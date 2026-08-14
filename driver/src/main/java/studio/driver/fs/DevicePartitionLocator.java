/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds the drive on which a plugged story teller has been mounted.
 *
 * <p>Seeing the device on the USB bus and being able to read its filesystem are two distinct events,
 * and on Windows they can be seconds apart: the OS has to enumerate the mass-storage interface,
 * assign a drive letter and mount the volume. The previous implementation gave the OS ten one-second
 * attempts and then threw. The throw was swallowed by the {@code CompletableFuture} that invoked the
 * hotplug listener, so a slow mount produced no device, no error in the UI, and — because the USB
 * arrival event only fires once — no further attempt for as long as the device stayed plugged in.
 *
 * <p>This class polls until the partition shows up, until the caller says the device is gone, or
 * until a bounded overall deadline. Every dependency is injected so the behaviour can be tested
 * without a device, a drive letter or a real clock.
 */
public class DevicePartitionLocator {

    private static final Logger LOGGER = Logger.getLogger(DevicePartitionLocator.class.getName());

    static final long DEFAULT_POLL_DELAY_MS = 1000L;
    /** 120 attempts at one second: generous for a slow hub, still bounded. */
    static final int DEFAULT_MAX_ATTEMPTS = 120;
    /** Report progress every Nth attempt rather than on each one. */
    static final int PROGRESS_LOG_INTERVAL = 5;

    /** Injectable so tests do not have to actually wait. Returns false if the wait was interrupted. */
    public interface Sleeper {
        boolean sleep(long millis);
    }

    private final Supplier<List<String>> mountPointsSupplier;
    private final Predicate<String> partitionMarkerPresent;
    private final Sleeper sleeper;
    private final long pollDelayMillis;
    private final int maxAttempts;

    public DevicePartitionLocator(Supplier<List<String>> mountPointsSupplier,
                                  Predicate<String> partitionMarkerPresent,
                                  Sleeper sleeper,
                                  long pollDelayMillis,
                                  int maxAttempts) {
        this.mountPointsSupplier = mountPointsSupplier;
        this.partitionMarkerPresent = partitionMarkerPresent;
        this.sleeper = sleeper;
        this.pollDelayMillis = pollDelayMillis;
        this.maxAttempts = maxAttempts;
    }

    /** Production wiring: real mount points, real marker file, real sleep. */
    public static DevicePartitionLocator forDeviceMetadataFile(String metadataFileName) {
        return new DevicePartitionLocator(
                DeviceUtils::listMountPoints,
                path -> new File(path, metadataFileName).exists(),
                millis -> {
                    try {
                        Thread.sleep(millis);
                        return true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                },
                DEFAULT_POLL_DELAY_MS,
                DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Polls for the device partition.
     *
     * @param deviceStillPlugged consulted before every attempt; when it turns false the search is
     *                           abandoned at once, so an unplug cannot leave a search running or a
     *                           stale mount point behind.
     * @return the mount point, or empty if the device went away or the deadline expired.
     */
    public Optional<String> awaitPartition(BooleanSupplier deviceStillPlugged) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!deviceStillPlugged.getAsBoolean()) {
                LOGGER.info("Device partition search cancelled: device is no longer plugged");
                return Optional.empty();
            }

            Optional<String> found = scanOnce();
            if (found.isPresent()) {
                LOGGER.info("Device partition located on " + found.get() + " after " + attempt + " attempt(s)");
                return found;
            }

            if (attempt == 1 || attempt % PROGRESS_LOG_INTERVAL == 0) {
                LOGGER.info("Device is plugged but its partition is not mounted yet; still waiting (attempt "
                        + attempt + "/" + maxAttempts + ")");
            }

            if (!sleeper.sleep(pollDelayMillis)) {
                LOGGER.info("Device partition search interrupted");
                return Optional.empty();
            }
        }

        LOGGER.warning("Gave up locating the device partition after " + maxAttempts
                + " attempt(s). Unplug and plug the device back in to retry.");
        return Optional.empty();
    }

    private Optional<String> scanOnce() {
        List<String> mountPoints;
        try {
            mountPoints = mountPointsSupplier.get();
        } catch (Exception e) {
            // Listing volumes can fail transiently (a disconnected network drive, an empty card
            // reader). That is not a reason to abandon the search.
            LOGGER.log(Level.WARNING, "Failed to list mount points; will retry", e);
            return Optional.empty();
        }

        for (String mountPoint : mountPoints) {
            try {
                if (partitionMarkerPresent.test(mountPoint)) {
                    return Optional.of(mountPoint);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to probe mount point " + mountPoint, e);
            }
        }
        return Optional.empty();
    }
}
