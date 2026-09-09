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
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Which origins the API answers.
 *
 * <p>The application serves its own UI and then opens a browser on it, at
 * {@link MainVerticle#listenHost()} — {@code 127.0.0.1} since the server stopped binding to every
 * interface. The CORS policy, written when the server was reached by name, allowed
 * {@code http://localhost:} and nothing else. A browser sends an {@code Origin} header on a POST
 * even when it is same-origin, so the page the application had just opened was refused by the
 * application that opened it: every SockJS request of the event bus handshake answered
 * {@code 403 CORS Rejected - Invalid origin}, and the UI lost device monitoring entirely, retrying
 * for as long as it stayed open.
 *
 * <p>The rule is one sentence: <strong>the loopback is one host under several names</strong>. Whether
 * the address bar says {@code localhost}, {@code 127.0.0.1} or {@code [::1]}, it is the machine
 * asking about itself, and the answer must not depend on the spelling.
 *
 * <p>Acceptance alone would be a poor test of an origin policy — a server that allowed everything
 * would pass it — so a foreign origin is refused here with equal weight. That is also what keeps the
 * fix from quietly becoming {@code *}: this API has no authentication, and a page on another origin
 * must not be able to read the library through the browser of whoever visits it.
 *
 * <p>Like {@link StaticResourceConfinementTest}, this deploys the real {@link MainVerticle}, because
 * the property under test is the router's configuration rather than a handler in isolation. It costs
 * the same constraint: the real verticle binds {@link MainVerticle#LISTEN_PORT}, so everything here
 * skips when that port is taken.
 */
@DisplayName("Which origins the API answers")
class CorsOriginTest {

    /** What Vert.x-Web puts on the wire when it turns an origin down. */
    private static final String REFUSAL = "403";

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
    @DisplayName("the origin the application opens in the browser is answered")
    void theApplicationsOwnOriginIsAnswered() {
        String ownOrigin = "http://" + MainVerticle.listenHost() + ":" + MainVerticle.LISTEN_PORT;

        String response = getWithOrigin("/api/device/infos", ownOrigin);

        assertFalse(response.contains(REFUSAL),
                "the application opens the browser on " + ownOrigin + " and then refuses it; the "
                        + "event bus handshake cannot complete. The response was:\n" + response);
    }

    @Test
    @DisplayName("the loopback is answered whatever it is spelled")
    void everySpellingOfTheLoopbackIsAnswered() {
        for (String origin : List.of("http://localhost:" + MainVerticle.LISTEN_PORT,
                "http://127.0.0.1:" + MainVerticle.LISTEN_PORT,
                "http://[::1]:" + MainVerticle.LISTEN_PORT,
                // The development server, which proxies to this one and is where the UI is worked on.
                "http://localhost:3000",
                "http://127.0.0.1:3000")) {
            String response = getWithOrigin("/api/device/infos", origin);
            assertFalse(response.contains(REFUSAL),
                    "`" + origin + "` is this machine asking about itself and must be answered; the "
                            + "response was:\n" + response);
        }
    }

    @Test
    @DisplayName("a page on another origin is still refused")
    void aForeignOriginIsRefused() {
        // The API has no authentication: whatever a page on another origin could reach here, it
        // could reach in the browser of anyone who visits it.
        for (String origin : List.of("http://studio.example.com",
                "https://studio.example.com",
                "http://localhost.example.com:8080",
                "http://127.0.0.1.example.com:8080")) {
            String response = getWithOrigin("/api/device/infos", origin);
            assertTrue(response.contains(REFUSAL),
                    "`" + origin + "` is not this machine and must be refused; the response was:\n"
                            + response);
        }
    }

    /** Status line and body of a GET carrying an {@code Origin}, as one string. */
    private String getWithOrigin(String target, String origin) {
        StringBuilder response = new StringBuilder();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(MainVerticle.listenHost(), MainVerticle.LISTEN_PORT),
                    5_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(("GET " + target + " HTTP/1.0\r\nHost: localhost\r\n"
                    + "Origin: " + origin + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append('\n');
            }
        } catch (IOException e) {
            fail("requesting `" + target + "` from `" + origin + "` failed: " + e, e);
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
