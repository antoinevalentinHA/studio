/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.usb4java.LibUsb;
import org.usb4java.LibUsbException;
import studio.driver.event.DeviceHotplugEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Device detection survives a transient libusb failure.
 *
 * <p>Both workers below used to let a failure escape, and in both cases the consequence was the
 * same and invisible: the backend kept running and serving the UI, but no device was ever reported
 * again until STUdio was restarted. These tests drive the retry behaviour through the seams the
 * workers expose, so no native libusb, no USB device and no drive letter are involved.
 */
class LibUsbWorkerResilienceTest {

    @Nested
    @DisplayName("async event worker")
    class AsyncEventWorker {

        /** Test double: the libusb call is scripted, and the backoff is instant. */
        class ScriptedWorker extends LibUsbAsyncEventsWorker {
            private final List<Object> script;
            final AtomicInteger calls = new AtomicInteger();
            final List<Long> pauses = new ArrayList<>();
            private final CountDownLatch scriptExhausted;

            ScriptedWorker(List<Object> script) {
                super(null);
                this.script = script;
                this.scriptExhausted = new CountDownLatch(1);
            }

            @Override
            protected int handleEvents() {
                int index = calls.getAndIncrement();
                if (index >= script.size()) {
                    scriptExhausted.countDown();
                    // Keep the loop alive but idle until the test aborts it.
                    return LibUsb.SUCCESS;
                }
                Object step = script.get(index);
                if (step instanceof RuntimeException) {
                    throw (RuntimeException) step;
                }
                return (Integer) step;
            }

            @Override
            protected boolean pause(long millis) {
                pauses.add(millis);
                return true;   // no real waiting
            }

            void awaitScriptExhausted() throws InterruptedException {
                assertTrue(scriptExhausted.await(5, TimeUnit.SECONDS), "worker did not consume its script");
            }
        }

        @Test
        @DisplayName("D1: a transient failure does not kill the worker, which recovers on its own")
        void survivesTransientFailure() throws Exception {
            ScriptedWorker worker = new ScriptedWorker(List.of(
                    LibUsb.SUCCESS,
                    new LibUsbException("transient", LibUsb.ERROR_IO),
                    LibUsb.SUCCESS));

            worker.start();
            worker.awaitScriptExhausted();

            // Before this change the LibUsbException left run() and the thread died here.
            assertTrue(worker.isAlive(), "the worker must still be running after a transient failure");
            assertEquals(LibUsbAsyncEventsWorker.WorkerState.RUNNING, worker.getWorkerState());
            assertEquals(0, worker.getConsecutiveFailures(), "the failure counter resets on recovery");

            worker.abort();
            worker.join(5000);
            assertFalse(worker.isAlive());
        }

        @Test
        @DisplayName("D1b: a non-SUCCESS return code is treated exactly like a thrown failure")
        void survivesFailureReturnCode() throws Exception {
            ScriptedWorker worker = new ScriptedWorker(List.of(LibUsb.ERROR_TIMEOUT, LibUsb.SUCCESS));

            worker.start();
            worker.awaitScriptExhausted();

            assertTrue(worker.isAlive());
            assertEquals(LibUsbAsyncEventsWorker.WorkerState.RUNNING, worker.getWorkerState());

            worker.abort();
            worker.join(5000);
        }

        @Test
        @DisplayName("D2: repeated failures back off, and the backoff is capped")
        void backsOffAndCapsTheDelay() throws Exception {
            List<Object> alwaysFailing = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                alwaysFailing.add(new LibUsbException("still broken", LibUsb.ERROR_IO));
            }
            ScriptedWorker worker = new ScriptedWorker(alwaysFailing);

            worker.start();
            worker.awaitScriptExhausted();
            worker.abort();
            worker.join(5000);

            assertFalse(worker.pauses.isEmpty(), "a failing worker must back off rather than spin");
            assertEquals(LibUsbAsyncEventsWorker.RETRY_DELAY_MIN_MS, worker.pauses.get(0),
                    "the first retry waits the minimum delay");
            for (Long pause : worker.pauses) {
                assertTrue(pause <= LibUsbAsyncEventsWorker.RETRY_DELAY_MAX_MS,
                        "backoff must stay bounded, saw " + pause);
            }
            assertTrue(worker.pauses.contains(LibUsbAsyncEventsWorker.RETRY_DELAY_MAX_MS),
                    "backoff must actually grow up to the cap");
        }

        @Test
        @DisplayName("D3: abort stops the worker cleanly, even while it is retrying")
        void abortStopsTheWorker() throws Exception {
            List<Object> alwaysFailing = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                alwaysFailing.add(new LibUsbException("broken", LibUsb.ERROR_IO));
            }
            ScriptedWorker worker = new ScriptedWorker(alwaysFailing);

            worker.start();
            Thread.sleep(50);
            worker.abort();
            worker.join(5000);

            assertFalse(worker.isAlive(), "abort must terminate the worker");
            assertEquals(LibUsbAsyncEventsWorker.WorkerState.STOPPED, worker.getWorkerState());
        }
    }

    @Nested
    @DisplayName("active polling worker (the Windows detection path)")
    class ActivePollingWorker {

        /** Test double: the scan is scripted; nothing touches libusb. */
        class ScriptedPoller extends LibUsbActivePollingWorker {
            private final List<RuntimeException> failures;
            final AtomicInteger scans = new AtomicInteger();
            private final DeviceHotplugEventListener listener;

            ScriptedPoller(List<RuntimeException> failures, DeviceHotplugEventListener listener) {
                super(null, DeviceVersion.DEVICE_VERSION_2, listener);
                this.failures = failures;
                this.listener = listener;
            }

            @Override
            protected void scanOnce() {
                int index = scans.getAndIncrement();
                if (index < failures.size() && failures.get(index) != null) {
                    throw failures.get(index);
                }
                // A successful scan that finds nothing: the listener is simply not called.
            }
        }

        private DeviceHotplugEventListener countingListener(AtomicInteger plugged, AtomicInteger unplugged) {
            return new DeviceHotplugEventListener() {
                @Override
                public void onDevicePlugged(org.usb4java.Device device) {
                    plugged.incrementAndGet();
                }

                @Override
                public void onDeviceUnplugged(org.usb4java.Device device) {
                    unplugged.incrementAndGet();
                }
            };
        }

        @Test
        @DisplayName("D1c: a failing scan never escapes run(), so the scheduler keeps the task alive")
        void failingScanDoesNotCancelTheScheduledTask() throws Exception {
            // This is the real mechanism being guarded. ScheduledExecutorService.scheduleAtFixedRate
            // suppresses all subsequent executions as soon as one throws, so before this change a
            // single transient descriptor read error permanently disabled device detection on
            // Windows — with the backend still up and the UI still responding.
            AtomicInteger plugged = new AtomicInteger();
            ScriptedPoller poller = new ScriptedPoller(
                    List.of(new LibUsbException("descriptor read failed", LibUsb.ERROR_NOT_FOUND)),
                    countingListener(plugged, new AtomicInteger()));

            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
            try {
                ScheduledFuture<?> task = executor.scheduleAtFixedRate(poller, 0, 10, TimeUnit.MILLISECONDS);

                Thread.sleep(300);

                assertFalse(task.isDone(), "the scheduled task must still be alive after a failing scan");
                assertTrue(poller.scans.get() > 5,
                        "scanning must have continued, saw only " + poller.scans.get() + " scan(s)");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("D2b: consecutive failures are counted and the counter resets on recovery")
        void tracksConsecutiveFailures() {
            AtomicInteger plugged = new AtomicInteger();
            List<RuntimeException> script = new ArrayList<>();
            script.add(new LibUsbException("boom", LibUsb.ERROR_IO));
            script.add(new LibUsbException("boom", LibUsb.ERROR_IO));
            script.add(null);   // recovery
            ScriptedPoller poller = new ScriptedPoller(script, countingListener(plugged, new AtomicInteger()));

            poller.run();
            assertEquals(1, poller.getConsecutiveFailures());
            poller.run();
            assertEquals(2, poller.getConsecutiveFailures());
            poller.run();
            assertEquals(0, poller.getConsecutiveFailures(), "a successful scan clears the counter");
        }

        @Test
        @DisplayName("D7: repeated scans of a stable device do not fire duplicate plug events")
        void doesNotDuplicateDeviceEvents() {
            AtomicInteger plugged = new AtomicInteger();
            AtomicInteger unplugged = new AtomicInteger();
            ScriptedPoller poller = new ScriptedPoller(List.of(), countingListener(plugged, unplugged));

            for (int i = 0; i < 10; i++) {
                poller.run();
            }

            // The scripted scan finds nothing, so neither event fires. What this pins is that the
            // run() wrapper itself introduces no spurious event on repetition.
            assertEquals(0, plugged.get());
            assertEquals(0, unplugged.get());
        }
    }
}

