/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Installs a pack index out of band: write {@code .pi.new}, ask for it to be synchronised, then move
 * it onto {@code .pi} in one operation.
 *
 * <p>The temporary is created exclusively — a pre-existing one is refused rather than taken over —
 * and removed again on every controlled failure. The installation is a single
 * {@code Files.move(ATOMIC_MOVE)}, so the index is never written through in place. If the move is
 * not supported the operation fails: there is deliberately no fallback to the copy this replaced.
 *
 * <h2>What this does not claim</h2>
 *
 * <p><strong>Atomic here means atomic to the filesystem, not resistant to power loss.</strong> FAT32
 * has no journal, and what a power cut does to a directory entry mid-update is outside anything this
 * repository can establish. {@code force} is a <em>request</em> that the operating system push the
 * file to the card; the card's controller and its translation layer may acknowledge before the bytes
 * reach the NAND, and nothing here can observe that.
 *
 * <p>Two behaviours recorded by {@code WritePathCharacterizationTest} and, on FAT32, by
 * {@code Fat32WritePathCharacterizationTest} are unchanged: the hidden attribute of {@code .pi} is
 * still lost, because attributes follow the source of the move exactly as they followed the source
 * of the copy; and nothing about the {@code .content} folder, the retry behaviour or the delete
 * ordering is touched here.
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
                    + temporary + ". It was not created by this operation, so the index write was refused"
                    + " rather than taking it over: overwriting or removing it would risk an index whose"
                    + " origin is not known, and the pack UUIDs it may hold exist nowhere else on the card."
                    + " Nothing was overwritten or removed. STUdio has no operation to resolve this state"
                    + " yet.", preexisting);
        }

        // From here the temporary is ours, and every path out of this method removes it.
        try {
            LOGGER.finest("Writing pack index to temporary file: " + temporary);
            writeIndexTo(temporary, packUUIDs);
            LOGGER.finest("Requesting synchronisation of the temporary pack index file");
            syncTemporary(temporary);
            LOGGER.finest("Installing the pack index file");
            installIndex(temporary, index);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            // No fallback. Copying the temporary over the index is exactly the behaviour being
            // removed: it would reopen the window during which the device has no valid index, and it
            // would do so silently, on whichever filesystem happens not to support the move.
            IOException refusal = new IOException("The device partition does not support installing the"
                    + " pack index by an atomic move. Refusing to fall back to a non-atomic copy, which"
                    + " would leave " + index + " absent or partial for an instant.", notAtomic);
            LOGGER.warning("Refusing to write the pack index: " + index
                    + " cannot be replaced atomically on this filesystem");
            removeOurTemporary(temporary, refusal);
            throw refusal;
        } catch (IOException | RuntimeException | Error failure) {
            removeOurTemporary(temporary, failure);
            throw failure;
        }

        // The move consumed the temporary. There is nothing left to clean up on this path.
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

    /**
     * Asks the operating system to push the temporary to the storage device.
     *
     * <p>Closing the stream above hands the bytes to the OS; it does not ask the OS to hand them to
     * the card. Installing an index whose content is still only in a cache would make the move atomic
     * with respect to the directory and meaningless with respect to the data.
     *
     * <p>This is a <strong>request</strong>, not a receipt. {@code force(true)} covers the file's data
     * and its metadata as far as the OS is concerned; the card's controller and its translation layer
     * are free to acknowledge before anything reaches the NAND, and nothing here can observe that.
     */
    void syncTemporary(Path temporary) throws IOException {
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * Installs the replacement by a single move, so the index is never written through in place.
     *
     * <p>{@code Files.copy(REPLACE_EXISTING)}, which this replaces, removed the target and then wrote
     * it: every rewrite opened an instant where {@code .pi} was absent, empty or partial. The move
     * re-points the directory entry at a file that is already complete.
     *
     * <p>{@code REPLACE_EXISTING} is passed alongside {@code ATOMIC_MOVE} because that is the
     * combination the existing characterizations measured, on NTFS and on a FAT32 volume alike:
     * an existing target is replaced, a hidden target does not refuse the move, and the attributes
     * follow the source — which is why {@code .pi} keeps losing its hidden attribute exactly as it
     * did before. Atomic here means atomic to the filesystem; it is not a claim about power loss.
     */
    void installIndex(Path temporary, Path index) throws IOException {
        Files.move(temporary, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    void deleteTemporary(Path temporary) throws IOException {
        Files.delete(temporary);
    }
}
