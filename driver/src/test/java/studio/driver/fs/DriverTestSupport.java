/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.usb4java.Device;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Test-only helpers to exercise {@link FsStoryTellerAsyncDriver} against a plain directory.
 *
 * <p>Why this exists: the driver's only constructor calls
 * {@code LibUsbDetectionHelper.initializeLibUsb(...)}, which initialises a native libusb context,
 * starts a worker thread and registers a JVM shutdown hook. None of that is available or desirable
 * in a unit test, and none of it is needed by the methods characterised here — they read
 * {@code partitionMountPoint} and nothing else. We therefore allocate the instance without running
 * its constructor and set the one field that matters.
 *
 * <p>This is a deliberate consequence of the driver having no seam between USB detection and
 * filesystem access. The intent is <em>not</em> to make this permanent: a future refactor extracting
 * a partition abstraction taking a {@link Path} would let these tests drop the reflection entirely.
 * Until then, reflection is the only way to characterise the existing behaviour without changing it.
 */
final class DriverTestSupport {

    private static final Objenesis OBJENESIS = new ObjenesisStd();

    private DriverTestSupport() {
    }

    /** A driver instance whose {@code partitionMountPoint} points at {@code partitionRoot}. */
    static FsStoryTellerAsyncDriver driverMountedOn(Path partitionRoot) {
        FsStoryTellerAsyncDriver driver = OBJENESIS.newInstance(FsStoryTellerAsyncDriver.class);
        setField(driver, "partitionMountPoint", partitionRoot.toString());
        return driver;
    }

    /**
     * Same as {@link #driverMountedOn(Path)} plus a non-null {@code device}, which several public
     * methods require as a "device is plugged" precondition. The Device instance is inert: usb4java's
     * Device holds a single {@code long} pointer, declares no finaliser, and is only ever null-checked
     * by the code under test.
     */
    static FsStoryTellerAsyncDriver pluggedDriverMountedOn(Path partitionRoot) {
        FsStoryTellerAsyncDriver driver = driverMountedOn(partitionRoot);
        setField(driver, "device", newInertDevice());
        return driver;
    }

    private static Device newInertDevice() {
        try {
            Constructor<Device> ctor = Device.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build an inert usb4java Device", e);
        }
    }

    static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set field " + name, e);
        }
    }

    /** Invokes a private no-arg method and unwraps the underlying failure, if any. */
    static Object invokePrivate(Object target, String name) throws Throwable {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        try {
            return method.invoke(target);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Root cause of a failed CompletableFuture / wrapped reflective call. */
    static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
