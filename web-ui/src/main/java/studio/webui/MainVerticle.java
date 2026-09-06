/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.bridge.BridgeEventType;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.ErrorHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import studio.metadata.DatabaseMetadataService;
import studio.webui.api.DeviceController;
import studio.webui.api.EvergreenController;
import studio.webui.api.LibraryController;
import studio.webui.service.*;
import studio.webui.service.mock.MockStoryTellerService;

import java.awt.*;
import java.net.URI;
import java.util.Set;

public class MainVerticle extends AbstractVerticle {

    /**
     * The address the HTTP server binds to: the loopback, and only the loopback.
     *
     * <p>Named rather than left to {@code listen(int)}, whose default is {@code 0.0.0.0} — every
     * interface. That default put an API with no authentication on the local network, where anything
     * on the same segment could read, write and delete in the user's library while STUdio ran. The
     * CORS filter below does not cover it: CORS is enforced by browsers, on requests issued by a
     * page, and says nothing to {@code curl} or a script on another machine.
     *
     * <p>Nothing is lost by restricting it. When this was written the web UI addressed the server as
     * {@code http://localhost:8080}, hardcoded throughout the frontend, so a browser on another
     * machine would receive the page and then send every request to its own loopback: the wider
     * binding exposed the API without ever making the application usable from elsewhere. That is no
     * longer the reason, because the frontend now uses relative addresses and follows whatever
     * origin served it — remote use would work if this bound wider.
     *
     * <p>Deliberately fixed and not a setting. The rule is not that authentication has to live inside
     * STUdio; it is that an unauthenticated STUdio socket must not become directly reachable from the
     * network. STUdio may sit behind authentication it does not provide — its own listener stays
     * unreachable except from this host.
     *
     * <p>That distinction is what decides the cases, and it is worth keeping because it decides them
     * differently. An authenticating proxy on this same machine satisfies the rule: it reaches the
     * loopback, and nothing here has to become configurable. A proxy on another machine does not,
     * however well it authenticates: the hop to STUdio would be unauthenticated and in the clear, so
     * anything able to reach this port bypasses the proxy entirely, and the safety would rest on a
     * firewall rule this application neither owns nor verifies. Safety an application cannot check is
     * not safety it can claim. Until the API authenticates, the loopback stays.
     *
     * <p>Worked out in issue #45 and recorded here rather than left in that thread.
     *
     * <p>A method rather than a {@code static final String}, so that the test which asserts this can
     * read it. A compile-time constant is inlined into whatever reads it, and a test holding an
     * inlined copy would go on binding to the address it was compiled against — passing while
     * production had been changed under it. That is precisely the regression this must catch.
     */
    static String listenHost() {
        return "127.0.0.1";
    }

    static final int LISTEN_PORT = 8080;

    private final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

    private DatabaseMetadataService databaseMetadataService;
    private LibraryService libraryService;
    private EvergreenService evergreenService;
    private IStoryTellerService storyTellerService;

    @Override
    public void start() {

        // Service that manages pack metadata
        databaseMetadataService = new DatabaseMetadataService(false);

        // Service that manages local library
        libraryService = new LibraryService(databaseMetadataService);

        // Service that manages updates
        evergreenService = new EvergreenService(vertx);

        // Service that manages link with the story teller device
        if (isDevMode()) {
            LOGGER.warn("[DEV MODE] Initializing mock storyteller service");
            storyTellerService = new MockStoryTellerService(vertx.eventBus(), databaseMetadataService);
        } else {
            storyTellerService = new StoryTellerService(vertx.eventBus(), databaseMetadataService);
        }


        Router router = Router.router(vertx);

        // Handle cross-origin calls
        router.route().handler(CorsHandler.create("http://localhost:.*")
                .allowedMethods(Set.of(
                        HttpMethod.GET,
                        HttpMethod.POST
                ))
                .allowedHeaders(Set.of(
                        HttpHeaders.ACCEPT.toString(),
                        HttpHeaders.CONTENT_TYPE.toString(),
                        "x-requested-with"
                ))
                .exposedHeaders(Set.of(
                        HttpHeaders.CONTENT_LENGTH.toString(),
                        HttpHeaders.CONTENT_TYPE.toString()
                ))
        );

        // Bridge event-bus to client-side app
        router.route("/eventbus/*").handler(eventBusHandler());

        // Rest API
        router.mountSubRouter("/api", apiRouter());

        // Static resources (/webroot), behind a check on what the path is allowed to contain
        router.route().handler(context -> {
            String path = context.normalisedPath();
            if (isServableStaticPath(path)) {
                context.next();
            } else {
                // Answered here rather than failed, so the failure handler does not log an exception
                // that does not exist. 404 rather than 400: a refusal should not confirm anything
                // about what is or is not behind the web root.
                LOGGER.warn("Refusing a static path that is not a plain web-root path: " + path);
                context.response().setStatusCode(404).end();
            }
        });
        router.route().handler(StaticHandler.create().setCachingEnabled(false));

        // Error handler
        ErrorHandler errorHandler = ErrorHandler.create(true);
        router.route().failureHandler(ctx -> {
            Throwable failure = ctx.failure();
            LOGGER.error("Exception thrown", failure);
            errorHandler.handle(ctx);
        });

        // Start HTTP server
        vertx.createHttpServer().requestHandler(router).listen(LISTEN_PORT, listenHost());
        // Automatically open URL in browser, unless instructed otherwise
        String openBrowser = System.getProperty("studio.open", "true");
        if (Boolean.valueOf(openBrowser)) {
            LOGGER.info("Opening URL in default browser...");
            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().browse(new URI("http://localhost:8080"));
                } catch (Exception e) {
                    LOGGER.error("Failed to open URL in default browser", e);
                }
            }
        }
    }

    private SockJSHandler eventBusHandler() {
        BridgeOptions options = new BridgeOptions()
                .addOutboundPermitted(new PermittedOptions().setAddressRegex("storyteller\\.(.+)"));
        SockJSHandler sockJSHandler = SockJSHandler.create(vertx);
        sockJSHandler.bridge(options, event -> {
            if (event.type() == BridgeEventType.SOCKET_CREATED) {
                LOGGER.debug("New sockjs client");
            }
            event.complete(true);
        });
        return sockJSHandler;
    }

    private Router apiRouter() {
        Router router = Router.router(vertx);

        // Handle JSON
        router.route().handler(BodyHandler.create());
        router.route().consumes("application/json");
        router.route().produces("application/json");


        // Device services
        router.mountSubRouter("/device", DeviceController.apiRouter(vertx, storyTellerService, libraryService));

        // Library services
        router.mountSubRouter("/library", LibraryController.apiRouter(vertx, libraryService));

        // Evergreen services
        router.mountSubRouter("/evergreen", EvergreenController.apiRouter(vertx, evergreenService));

        return router;
    }

    private boolean isDevMode() {
        return "dev".equalsIgnoreCase(System.getProperty("env", "prod"));
    }

    /**
     * Whether a normalised request path may be handed to the static handler.
     *
     * <p>An allow list, and deliberately not a deny list. The bundled Vert.x-Web 3.9 normalises
     * inconsistently — it collapses {@code ..} over {@code /} and decodes {@code %2e}, but leaves
     * {@code %5c} and {@code %2f} encoded — so a backslash survives normalisation and, on Windows,
     * is then a separator to the filesystem. Enumerating the encodings that survive would mean
     * enumerating the ones *this version* happens to leave alone; a later Vert.x could change the
     * set and the check would stop matching while still passing its tests. Naming what is allowed
     * does not have that failure mode.
     *
     * <p>What is allowed is the unreserved set of RFC 3986 — letters, digits, {@code - . _ ~} — plus
     * the separator. That is not a guess: it is the set the built bundle actually uses, and the
     * tilde is in it because an asset is named {@code runtime~main.<hash>.js}. A percent sign is not,
     * which is what refuses every encoded escape without naming any of them.
     *
     * <p>The {@code ..} segment check is redundant while normalisation collapses them, and kept
     * because it costs nothing and does not depend on that staying true.
     */
    static boolean isServableStaticPath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '/';
            if (!unreserved) {
                return false;
            }
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return false;
            }
        }
        return true;
    }
}
