/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A conservative upper bound on the logical bytes an upload adds.
 *
 * <p>The preflight used to compare free space against the sum of the source file sizes, which is not
 * what a transfer puts on the card. Three things were missing: the cipher pads short assets, the
 * boot file is generated on the device and appears in no source tree, and the index is rewritten
 * through a temporary file that coexists with the one it replaces. All three make the real cost
 * larger than the old estimate, so the check could accept an upload that does not fit — and an
 * upload that runs out of space part-way leaves an orphan content folder that nothing cleans up.
 *
 * <p>It is a <strong>bound, not an exact figure</strong>, and deliberately so: the preflight runs
 * before the device metadata is read, so the firmware family is unknown here and the worst of the
 * two is assumed for the cipher padding and for the boot file. On a V2 device the result therefore
 * over-counts, by at most fifteen bytes per ciphered asset and thirty-two for the boot file. Erring
 * high refuses a transfer that would have fitted; erring low starts one that cannot finish.
 *
 * <p>This computes only the sum. It reads no device state, decides nothing, and writes nothing; the
 * driver keeps the decision.
 *
 * <h2>A bound on logical bytes, not on allocated space</h2>
 *
 * <p><strong>This is not an upper bound on what the transfer will occupy on a FAT32 card.</strong>
 * FAT32 allocates by cluster, and the cluster size is not reachable through the APIs in use —
 * {@code FileStore.getBlockSize()} reports the sector size, 512 bytes, where C6c measured a 4096-byte
 * cluster. That measurement also found 440 logical bytes occupying 32768 allocated bytes in a
 * synthetic pack, a ratio dominated by rounding on a tiny fixture and not one to generalise. Rather
 * than invent a multiplier, an assumed cluster or a write probe, this bounds the costs it can bound
 * and leaves the rest documented: directories, directory entries, cluster slack and filesystem
 * metadata are not included.
 *
 * <p>Being above the logical cost therefore says nothing about being above the allocated cost: the
 * result is a floor on the requirement, not a guarantee of success. The real protection against
 * running out of space mid-transfer is what happens when a copy fails, which is still unchanged.
 */
final class PackTransferSizeEstimator {

    /** Only the first block of an asset is ciphered; the rest is copied through unchanged. */
    private static final long CIPHER_BLOCK_SIZE = 512L;
    /** The V3 cipher zero-pads its block up to a whole number of AES blocks. */
    private static final long AES_BLOCK_SIZE = 16L;

    /**
     * The largest boot file either firmware writes.
     *
     * <p>The preflight runs before the device metadata is read, so the firmware family is not known
     * yet. V2 writes at most 64 bytes — the ciphered head of {@code ri} — and V3 writes a 32-byte key
     * fragment, so 64 covers both. Over-counting 32 bytes on a V3 device is the safe direction, and
     * moving the check after the metadata read would turn a synchronous refusal into a failed future.
     */
    static final long BOOT_FILE_MAX_BYTES = 64L;

    /** One big-endian UUID per pack, and nothing else in the file. */
    static final long PACK_INDEX_RECORD_SIZE = 16L;

    private PackTransferSizeEstimator() {
    }

    /**
     * The additional logical bytes an upload of {@code sourceFolder} is known to need.
     *
     * @param sourceFolder      the pack as it sits in the library, in clear
     * @param currentIndexBytes the size of the index already on the device, which is space already
     *                          spent and so is never counted twice — only the transient copy of it
     *                          and the sixteen bytes it grows by are
     * @throws ArithmeticException if the total cannot be represented. Refusing loudly beats wrapping
     *                             to a small number that would read as "plenty of room".
     */
    static long additionalBytesForUpload(Path sourceFolder, long currentIndexBytes) throws IOException {
        List<Path> sourceFiles;
        try (Stream<Path> paths = Files.walk(sourceFolder)) {
            sourceFiles = paths.filter(Files::isRegularFile).collect(Collectors.toList());
        }

        long total = 0L;
        for (Path sourceFile : sourceFiles) {
            total = Math.addExact(total, transferredBytes(sourceFile));
        }

        // The boot file is written into the content folder after the copy, from device material.
        total = Math.addExact(total, BOOT_FILE_MAX_BYTES);
        // The index is replaced through a temporary file holding the whole new index, which exists
        // alongside the current one; and the index itself ends up one record longer.
        total = Math.addExact(total, Math.addExact(currentIndexBytes, PACK_INDEX_RECORD_SIZE));
        total = Math.addExact(total, PACK_INDEX_RECORD_SIZE);
        return total;
    }

    /**
     * What one source file becomes on the device.
     *
     * <p>Which files are copied and which are ciphered is asked of {@link CipherUtils} rather than
     * restated here, so the estimate follows the transfer instead of a second copy of its rules.
     */
    private static long transferredBytes(Path sourceFile) throws IOException {
        if (!CipherUtils.shouldBeCopied(sourceFile)) {
            return 0L;
        }
        long size = Files.size(sourceFile);
        if (!CipherUtils.shouldBeCiphered(sourceFile)) {
            return size;
        }
        return cipheredSize(size);
    }

    /**
     * A ciphered file grows only when it is shorter than the cipher block, and only up to the next
     * AES block. At or beyond 512 bytes the block is already a whole number of AES blocks and the
     * tail is copied as is, so nothing is added — assuming every asset grows would be wrong.
     */
    private static long cipheredSize(long size) {
        if (size >= CIPHER_BLOCK_SIZE) {
            return size;
        }
        long remainder = size % AES_BLOCK_SIZE;
        return remainder == 0L ? size : size + (AES_BLOCK_SIZE - remainder);
    }
}
