/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeing the device on the USB bus and being able to read its filesystem are two separate events.
 *
 * <p>On Windows the gap between them is however long the OS takes to assign a drive letter and mount
 * the volume. The previous code allowed ten one-second attempts and then threw into a
 * {@code CompletableFuture} that only logged, so a slow mount produced no device and — because the
 * USB arrival event fires once — no further attempt until the device was physically re-plugged.
 * That is the "Windows sees the Lunii, STUdio does not" symptom.
 *
 * <p>No device, no drive letter and no real waiting are involved here: mount points, the marker
 * probe and the sleep are all injected.
 */
class DevicePartitionLocatorTest {

    /** Records how often it was asked to wait, without ever actually waiting. */
    static class RecordingSleeper implements DevicePartitionLocator.Sleeper {
        final List<Long> waits = new ArrayList<>();
        boolean interrupt = false;

        @Override
        public boolean sleep(long millis) {
            waits.add(millis);
            return !interrupt;
        }
    }

    private DevicePartitionLocator locator(Supplier<List<String>> mountPoints,
                                          java.util.function.Predicate<String> marker,
                                          RecordingSleeper sleeper,
                                          int maxAttempts) {
        return new DevicePartitionLocator(mountPoints, marker, sleeper, 1000L, maxAttempts);
    }

    @Test
    @DisplayName("finds the partition immediately when the volume is already mounted")
    void findsPartitionOnFirstAttempt() {
        RecordingSleeper sleeper = new RecordingSleeper();
        DevicePartitionLocator locator = locator(
                () -> Arrays.asList("C:\\", "D:\\"),
                path -> "D:\\".equals(path),
                sleeper, 120);

        Optional<String> found = locator.awaitPartition(() -> true);

        assertEquals(Optional.of("D:\\"), found);
        assertTrue(sleeper.waits.isEmpty(), "no waiting is needed when the volume is already there");
    }

    @Test
    @DisplayName("D4: keeps polling and succeeds when the volume is mounted late")
    void succeedsWhenVolumeAppearsLate() {
        // Twenty seconds is well past the ten attempts the previous implementation allowed, and is
        // an entirely plausible delay on a slow hub.
        AtomicInteger tick = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();
        DevicePartitionLocator locator = locator(
                () -> tick.incrementAndGet() < 20 ? Arrays.asList("C:\\") : Arrays.asList("C:\\", "D:\\"),
                path -> "D:\\".equals(path),
                sleeper, 120);

        Optional<String> found = locator.awaitPartition(() -> true);

        assertEquals(Optional.of("D:\\"), found);
        assertEquals(19, sleeper.waits.size(), "it should have waited between each attempt");
    }

    @Test
    @DisplayName("D5: an unplug cancels the search at once and yields no mount point")
    void unplugCancelsTheSearch() {
        AtomicBoolean plugged = new AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();
        DevicePartitionLocator locator = locator(
                () -> {
                    if (attempts.incrementAndGet() == 3) {
                        plugged.set(false);
                    }
                    return Arrays.asList("C:\\");
                },
                path -> false,
                sleeper, 120);

        Optional<String> found = locator.awaitPartition(plugged::get);

        assertFalse(found.isPresent(), "an unplugged device must not resolve to a mount point");
        assertEquals(3, attempts.get(), "the search must stop as soon as the device is gone");
    }

    @Test
    @DisplayName("D6: a fresh search after a previous failure starts clean and can succeed")
    void retriesFromScratchAfterAPreviousFailure() {
        RecordingSleeper sleeper = new RecordingSleeper();
        DevicePartitionLocator locator = locator(
                () -> Arrays.asList("C:\\"),
                path -> false,
                sleeper, 3);

        Optional<String> firstAttempt = locator.awaitPartition(() -> true);
        assertFalse(firstAttempt.isPresent(), "precondition: the first search gives up");

        // Same locator, device plugged back in, volume present this time.
        DevicePartitionLocator afterReplug = locator(
                () -> Arrays.asList("C:\\", "E:\\"),
                path -> "E:\\".equals(path),
                new RecordingSleeper(), 3);

        assertEquals(Optional.of("E:\\"), afterReplug.awaitPartition(() -> true));
    }

    @Test
    @DisplayName("gives up after a bounded number of attempts rather than polling forever")
    void givesUpAfterTheDeadline() {
        RecordingSleeper sleeper = new RecordingSleeper();
        AtomicInteger scans = new AtomicInteger();
        DevicePartitionLocator locator = locator(
                () -> {
                    scans.incrementAndGet();
                    return Arrays.asList("C:\\");
                },
                path -> false,
                sleeper, 5);

        Optional<String> found = locator.awaitPartition(() -> true);

        assertFalse(found.isPresent());
        assertEquals(5, scans.get(), "exactly maxAttempts scans, no more");
    }

    @Test
    @DisplayName("an interrupted wait aborts the search instead of spinning")
    void interruptedWaitAbortsTheSearch() {
        RecordingSleeper sleeper = new RecordingSleeper();
        sleeper.interrupt = true;
        AtomicInteger scans = new AtomicInteger();
        DevicePartitionLocator locator = locator(
                () -> {
                    scans.incrementAndGet();
                    return Arrays.asList("C:\\");
                },
                path -> false,
                sleeper, 120);

        assertFalse(locator.awaitPartition(() -> true).isPresent());
        assertEquals(1, scans.get());
    }

    @Test
    @DisplayName("a failure while listing volumes is transient, not terminal")
    void survivesAFailureToListMountPoints() {
        // Enumerating drives can fail transiently on Windows — a disconnected network drive, an
        // empty card reader. That must not end the search.
        AtomicInteger calls = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();
        DevicePartitionLocator locator = locator(
                () -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new RuntimeException("failed to list volumes");
                    }
                    return Arrays.asList("D:\\");
                },
                path -> "D:\\".equals(path),
                sleeper, 120);

        assertEquals(Optional.of("D:\\"), locator.awaitPartition(() -> true));
    }

    @Test
    @DisplayName("a failure while probing one mount point does not hide another")
    void survivesAFailureToProbeOneMountPoint() {
        RecordingSleeper sleeper = new RecordingSleeper();
        DevicePartitionLocator locator = locator(
                () -> Arrays.asList("A:\\", "D:\\"),
                path -> {
                    if ("A:\\".equals(path)) {
                        throw new RuntimeException("device not ready");
                    }
                    return "D:\\".equals(path);
                },
                sleeper, 120);

        assertEquals(Optional.of("D:\\"), locator.awaitPartition(() -> true));
    }
}
