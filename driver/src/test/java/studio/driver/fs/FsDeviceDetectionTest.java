/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import org.usb4java.Device;
import studio.driver.event.DeviceHotplugEventListener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The detection half of the FS driver: what happens between "USB says a device arrived" and
 * "STUdio publishes a usable device".
 *
 * <p>The driver is allocated without running its constructor, which would initialise libusb, start
 * threads and register a JVM shutdown hook. Only the detection fields are populated; nothing in the
 * transfer or write path is touched by these tests.
 */
class FsDeviceDetectionTest {

    private FsStoryTellerAsyncDriver driver;
    private List<String> pluggedEvents;
    private List<String> unpluggedEvents;

    private static Device newDevice() {
        try {
            Constructor<Device> ctor = Device.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    private static Object get(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + field, e);
        }
    }

    /** A locator whose answer the test controls, with no filesystem involved. */
    private DevicePartitionLocator locatorReturning(String mountPoint, AtomicBoolean observedCancellation) {
        return new DevicePartitionLocator(
                () -> mountPoint == null ? List.of() : Arrays.asList(mountPoint),
                path -> path.equals(mountPoint),
                millis -> true,
                0L,
                1) {
            @Override
            public Optional<String> awaitPartition(java.util.function.BooleanSupplier deviceStillPlugged) {
                if (observedCancellation != null && !deviceStillPlugged.getAsBoolean()) {
                    observedCancellation.set(true);
                    return Optional.empty();
                }
                return Optional.ofNullable(mountPoint);
            }
        };
    }

    private void installLocator(DevicePartitionLocator locator) {
        set(driver, "partitionLocator", locator);
    }

    @BeforeEach
    void buildDriverWithoutTouchingLibUsb() {
        driver = new ObjenesisStd().newInstance(FsStoryTellerAsyncDriver.class);
        pluggedEvents = new ArrayList<>();
        unpluggedEvents = new ArrayList<>();
        List<DeviceHotplugEventListener> listeners = new ArrayList<>();
        listeners.add(new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
                pluggedEvents.add("plugged");
            }

            @Override
            public void onDeviceUnplugged(Device device) {
                unpluggedEvents.add("unplugged");
            }
        });
        set(driver, "listeners", listeners);
    }

    @Test
    @DisplayName("publishes the device once the partition is located")
    void publishesDeviceWhenPartitionIsFound() {
        installLocator(locatorReturning("D:\\", null));
        Device device = newDevice();

        driver.handleDevicePlugged(device);

        assertEquals("D:\\", driver.getPartitionMountPoint());
        assertSame(device, get(driver, "device"));
        assertEquals(1, pluggedEvents.size());
    }

    @Test
    @DisplayName("D8: a partition that never appears leaves no device and, above all, throws nothing")
    void missingPartitionIsNotAnException() {
        // The caller is a CompletableFuture that only logs, so throwing here produced a device that
        // was invisible to STUdio with nothing shown to the user. Failing quietly and leaving the
        // state clean is the contract; the listener is simply never notified.
        installLocator(locatorReturning(null, null));
        Device device = newDevice();

        assertDoesNotThrow(() -> driver.handleDevicePlugged(device));

        assertNull(driver.getPartitionMountPoint());
        assertNull(get(driver, "device"));
        assertEquals(0, pluggedEvents.size());
        assertNull(get(driver, "awaitingPartitionFor"), "no search must be left pending");
    }

    @Test
    @DisplayName("D5: an unplug during the search cancels it and leaves no stale mount point")
    void unplugDuringSearchLeavesNoStaleMountPoint() {
        Device device = newDevice();
        AtomicBoolean cancellationObserved = new AtomicBoolean(false);

        // A locator that unplugs the device midway, then consults the cancellation predicate.
        installLocator(new DevicePartitionLocator(List::of, path -> false, millis -> true, 0L, 1) {
            @Override
            public Optional<String> awaitPartition(java.util.function.BooleanSupplier deviceStillPlugged) {
                driver.handleDeviceUnplugged(device);
                if (!deviceStillPlugged.getAsBoolean()) {
                    cancellationObserved.set(true);
                }
                return Optional.of("D:\\");   // even if a mount point is found, it must be discarded
            }
        });

        driver.handleDevicePlugged(device);

        assertEquals(true, cancellationObserved.get(), "the search must observe the unplug");
        assertNull(driver.getPartitionMountPoint(), "a mount point found after an unplug must be discarded");
        assertNull(get(driver, "device"));
        assertEquals(0, pluggedEvents.size(), "no device may be published after an unplug");
        assertEquals(1, unpluggedEvents.size());
    }

    @Test
    @DisplayName("D6: a replug after a failed detection starts a clean, successful sequence")
    void replugAfterFailureSucceeds() {
        Device first = newDevice();
        installLocator(locatorReturning(null, null));
        driver.handleDevicePlugged(first);
        assertEquals(0, pluggedEvents.size(), "precondition: first detection failed");

        // Device unplugged and plugged back in: the volume is there this time.
        driver.handleDeviceUnplugged(first);
        Device second = newDevice();
        installLocator(locatorReturning("E:\\", null));

        driver.handleDevicePlugged(second);

        assertEquals("E:\\", driver.getPartitionMountPoint());
        assertEquals(1, pluggedEvents.size(), "the replug must produce exactly one plug event");
    }

    @Test
    @DisplayName("unplugging clears the mount point so no stale drive letter can be reused")
    void unplugClearsTheMountPoint() {
        installLocator(locatorReturning("D:\\", null));
        Device device = newDevice();
        driver.handleDevicePlugged(device);
        assertEquals("D:\\", driver.getPartitionMountPoint());

        driver.handleDeviceUnplugged(device);

        assertNull(driver.getPartitionMountPoint());
        assertNull(get(driver, "device"));
        assertEquals(1, unpluggedEvents.size());
    }

    @Test
    @DisplayName("D7: two plug events for the same device publish it once per event, never inconsistently")
    void repeatedPlugEventsKeepStateConsistent() {
        installLocator(locatorReturning("D:\\", null));
        Device device = newDevice();

        driver.handleDevicePlugged(device);
        driver.handleDevicePlugged(device);

        assertEquals("D:\\", driver.getPartitionMountPoint());
        assertSame(device, get(driver, "device"));
        assertNull(get(driver, "awaitingPartitionFor"), "no search may be left dangling");
        assertEquals(2, pluggedEvents.size());
    }

    @Test
    @DisplayName("an unexpected failure inside detection is contained, not propagated")
    void unexpectedFailureIsContained() {
        installLocator(new DevicePartitionLocator(List::of, path -> false, millis -> true, 0L, 1) {
            @Override
            public Optional<String> awaitPartition(java.util.function.BooleanSupplier deviceStillPlugged) {
                throw new IllegalStateException("something unexpected");
            }
        });

        assertDoesNotThrow(() -> driver.handleDevicePlugged(newDevice()));

        assertNull(driver.getPartitionMountPoint());
        assertNull(get(driver, "awaitingPartitionFor"));
    }
}
