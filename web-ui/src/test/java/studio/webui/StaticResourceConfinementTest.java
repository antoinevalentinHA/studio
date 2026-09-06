/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the static handler will hand out, and what it will not.
 *
 * <p>The bundled Vert.x-Web 3.9 normalises a request path by collapsing {@code ..} over {@code /},
 * and by decoding {@code %2e} on the way. It does neither for a backslash: {@code %5c} survives
 * normalisation intact, and on Windows the filesystem then treats it as a separator. A single
 * request could therefore step one level above the web root and be answered with a resource from the
 * application's own classpath — its configuration, its translations, whatever sits in the bundled
 * jars. Measured, not deduced: three request shapes returned a sentinel placed at the classpath root.
 *
 * <p>This is the same class of defect as CVE-2023-24815, which is declared for Vert.x-Web 4.0.0 to
 * 4.3.7 and reached through a wildcard mount that does not exist in 3.9. Version 3.9 never got the
 * fix, and there is no 3.x release that has it, so the guard lives here instead.
 *
 * <p>The test deploys the real {@link MainVerticle}, like {@link ServerBindingTest}, because the
 * property under test is the route configuration and not a handler in isolation: a guard on a
 * hand-rolled router would prove only that the guard works. It costs the same constraint — the real
 * verticle binds {@link MainVerticle#LISTEN_PORT}, so everything here skips when that port is taken.
 *
 * <p>The assertion is on the consequence rather than on the status code. A refusal that answered 404
 * and a refusal that answered 400 are both acceptable; a response carrying the sentinel is not,
 * whatever its status. The nominal cases carry equal weight, and one of them is there for a specific
 * reason: a build asset is named {@code runtime~main.<hash>.js}, so a guard written over letters,
 * digits and the obvious punctuation would refuse a file the application needs. That is the mistake
 * this pins.
 */
@DisplayName("What the static handler will serve")
class StaticResourceConfinementTest {

    /** The marker in {@code src/test/resources/classpath-sentinel.txt}, at the classpath root. */
    private static final String SENTINEL = "STUDIO-CLASSPATH-SENTINEL-DO-NOT-SERVE";

    private static final String SENTINEL_FILE = "classpath-sentinel.txt";

    /**
     * Request paths that reached the sentinel, or that would if an encoding stopped surviving
     * normalisation. The last one is a regression pin rather than a live hole: {@code %2f} is not
     * decoded today, so it answers a plain 404 — if that ever changes, the same defect becomes
     * cross-platform instead of Windows-only, and this goes red rather than quiet.
     */
    private static final List<String> ESCAPES = List.of(
            "/..%5C" + SENTINEL_FILE,
            "/%2e%2e%5C" + SENTINEL_FILE,
            "/a%5C..%5C..%5C" + SENTINEL_FILE,
            "/..%2F" + SENTINEL_FILE);

    private static final List<String> TOUCHED_PROPERTIES = List.of(
            "studio.open", "env", "studio.library", "studio.tmpdir",
            "studio.db.official", "studio.db.unofficial");

    private final Map<String, String> savedProperties = new HashMap<>();

    @TempDir
    Path studioHome;

    private Vertx vertx;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(isFree(MainVerticle.LISTEN_PORT),
                "port " + MainVerticle.LISTEN_PORT + " is already in use, most likely by a running "
                        + "STUdio; the real verticle cannot be deployed anywhere else");

        for (String property : TOUCHED_PROPERTIES) {
            savedProperties.put(property, System.getProperty(property));
        }
        Path officialDb = studioHome.resolve("official.json");
        Files.write(officialDb, "{}".getBytes(StandardCharsets.UTF_8));
        System.setProperty("studio.db.official", officialDb.toString());
        System.setProperty("studio.db.unofficial", studioHome.resolve("unofficial.json").toString());
        System.setProperty("studio.library",
                Files.createDirectories(studioHome.resolve("library")) + "/");
        System.setProperty("studio.tmpdir",
                Files.createDirectories(studioHome.resolve("tmp")) + "/");
        System.setProperty("env", "dev");
        System.setProperty("studio.open", "false");

        vertx = Vertx.vertx();
        deployMainVerticle();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (vertx != null) {
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close(ar -> closed.countDown());
            closed.await(30, TimeUnit.SECONDS);
        }
        savedProperties.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    @Test
    @DisplayName("no request escapes the web root into the classpath")
    void escapesAreRefused() {
        for (String escape : ESCAPES) {
            String response = get(escape);
            assertFalse(response.contains(SENTINEL),
                    "`" + escape + "` returned a resource from outside the web root; the response was:\n"
                            + response);
        }
    }

    @Test
    @DisplayName("the web root is still served")
    void theWebRootIsStillServed() {
        // Only what `src/main/resources/webroot` holds is on the classpath here. The React bundle is
        // copied in at `prepare-package`, which runs after `test`, and the CI job skips the frontend
        // goals entirely — so index.html and the hashed assets do not exist at this point. Asserting
        // on them would pass on a warm `target` and fail on a clean build, which is how this test was
        // first written and how it was caught.
        assertTrue(get("/favicon.png").contains("200"), "the favicon must still be served");
        assertTrue(get("/locales/fr/translation.json").contains("200"),
                "the translations must still be served");
    }

    @Test
    @DisplayName("the shapes the built bundle actually uses are accepted, tilde included")
    void realAssetPathsAreAccepted() {
        // Taken from a real packaged bundle rather than imagined. The tilde is the reason this test
        // exists: a guard written over letters, digits and the obvious punctuation would refuse
        // `runtime~main.<hash>.js`, and the application would not start in a browser. Checked against
        // the policy directly, since the bundle is not on the classpath while tests run.
        List<String> realPaths = List.of(
                "/",
                "/index.html",
                "/favicon.png",
                "/asset-manifest.json",
                "/service-worker.js",
                "/precache-manifest.90d8c5352b8a5422f10623925a66ff25.js",
                "/locales/fr/translation.json",
                "/static/css/main.96cda0b2.chunk.css",
                "/static/css/2.ffcf707b.chunk.css.map",
                "/static/js/main.1fefc518.chunk.js",
                "/static/js/runtime~main.a8a9905a.js",
                "/static/js/runtime~main.a8a9905a.js.map");
        for (String path : realPaths) {
            assertTrue(MainVerticle.isServableStaticPath(path),
                    "`" + path + "` is a path the built bundle serves and must not be refused");
        }

        for (String escape : List.of("/..%5Cx", "/..\\x", "/%2e%2e%5Cx", "/..%2Fx", "/../x", "x", "")) {
            assertFalse(MainVerticle.isServableStaticPath(escape),
                    "`" + escape + "` must not reach the static handler");
        }
    }

    /** Status line and body of a GET, as one string. HTTP/1.0 so the server closes when done. */
    private String get(String target) {
        StringBuilder response = new StringBuilder();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(MainVerticle.listenHost(), MainVerticle.LISTEN_PORT),
                    5_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.0\r\nHost: localhost\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append('\n');
            }
        } catch (IOException e) {
            fail("requesting `" + target + "` failed: " + e, e);
        }
        return response.toString();
    }

    private void deployMainVerticle() throws InterruptedException {
        CountDownLatch deployed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        vertx.deployVerticle(new MainVerticle(), ar -> {
            if (ar.failed()) {
                failure.set(ar.cause());
            }
            deployed.countDown();
        });
        assertTrue(deployed.await(60, TimeUnit.SECONDS), "deploying MainVerticle timed out");
        if (failure.get() != null) {
            fail("deploying MainVerticle failed", failure.get());
        }
        waitUntilServing();
    }

    /** The server binds asynchronously; do not conclude anything from a connection refused early. */
    private static void waitUntilServing() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        do {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(MainVerticle.listenHost(),
                        MainVerticle.LISTEN_PORT), 1_000);
                return;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        } while (System.currentTimeMillis() < deadline);
        fail("the application did not start listening");
    }

    private static boolean isFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(MainVerticle.listenHost(), port));
            return true;
        } catch (IOException inUse) {
            return false;
        }
    }
}
