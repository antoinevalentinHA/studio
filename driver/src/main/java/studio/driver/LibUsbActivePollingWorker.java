/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

import org.usb4java.*;
import studio.driver.event.DeviceHotplugEventListener;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LibUsbActivePollingWorker implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(LibUsbActivePollingWorker.class.getName());

    /** Only log every Nth consecutive failure past the first. */
    static final int FAILURE_LOG_INTERVAL = 12;

    private final Context context;
    private final DeviceVersion deviceVersion;
    private final DeviceHotplugEventListener listener;
    private Device device = null;
    private int consecutiveFailures = 0;

    public LibUsbActivePollingWorker(Context context, DeviceVersion deviceVersion, DeviceHotplugEventListener listener) {
        this.context = context;
        this.deviceVersion = deviceVersion;
        this.listener = listener;
    }

    /**
     * Runs one scan.
     *
     * <p>This is scheduled with {@code ScheduledExecutorService.scheduleAtFixedRate}, whose contract
     * is unforgiving: <em>if any execution of the task encounters an exception, subsequent executions
     * are suppressed</em>. The scan below reads a descriptor for every USB device on the machine, and
     * {@code getDeviceDescriptor} legitimately fails with a transient error when a device disappears
     * between the list snapshot and the read — which is exactly what happens while devices are being
     * plugged and unplugged. Letting that propagate cancelled the polling task permanently: the
     * backend stayed up and served HTTP, but no device was ever detected again until STUdio was
     * restarted, with nothing in the UI to explain it.
     *
     * <p>On Windows libusb reports no hotplug capability, so this poller — not the hotplug callback —
     * is the whole of device detection. Nothing may escape this method.
     */
    @Override
    public void run() {
        try {
            scanOnce();
            if (consecutiveFailures > 0) {
                LOGGER.info("Active polling recovered after " + consecutiveFailures + " consecutive failure(s)");
                consecutiveFailures = 0;
            }
        } catch (Exception e) {
            int failures = ++consecutiveFailures;
            // Throttled: a permanently failing scan must not fill the log file every POLL_DELAY.
            if (failures == 1 || failures % FAILURE_LOG_INTERVAL == 0) {
                LOGGER.log(Level.WARNING, "Active polling scan failed (consecutive failures: " + failures
                        + "); detection continues on the next scan", e);
            }
        }
    }

    /** Observable so a degraded poller can be diagnosed without reading the logs. */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /** Seam: the actual scan, overridden in tests to inject failures without native libusb. */
    protected void scanOnce() {
        // List available devices
        DeviceList devices = new DeviceList();
        int result = LibUsb.getDeviceList(this.context, devices);
        if (result < 0) {
            throw new LibUsbException("Unable to get libusb device list", result);
        }
        try {
            // Iterate over all devices and scan for the right one
            Device found = null;
            for (Device d: devices) {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                result = LibUsb.getDeviceDescriptor(d, descriptor);
                if (result != LibUsb.SUCCESS) {
                    throw new LibUsbException("Unable to read libusb device descriptor", result);
                }
                if (
                        (deviceVersion == DeviceVersion.DEVICE_VERSION_1 || deviceVersion == DeviceVersion.DEVICE_VERSION_ANY)
                        && descriptor.idVendor() == LibUsbDetectionHelper.VENDOR_ID_FW1 && descriptor.idProduct() == LibUsbDetectionHelper.PRODUCT_ID_FW1
                ) {
                    found = d;
                }
                if (
                        (deviceVersion == DeviceVersion.DEVICE_VERSION_2 || deviceVersion == DeviceVersion.DEVICE_VERSION_ANY)
                        && (
                                (descriptor.idVendor() == LibUsbDetectionHelper.VENDOR_ID_FW2 && descriptor.idProduct() == LibUsbDetectionHelper.PRODUCT_ID_FW2)
                                || (descriptor.idVendor() == LibUsbDetectionHelper.VENDOR_ID_V2 && descriptor.idProduct() == (short)(LibUsbDetectionHelper.PRODUCT_ID_V2 & 0xffff))
                        )
                ) {
                    found = d;
                }
            }
            // Fire plugged / unplugged events
            if (found != null && this.device == null) {
                LOGGER.info("Active polling found a new device. Firing event.");
                this.device = found;
                CompletableFuture.runAsync(() -> this.listener.onDevicePlugged(this.device));
            } else if (found == null && this.device != null) {
                LOGGER.info("Active polling lost the device. Firing event.");
                CompletableFuture.runAsync(() -> this.listener.onDeviceUnplugged(this.device));
                this.device = null;
            }
        } finally {
            // Ensure the allocated device list is freed
            LibUsb.freeDeviceList(devices, false);  // Do NOT unref devices
        }
    }
}
