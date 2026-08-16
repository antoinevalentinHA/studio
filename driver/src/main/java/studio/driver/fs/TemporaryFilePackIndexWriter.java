/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The index-replacement strategy the driver has always used: write {@code .pi.new}, copy it over
 * {@code .pi}, delete the temporary file.
 *
 * <p>The temporary is now handled properly — created exclusively, and removed again whether the
 * write succeeds or fails — but <strong>the installation itself is untouched</strong>. What it does
 * is characterised by {@code WritePathCharacterizationTest} and, on FAT32, by
 * {@code Fat32WritePathCharacterizationTest}, including the parts that are plainly undesirable:
 *
 * <ul>
 *   <li>the copy is <strong>not atomic</strong>: {@code REPLACE_EXISTING} removes the target before
 *       writing it, so there is an instant where {@code .pi} is absent or partial;</li>
 *   <li>the hidden attribute of {@code .pi} is <strong>lost</strong>, on NTFS and on FAT32 alike,
 *       because the attribute is inherited from the copy source;</li>
 *   <li>nothing is flushed to the card before the operation reports success.</li>
 * </ul>
 *
 * <p>Tidying the temporary away is housekeeping; it is not durability, and it does not narrow the
 * window during which the device has no valid index. The class name states the strategy rather than
 * claiming a property, which leaves room for an implementation that installs the index differently
 * once the invariants have been decided.
 */
class TemporaryFilePackIndexWriter implements PackIndexWriter {

    private static final Logger LOGGER = Logger.getLogger(TemporaryFilePackIndexWriter.class.getName());

    /** The suffix the temporary has always carried. Unchanged: the firmware may have seen it. */
    private static final String TEMPORARY_SUFFIX = ".new";

    @Override
    public void write(String packIndexFilePath, List<UUID> packUUIDs) throws IOException {
        Path index = Paths.get(packIndexFilePath);
        Path temporary = Paths.get(packIndexFilePath + TEMPORARY_SUFFIX);

        // Claim the temporary before writing anything. Creation is exclusive, so the answer to "is
        // this file ours?" is decided here, once, atomically — no exists() then create, which would
        // leave a window, and no guessing from age or content afterwards.
        try {
            LOGGER.finest("Creating temporary pack index file: " + temporary);
            createTemporary(temporary);
        } catch (FileAlreadyExistsException preexisting) {
            // Not ours. It could be the residue of an interrupted operation, of an older STUdio, of
            // another tool or of the firmware, and nothing here can tell which. Truncating it would
            // destroy an index we cannot rebuild — the pack UUIDs exist nowhere else on the card.
            LOGGER.warning("Refusing to write the pack index: a temporary file already exists at "
                    + temporary + ", and it was not created by this operation");
            throw new IOException("A pack index temporary file already exists on the device partition: "
                    + temporary + ". It was not created by this operation, so it is neither overwritten"
                    + " nor removed. Establish where it came from before removing it by hand.", preexisting);
        }

        // From here the temporary is ours, and every path out of this method removes it.
        try {
            LOGGER.finest("Writing pack index to temporary file: " + temporary);
            writeIndexTo(temporary, packUUIDs);
            LOGGER.finest("Replacing pack index file");
            installIndex(temporary, index);
        } catch (IOException | RuntimeException | Error failure) {
            removeOurTemporary(temporary, failure);
            throw failure;
        }

        LOGGER.finest("Deleting temporary pack index file");
        deleteTemporary(temporary);
    }

    /**
     * Removes the temporary this operation created, without letting a cleanup problem bury the
     * reason the write failed in the first place.
     */
    private void removeOurTemporary(Path temporary, Throwable primaryFailure) {
        try {
            LOGGER.finest("Removing the temporary pack index file after a failed write");
            deleteTemporary(temporary);
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            LOGGER.log(Level.WARNING, "Failed to remove the temporary pack index file " + temporary,
                    cleanupFailure);
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    // ---------------------------------------------------------------- the individual steps
    // Package-private and overridable so a test can make one of them fail while the rest of the
    // operation runs for real. Substituting the whole writer would short-circuit exactly the
    // ownership and cleanup logic that needs testing. Same seam idea as the driver's `handleEvents`,
    // `pause` and `scanOnce`.

    /** Exclusive: raises {@link FileAlreadyExistsException} rather than taking over an existing file. */
    void createTemporary(Path temporary) throws IOException {
        Files.createFile(temporary);
    }

    void writeIndexTo(Path temporary, List<UUID> packUUIDs) throws IOException {
        try (DataOutputStream packIndexDos = new DataOutputStream(Files.newOutputStream(temporary))) {
            for (UUID packUUID : packUUIDs) {
                packIndexDos.writeLong(packUUID.getMostSignificantBits());
                packIndexDos.writeLong(packUUID.getLeastSignificantBits());
            }
        }
    }

    /** Unchanged, and still not atomic: the target is removed before the new bytes are written. */
    void installIndex(Path temporary, Path index) throws IOException {
        Files.copy(temporary, index, StandardCopyOption.REPLACE_EXISTING);
    }

    void deleteTemporary(Path temporary) throws IOException {
        Files.delete(temporary);
    }
}
