/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

import org.usb4java.Context;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;
import studio.driver.event.DeviceHotplugEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sole owner of the libusb context.
 *
 * <p>Two drivers need USB detection — one for 1.x devices, one for 2.x/3.x — and each used to call
 * {@link #initializeLibUsb} from its own constructor. Because the context lived in a static field
 * that each call overwrote, that produced two native contexts of which only the second was ever
 * reachable: the first was leaked, its event worker orphaned, and on Windows its polling executor
 * kept a non-daemon thread alive for the lifetime of the JVM. Both calls also registered a shutdown
 * hook, so {@code LibUsb.exit} ran twice on the second context and never on the first.
 *
 * <p>Initialisation is now idempotent. The first call creates the context, starts the event worker
 * and registers one shutdown hook; every later call reuses that same context and only adds its own
 * detection — a hotplug callback, or a polling task where hotplug is unavailable. {@link #shutdown()}
 * stops every worker before releasing the context, exactly once, and is safe to call repeatedly.
 *
 * <p>Detection behaviour itself is unchanged: same vendor/product identifiers, same choice between
 * hotplug and active polling, same poll delay, same workers.
 */
public class LibUsbDetectionHelper {

    private static final Logger LOGGER = Logger.getLogger(LibUsbDetectionHelper.class.getName());

    // USB device
    public static final int VENDOR_ID_FW1 = 0x0c45;
    public static final int PRODUCT_ID_FW1 = 0x6820;
    public static final int VENDOR_ID_FW2 = 0x0c45;
    public static final int PRODUCT_ID_FW2 = 0x6840;
    public static final int VENDOR_ID_V2 = 0x0483;
    public static final int PRODUCT_ID_V2 = 0xa341;

    private static final long POLL_DELAY = 5000L;

    /** How long shutdown waits for a worker to stop before releasing the context anyway. */
    static final long WORKER_SHUTDOWN_TIMEOUT_MS = 5000L;

    private static final Object LOCK = new Object();

    // All guarded by LOCK. A non-null context means "initialized and not yet shut down".
    private static Context context = null;
    private static LibUsbAsyncEventsWorker asyncEventHandlerWorker = null;
    private static ScheduledExecutorService scheduledExecutor = null;
    private static final List<Future<?>> activePollingTasks = new ArrayList<>();
    private static boolean shutdownHookRegistered = false;

    private static LibUsbEnvironment environment = new NativeLibUsbEnvironment();

    private LibUsbDetectionHelper() {
    }

    /**
     * Initializes libusb if needed, then registers detection for the given device version.
     *
     * <p>Safe to call once per driver: only the first call creates a context and starts the event
     * worker.
     *
     * @param deviceVersion The version of the device to detect
     * @param listener A hotplug listener
     */
    public static void initializeLibUsb(DeviceVersion deviceVersion, DeviceHotplugEventListener listener) {
        synchronized (LOCK) {
            ensureContextInitialized();
            registerDetection(deviceVersion, listener);
        }
    }

    private static void ensureContextInitialized() {
        if (context != null) {
            LOGGER.fine("libusb already initialized; reusing the shared context");
            return;
        }

        LOGGER.info("Initializing libusb...");
        Context newContext = environment.createContext();
        int result = environment.init(newContext);
        if (result != LibUsb.SUCCESS) {
            // Nothing has been started yet, so there is nothing to unwind: the context stays null and
            // a later call can try again from a clean state.
            throw new StoryTellerException("Unable to initialize libusb.", new LibUsbException(result));
        }
        context = newContext;

        asyncEventHandlerWorker = environment.createAsyncEventsWorker(context);
        asyncEventHandlerWorker.start();

        if (!shutdownHookRegistered) {
            environment.registerShutdownHook(LibUsbDetectionHelper::shutdown);
            shutdownHookRegistered = true;
        }

        LOGGER.info("libusb initialized; context owned by LibUsbDetectionHelper, event worker started");
    }

    private static void registerDetection(DeviceVersion deviceVersion, DeviceHotplugEventListener listener) {
        if (environment.hasHotplugCapability()) {
            LOGGER.info("Hotplug is supported. Registering hotplug callback(s) for " + deviceVersion + "...");
            if (deviceVersion == DeviceVersion.DEVICE_VERSION_1 || deviceVersion == DeviceVersion.DEVICE_VERSION_ANY) {
                environment.registerHotplugCallback(context, VENDOR_ID_FW1, PRODUCT_ID_FW1, listener);
            }
            if (deviceVersion == DeviceVersion.DEVICE_VERSION_2 || deviceVersion == DeviceVersion.DEVICE_VERSION_ANY) {
                environment.registerHotplugCallback(context, VENDOR_ID_FW2, PRODUCT_ID_FW2, listener);
                environment.registerHotplugCallback(context, VENDOR_ID_V2, PRODUCT_ID_V2, listener);
            }
        } else {
            LOGGER.info("Hotplug is NOT supported. Scheduling task to actively poll USB device for " + deviceVersion + "...");
            if (scheduledExecutor == null) {
                // Daemon threads: an executor that is never shut down must not be able to keep the JVM
                // alive, which is exactly what the leaked second executor used to do.
                scheduledExecutor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("libusb-active-polling"));
            }
            activePollingTasks.add(scheduledExecutor.scheduleAtFixedRate(
                    environment.createActivePollingWorker(context, deviceVersion, listener),
                    0, POLL_DELAY, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * Stops every worker, then releases the libusb context. Idempotent: calling it again once the
     * context has been released does nothing.
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (context == null) {
                // Never initialized, or already shut down. Both are a no-op, which is what makes a
                // second shutdown hook (or an explicit call) harmless.
                return;
            }
            LOGGER.info("Shutting down libusb detection...");

            for (Future<?> task : activePollingTasks) {
                task.cancel(true);
            }
            activePollingTasks.clear();

            if (scheduledExecutor != null) {
                scheduledExecutor.shutdownNow();
                try {
                    scheduledExecutor.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                scheduledExecutor = null;
                LOGGER.info("Active polling stopped");
            }

            if (asyncEventHandlerWorker != null) {
                asyncEventHandlerWorker.abort();
                try {
                    // Bounded: the context is released even if the worker is wedged in a native call,
                    // but only after it has been asked to stop and given a chance to do so.
                    asyncEventHandlerWorker.join(WORKER_SHUTDOWN_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (asyncEventHandlerWorker.isAlive()) {
                    LOGGER.warning("Async event worker did not stop within "
                            + WORKER_SHUTDOWN_TIMEOUT_MS + " ms; releasing the context anyway");
                } else {
                    LOGGER.info("Async event worker stopped");
                }
                asyncEventHandlerWorker = null;
            }

            environment.exit(context);
            context = null;
            LOGGER.info("libusb released");
        }
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    // ------------------------------------------------------------------ test seam

    /**
     * Everything in this class that touches libusb or spawns a thread. The production implementation
     * is the only caller of the native API; tests substitute a double so the lifecycle can be
     * exercised without a USB stack — passing a fake context to a native call would crash the JVM.
     */
    interface LibUsbEnvironment {
        Context createContext();

        int init(Context context);

        void exit(Context context);

        boolean hasHotplugCapability();

        void registerHotplugCallback(Context context, int vendorId, int productId, DeviceHotplugEventListener listener);

        LibUsbAsyncEventsWorker createAsyncEventsWorker(Context context);

        Runnable createActivePollingWorker(Context context, DeviceVersion deviceVersion, DeviceHotplugEventListener listener);

        void registerShutdownHook(Runnable hook);
    }

    /** Swaps the environment and resets all state. Test-only. */
    static void setEnvironmentForTest(LibUsbEnvironment testEnvironment) {
        synchronized (LOCK) {
            context = null;
            asyncEventHandlerWorker = null;
            scheduledExecutor = null;
            activePollingTasks.clear();
            shutdownHookRegistered = false;
            environment = testEnvironment != null ? testEnvironment : new NativeLibUsbEnvironment();
        }
    }

    static boolean isInitialized() {
        synchronized (LOCK) {
            return context != null;
        }
    }

    /** The real thing. Behaviour is identical to what this class did before it was extracted. */
    private static class NativeLibUsbEnvironment implements LibUsbEnvironment {

        @Override
        public Context createContext() {
            return new Context();
        }

        @Override
        public int init(Context context) {
            return LibUsb.init(context);
        }

        @Override
        public void exit(Context context) {
            LibUsb.exit(context);
        }

        @Override
        public boolean hasHotplugCapability() {
            return LibUsb.hasCapability(LibUsb.CAP_HAS_HOTPLUG);
        }

        @Override
        public LibUsbAsyncEventsWorker createAsyncEventsWorker(Context context) {
            return new LibUsbAsyncEventsWorker(context);
        }

        @Override
        public Runnable createActivePollingWorker(Context context, DeviceVersion deviceVersion, DeviceHotplugEventListener listener) {
            return new LibUsbActivePollingWorker(context, deviceVersion, listener);
        }

        @Override
        public void registerShutdownHook(Runnable hook) {
            Runtime.getRuntime().addShutdownHook(new Thread(hook, "libusb-shutdown"));
        }

        @Override
        public void registerHotplugCallback(Context context, int vendorId, int productId, DeviceHotplugEventListener listener) {
            LibUsb.hotplugRegisterCallback(
                    context,
                    LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED | LibUsb.HOTPLUG_EVENT_DEVICE_LEFT,
                    LibUsb.HOTPLUG_ENUMERATE,   // Arm the callback and fire it for all matching currently attached devices
                    vendorId,
                    productId,
                    LibUsb.HOTPLUG_MATCH_ANY,   // Device class
                    (ctx, device, event, userData) -> {
                        LOGGER.info(String.format("Hotplug event callback (%04x:%04x): " + event, vendorId, productId));
                        switch (event) {
                            case LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED:
                                CompletableFuture.runAsync(() -> listener.onDevicePlugged(device))
                                        .exceptionally(e -> {
                                            LOGGER.log(Level.SEVERE, "An error occurred while handling device plug event", e);
                                            return null;
                                        });
                                break;
                            case LibUsb.HOTPLUG_EVENT_DEVICE_LEFT:
                                CompletableFuture.runAsync(() -> listener.onDeviceUnplugged(device))
                                        .exceptionally(e -> {
                                            LOGGER.log(Level.SEVERE, "An error occurred while handling device unplug event", e);
                                            return null;
                                        });
                                break;
                        }
                        return 0;   // Do not deregister the callback
                    },
                    null,
                    null
            );
        }
    }
}
