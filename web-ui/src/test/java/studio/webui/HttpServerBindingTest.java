/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Where the HTTP server can be reached from.
 *
 * <p>The server used to be started with {@code listen(int)}, whose default host is {@code 0.0.0.0}:
 * every machine on the same network segment could reach an API that has no authentication, and read,
 * write or delete in the user's library while STUdio was running. The launcher opens a browser on
 * {@code localhost}, so nothing told the user anything else was listening.
 *
 * <p>This asserts the consequence rather than the constant. It starts a real server on the address
 * production uses, then opens real sockets: the loopback must answer, and a non-loopback address of
 * this same machine must not. Reading {@link MainVerticle#listenHost()} back would only restate the
 * source; connecting proves what the operating system actually did with it.
 *
 * <p>The refusal case needs a second local address to aim at, and a container often has none. When
 * there is no non-loopback IPv4 address the case is skipped rather than passed — a machine that
 * cannot set up the situation has not established anything about it. The nominal case runs
 * everywhere, and carries equal weight: a server bound to nothing at all would pass every refusal.
 */
class HttpServerBindingTest {

    /** Long enough for a local connection to complete, short enough that a refusal is not a wait. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    private Vertx vertx;
    private HttpServer server;
    private int port;

    @BeforeEach
    void startServerOnTheProductionAddress() throws Exception {
        vertx = Vertx.vertx();
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        // Port 0, so the test never collides with a running STUdio or with a parallel run. The
        // address is production's, which is the whole point.
        vertx.createHttpServer()
                .requestHandler(request -> request.response().end("ok"))
                .listen(0, MainVerticle.listenHost(), ar -> {
                    if (ar.succeeded()) {
                        started.complete(ar.result());
                    } else {
                        started.completeExceptionally(ar.cause());
                    }
                });
        server = started.get(10, TimeUnit.SECONDS);
        port = server.actualPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (vertx != null) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            vertx.close(ar -> closed.complete(null));
            closed.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("the web UI can still reach the server on the loopback")
    void loopbackIsReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            // A whole request and answer, not just a completed handshake: an open port proves the
            // socket was accepted, this proves the application behind it still serves. It also lets
            // the server finish with the connection before the teardown closes Vert.x under it.
            socket.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String status = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertNotNull(status, "the server accepted the connection and then answered nothing");
            assertTrue(status.contains("200"), "expected a served response, got: " + status);
        } catch (IOException e) {
            fail("the application must still be reachable where it serves its own UI: " + e, e);
        }
    }

    @Test
    @DisplayName("no other machine on the network can reach it")
    void nonLoopbackIsRefused() {
        Optional<InetAddress> routable = aNonLoopbackAddressOfThisMachine();
        Assumptions.assumeTrue(routable.isPresent(),
                "this machine has no non-loopback IPv4 address, so it cannot set up the situation");

        InetSocketAddress target = new InetSocketAddress(routable.get(), port);
        assertThrows(IOException.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(target, CONNECT_TIMEOUT_MS);
            }
        }, "the server answered on " + target + ", so it is listening beyond the loopback and the "
                + "unauthenticated API is reachable from the local network");
    }

    /**
     * An IPv4 address of this machine that another machine could route to, if there is one.
     *
     * <p>Loopback, down interfaces and link-local addresses are all skipped: none of them is a
     * credible stand-in for "somebody else on the network".
     */
    private Optional<InetAddress> aNonLoopbackAddressOfThisMachine() {
        List<InetAddress> candidates = new ArrayList<>();
        try {
            for (NetworkInterface itf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!itf.isUp() || itf.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(itf.getInetAddresses())) {
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        candidates.add(address);
                    }
                }
            }
        } catch (Exception e) {
            return Optional.empty();
        }
        return candidates.stream().findFirst();
    }
}
