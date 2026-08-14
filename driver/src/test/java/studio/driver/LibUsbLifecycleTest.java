/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.usb4java.Context;
import org.usb4java.LibUsb;
import studio.driver.event.DeviceHotplugEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle of the shared libusb context.
 *
 * <p>Two drivers each called {@code initializeLibUsb} from their constructor, and the context lived
 * in a static field that the second call overwrote. That created two native contexts, leaked the
 * first along with its event worker and — on Windows — its polling executor, and registered two
 * shutdown hooks that both released the second context and never the first.
 *
 * <p>Nothing native runs here: every libusb call and every thread the helper spawns goes through the
 * injected environment. Passing a fake context to the real native API would crash the JVM, which is
 * precisely why the seam exists.
 */
class LibUsbLifecycleTest {

    /** Records every lifecycle event in order, and never touches libusb. */
    static class FakeEnvironment implements LibUsbDetectionHelper.LibUsbEnvironment {
        final List<String> events = new ArrayList<>();
        final List<Context> createdContexts = new ArrayList<>();
        final List<Context> initialized = new ArrayList<>();
        final List<Context> exited = new ArrayList<>();
        final List<String> hotplugRegistrations = new ArrayList<>();
        final List<Runnable> shutdownHooks = new ArrayList<>();
        final List<StubAsyncWorker> asyncWorkers = new ArrayList<>();
        final AtomicInteger pollingWorkersCreated = new AtomicInteger();

        boolean hotplugSupported = true;
        int initResult = LibUsb.SUCCESS;
        boolean failOnHotplugRegistration = false;
        /** Whether the async worker was still alive when exit() was called. */
        Boolean workerAliveAtExit = null;

        @Override
        public Context createContext() {
            Context context = new Context();
            createdContexts.add(context);
            return context;
        }

        @Override
        public int init(Context context) {
            events.add("init");
            if (initResult == LibUsb.SUCCESS) {
                initialized.add(context);
            }
            return initResult;
        }

        @Override
        public void exit(Context context) {
            events.add("exit");
            exited.add(context);
            workerAliveAtExit = !asyncWorkers.isEmpty() && asyncWorkers.get(asyncWorkers.size() - 1).isAlive();
        }

        @Override
        public boolean hasHotplugCapability() {
            return hotplugSupported;
        }

        @Override
        public void registerHotplugCallback(Context context, int vendorId, int productId, DeviceHotplugEventListener listener) {
            if (failOnHotplugRegistration) {
                throw new IllegalStateException("registration exploded");
            }
            hotplugRegistrations.add(String.format("%04x:%04x", vendorId, productId));
        }

        @Override
        public LibUsbAsyncEventsWorker createAsyncEventsWorker(Context context) {
            events.add("createAsyncWorker");
            StubAsyncWorker worker = new StubAsyncWorker(context);
            asyncWorkers.add(worker);
            return worker;
        }

        @Override
        public Runnable createActivePollingWorker(Context context, DeviceVersion deviceVersion, DeviceHotplugEventListener listener) {
            pollingWorkersCreated.incrementAndGet();
            return () -> { /* nothing: the real scan is covered by LibUsbWorkerResilienceTest */ };
        }

        @Override
        public void registerShutdownHook(Runnable hook) {
            // Deliberately not registered with the JVM: a test must not leave hooks behind.
            shutdownHooks.add(hook);
        }
    }

    /**
     * A real {@link LibUsbAsyncEventsWorker} with only its native call stubbed, using the seam C3
     * introduced. Its abort/join behaviour — the part this test cares about — is the production one.
     */
    static class StubAsyncWorker extends LibUsbAsyncEventsWorker {
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean aborted = false;

        StubAsyncWorker(Context context) {
            super(context);
        }

        @Override
        protected int handleEvents() {
            started.countDown();
            // Yield so the loop does not monopolise a core while the test runs.
            Thread.yield();
            return LibUsb.SUCCESS;
        }

        @Override
        public void abort() {
            aborted = true;
            super.abort();
        }
    }

    private FakeEnvironment environment;

    private DeviceHotplugEventListener noopListener() {
        return new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(org.usb4java.Device device) {
            }

            @Override
            public void onDeviceUnplugged(org.usb4java.Device device) {
            }
        };
    }

    @BeforeEach
    void installFakeEnvironment() {
        environment = new FakeEnvironment();
        LibUsbDetectionHelper.setEnvironmentForTest(environment);
    }

    @AfterEach
    void restoreRealEnvironment() {
        LibUsbDetectionHelper.shutdown();
        LibUsbDetectionHelper.setEnvironmentForTest(null);
    }

    // ------------------------------------------------------------------ init

    @Test
    @DisplayName("L1: a normal startup initializes libusb exactly once")
    void initializesExactlyOnce() {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_1, noopListener());

        assertEquals(1, environment.initialized.size());
        assertEquals(1, environment.asyncWorkers.size(), "exactly one event worker");
        assertTrue(LibUsbDetectionHelper.isInitialized());
    }

    @Test
    @DisplayName("L2: two drivers share one context; the second call does not re-initialize")
    void twoDriversShareASingleContext() {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_1, noopListener());
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());

        // This is the defect: previously each call created its own context and its own worker, and
        // only the second remained reachable.
        assertEquals(1, environment.initialized.size(), "libusb must be initialized once, not once per driver");
        assertEquals(1, environment.asyncWorkers.size(), "a second event worker would be orphaned");
        assertEquals(1, environment.shutdownHooks.size(), "a second hook would release the same context twice");
    }

    @Test
    @DisplayName("L2b: both drivers still get their own detection registered")
    void bothDriversRegisterTheirOwnDetection() {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_1, noopListener());
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());

        // Sharing the context must not cost either driver its detection: 1 callback for the 1.x
        // device, 2 for the 2.x/3.x identifiers, exactly as before.
        assertEquals(List.of("0c45:6820", "0c45:6840", "0483:a341"), environment.hotplugRegistrations);
        assertSame(environment.initialized.get(0), environment.createdContexts.get(0));
    }

    @Test
    @DisplayName("L2c: without hotplug support, each driver gets its own polling task on one executor")
    void pollingPathSchedulesOneTaskPerDriver() {
        environment.hotplugSupported = false;

        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_1, noopListener());
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());

        // This is the Windows path. Two pollers, as before — but on a single shared context and a
        // single executor, where previously the first executor was leaked and never shut down.
        assertEquals(2, environment.pollingWorkersCreated.get());
        assertEquals(1, environment.initialized.size());
        assertTrue(environment.hotplugRegistrations.isEmpty());
    }

    // ------------------------------------------------------------------ shutdown

    @Test
    @DisplayName("L3: shutdown stops the worker before releasing the context, and exits once")
    void shutdownStopsWorkersBeforeExit() throws Exception {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());
        StubAsyncWorker worker = environment.asyncWorkers.get(0);
        assertTrue(worker.started.await(5, TimeUnit.SECONDS), "the worker should be running");

        LibUsbDetectionHelper.shutdown();

        assertEquals(1, environment.exited.size(), "exit must be called exactly once");
        assertTrue(worker.aborted, "the worker must be asked to stop");
        assertEquals(Boolean.FALSE, environment.workerAliveAtExit,
                "the context must not be released while the worker is still running");
        assertFalse(worker.isAlive());
        assertFalse(LibUsbDetectionHelper.isInitialized());
    }

    @Test
    @DisplayName("L3b: the registered shutdown hook performs that same shutdown")
    void theShutdownHookReleasesTheContext() {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());
        assertEquals(1, environment.shutdownHooks.size());

        environment.shutdownHooks.get(0).run();

        assertEquals(1, environment.exited.size());
        assertFalse(LibUsbDetectionHelper.isInitialized());
    }

    @Test
    @DisplayName("L4: shutting down twice does not exit twice and does not throw")
    void shutdownIsIdempotent() {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());

        LibUsbDetectionHelper.shutdown();
        LibUsbDetectionHelper.shutdown();
        LibUsbDetectionHelper.shutdown();

        assertEquals(1, environment.exited.size(), "the context must be released exactly once");
    }

    @Test
    @DisplayName("L4b: shutting down without ever initializing is a no-op")
    void shutdownWithoutInitIsANoOp() {
        LibUsbDetectionHelper.shutdown();

        assertTrue(environment.exited.isEmpty());
        assertFalse(LibUsbDetectionHelper.isInitialized());
    }

    // ------------------------------------------------------------------ failures

    @Test
    @DisplayName("L5: a failed init starts no worker and leaves no context behind")
    void failedInitLeavesACleanState() {
        environment.initResult = LibUsb.ERROR_OTHER;

        StoryTellerException raised = assertThrows(StoryTellerException.class,
                () -> LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener()));

        assertTrue(raised.getMessage().contains("Unable to initialize libusb"));
        assertTrue(environment.asyncWorkers.isEmpty(), "no worker may be started when init failed");
        assertTrue(environment.shutdownHooks.isEmpty(), "no hook for a context that does not exist");
        assertFalse(LibUsbDetectionHelper.isInitialized());
    }

    @Test
    @DisplayName("L5b: after a failed init, a later attempt can still succeed")
    void initCanBeRetriedAfterAFailure() {
        environment.initResult = LibUsb.ERROR_OTHER;
        assertThrows(StoryTellerException.class,
                () -> LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener()));

        environment.initResult = LibUsb.SUCCESS;
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());

        assertTrue(LibUsbDetectionHelper.isInitialized());
        assertEquals(1, environment.initialized.size());
    }

    @Test
    @DisplayName("L6: a second driver failing while registering does not leak the shared context")
    void aFailingSecondDriverDoesNotLeakTheContext() {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_1, noopListener());
        Context shared = environment.initialized.get(0);

        // The second driver's registration blows up. The context belongs to the helper, not to that
        // driver, so it must survive the failure and still be released exactly once at shutdown.
        environment.failOnHotplugRegistration = true;
        assertThrows(IllegalStateException.class,
                () -> LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener()));

        assertTrue(LibUsbDetectionHelper.isInitialized(), "the shared context must still be usable");
        assertEquals(1, environment.initialized.size(), "no second context was created");

        LibUsbDetectionHelper.shutdown();

        assertEquals(1, environment.exited.size(), "the one context created must be the one released");
        assertSame(shared, environment.exited.get(0));
    }

    @Test
    @DisplayName("L7: a worker that will not stop delays the exit but does not prevent it")
    void aStuckWorkerDoesNotPreventRelease() throws Exception {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());
        StubAsyncWorker worker = environment.asyncWorkers.get(0);
        assertTrue(worker.started.await(5, TimeUnit.SECONDS));

        long start = System.currentTimeMillis();
        LibUsbDetectionHelper.shutdown();
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(worker.aborted, "the worker must be asked to stop first");
        assertEquals(1, environment.exited.size(), "the context is released either way");
        assertTrue(elapsed < LibUsbDetectionHelper.WORKER_SHUTDOWN_TIMEOUT_MS,
                "a cooperative worker should stop well within the timeout, took " + elapsed + " ms");
    }

    // ------------------------------------------------------------------ restart

    @Test
    @DisplayName("L8: init, shutdown, init again yields a fresh context and no residual state")
    void supportsACleanRestart() {
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());
        Context first = environment.initialized.get(0);
        LibUsbDetectionHelper.shutdown();

        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, noopListener());

        assertEquals(2, environment.initialized.size());
        Context second = environment.initialized.get(1);
        assertNotSame(first, second, "a restart must not reuse a released context");
        assertEquals(2, environment.asyncWorkers.size(), "the second cycle gets its own worker");
        assertTrue(LibUsbDetectionHelper.isInitialized());

        LibUsbDetectionHelper.shutdown();
        assertEquals(List.of(first, second), environment.exited, "each context released exactly once");
    }
}
