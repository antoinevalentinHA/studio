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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Where the application itself accepts connections.
 *
 * <p>Companion to {@link HttpServerBindingTest}, and deliberately not a replacement for it. That one
 * starts a bare server on {@link MainVerticle#listenHost()} and proves the operating system honours
 * the address; it runs anywhere, because port 0 cannot collide with anything. This one deploys the
 * real {@link MainVerticle} and proves that the application actually uses that address — a bare
 * server bound correctly says nothing about what {@code start()} does. Both assertions are worth
 * having, and neither covers the other's gap.
 *
 * <p>The cost of testing the real verticle is that it binds {@link MainVerticle#LISTEN_PORT}, which
 * is fixed. There is no way to point it elsewhere, by design: the address is not configurable, and
 * a test-only seam to vary it would be the same override under another name. So when that port is
 * already taken — a developer running STUdio while the suite runs, most likely — these cases skip
 * rather than fail. On a CI runner the port is free and they run. {@code HttpServerBindingTest}
 * covers the address unconditionally, which is why it stays.
 *
 * <p>One note on {@link MainVerticle#LISTEN_PORT}: it is a {@code static final int}, so it is a
 * compile-time constant and javac inlines it here — the same trap that made {@code listenHost()} a
 * method rather than a field. The consequence is milder for the port, since a stale copy makes this
 * test connect to the wrong port and go red rather than silently agree with itself, but it is the
 * same shape of problem.
 *
 * <p>The verticle is deployed with {@code env=dev}, so the mock story teller service is used and
 * libusb is never touched, and with every path pointed at a temporary folder. The official metadata
 * database matters in particular: when its file is missing the service fetches it over the network,
 * and a test that quietly downloads a database is a test that fails on a train.
 */
@DisplayName("Where the application accepts connections")
class ServerBindingTest {

    /** Every system property these tests write, saved and restored around each one. */
    private static final List<String> TOUCHED_PROPERTIES = List.of(
            "studio.open", "env", "studio.library", "studio.tmpdir",
            "studio.db.official", "studio.db.unofficial");

    private final Map<String, String> savedProperties = new HashMap<>();

    @TempDir
    Path studioHome;

    private Vertx vertx;

    @BeforeEach
    void setUp() throws IOException {
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

        System.setProperty("env", "dev");           // mock story teller, no libusb
        System.setProperty("studio.open", "false"); // no browser

        vertx = Vertx.vertx();
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
    @DisplayName("the deployed application answers on the loopback")
    void deployedApplicationAcceptsLoopback() throws Exception {
        deployMainVerticle();

        assertTrue(waitForConnection(MainVerticle.listenHost(), MainVerticle.LISTEN_PORT),
                "the application should be reachable on " + MainVerticle.listenHost());
    }

    @Test
    @DisplayName("and on no other address of this machine")
    void deployedApplicationRefusesNonLoopback() throws Exception {
        String externalAddress = nonLoopbackAddress().orElse(null);
        assumeTrue(externalAddress != null,
                "no non-loopback IPv4 address on this machine, nothing to refuse");

        deployMainVerticle();

        // The loopback first: once it answers the socket is bound, so a refusal elsewhere is a real
        // refusal rather than a server that has not finished starting.
        assertTrue(waitForConnection(MainVerticle.listenHost(), MainVerticle.LISTEN_PORT),
                "the application should have started");
        assertFalse(connectsOnce(externalAddress, MainVerticle.LISTEN_PORT),
                "the application should not be reachable on " + externalAddress);
    }

    // ---------------------------------------------------------------- helpers

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
    }

    private static boolean isFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(MainVerticle.listenHost(), port));
            return true;
        } catch (IOException inUse) {
            return false;
        }
    }

    /** The server binds asynchronously, so give it a moment to appear before concluding. */
    private static boolean waitForConnection(String host, int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        do {
            if (connectsOnce(host, port)) {
                return true;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    private static boolean connectsOnce(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1_000);
            return true;
        } catch (IOException refusedOrUnreachable) {
            return false;
        }
    }

    /**
     * An IPv4 address of this machine that is not the loopback, if it has one. A CI runner normally
     * does; a container often does not, and the case needing one is skipped rather than failed.
     */
    private static Optional<String> nonLoopbackAddress() throws Exception {
        for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                if (address.getAddress().length == 4 && !address.isLoopbackAddress()) {
                    return Optional.of(address.getHostAddress());
                }
            }
        }
        return Optional.empty();
    }
}
