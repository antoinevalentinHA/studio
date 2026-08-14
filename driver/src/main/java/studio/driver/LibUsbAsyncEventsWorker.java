/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

import org.usb4java.Context;
import org.usb4java.LibUsb;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pumps libusb asynchronous events for as long as the application runs.
 *
 * <p>This thread is the only thing delivering hotplug callbacks on platforms where libusb supports
 * them. It previously threw out of {@code run()} on the first non-SUCCESS return, which killed the
 * thread: the backend stayed up, kept serving HTTP, and silently never reported another device
 * until STUdio was restarted. A single transient libusb error was enough.
 *
 * <p>It now treats a failure as transient by default: it backs off, keeps polling, and recovers on
 * its own if the condition clears. It never gives up on its own, because giving up is
 * indistinguishable — from the user's point of view — from the bug this replaces. A genuinely
 * unrecoverable context simply costs one failed call per {@link #RETRY_DELAY_MAX_MS}, which is
 * negligible, and the failure is visible in both the logs and {@link #getWorkerState()}.
 */
public class LibUsbAsyncEventsWorker extends Thread {

    private static final Logger LOGGER = Logger.getLogger(LibUsbAsyncEventsWorker.class.getName());

    /** Timeout passed to libusb; unchanged from the original implementation. */
    static final long HANDLE_EVENTS_TIMEOUT = 250000L;

    static final long RETRY_DELAY_MIN_MS = 200L;
    static final long RETRY_DELAY_MAX_MS = 5000L;

    /** Only log every Nth consecutive failure past the first, to keep a stuck context from flooding. */
    static final int FAILURE_LOG_INTERVAL = 20;

    public enum WorkerState {
        /** Polling normally. */
        RUNNING,
        /** At least one consecutive failure; backing off and retrying. */
        RETRYING,
        /** Stopped on request. */
        STOPPED
    }

    private volatile boolean abort;
    private volatile WorkerState state = WorkerState.RUNNING;
    private volatile int consecutiveFailures;

    private final Context context;

    public LibUsbAsyncEventsWorker(Context context) {
        this.context = context;
        setName("libusb-async-events");
        setDaemon(true);
    }

    public void abort() {
        this.abort = true;
        // Cut short any backoff in progress so shutdown is not delayed by a pending retry.
        interrupt();
    }

    /** Observable so that a stuck worker can be diagnosed without reading the logs. */
    public WorkerState getWorkerState() {
        return state;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    @Override
    public void run() {
        LOGGER.info("libusb async event worker started");
        long retryDelay = RETRY_DELAY_MIN_MS;

        while (!abort) {
            boolean succeeded;
            try {
                succeeded = handleEvents() == LibUsb.SUCCESS;
            } catch (Exception e) {
                // usb4java can throw rather than return a code; both mean the same thing here.
                succeeded = false;
                recordFailure(e);
            }

            if (succeeded) {
                if (consecutiveFailures > 0) {
                    LOGGER.info("libusb async event worker recovered after " + consecutiveFailures + " consecutive failure(s)");
                }
                consecutiveFailures = 0;
                retryDelay = RETRY_DELAY_MIN_MS;
                state = WorkerState.RUNNING;
                continue;
            }

            if (consecutiveFailures == 0) {
                // Failure signalled by a return code rather than an exception.
                recordFailure(null);
            }
            state = WorkerState.RETRYING;

            if (abort) {
                break;
            }
            if (!pause(retryDelay)) {
                break;
            }
            retryDelay = Math.min(retryDelay * 2, RETRY_DELAY_MAX_MS);
        }

        state = WorkerState.STOPPED;
        LOGGER.info("libusb async event worker stopped");
    }

    private void recordFailure(Exception cause) {
        int failures = ++consecutiveFailures;
        // First failure is always logged; after that, throttle so a permanently broken context
        // cannot fill the log file.
        if (failures == 1 || failures % FAILURE_LOG_INTERVAL == 0) {
            String message = "libusb event handling failed (consecutive failures: " + failures
                    + "); retrying, device detection is degraded until it recovers";
            if (cause != null) {
                LOGGER.log(Level.WARNING, message, cause);
            } else {
                LOGGER.warning(message);
            }
        }
    }

    /** Seam: overridden in tests so the retry behaviour can be exercised without native libusb. */
    protected int handleEvents() {
        return LibUsb.handleEventsTimeout(context, HANDLE_EVENTS_TIMEOUT);
    }

    /** Seam: returns false when the wait was interrupted, i.e. the worker should stop. */
    protected boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

