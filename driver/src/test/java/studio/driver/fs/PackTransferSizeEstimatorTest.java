/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the upload preflight must count, and what it must not pretend to know.
 *
 * <p>These are <strong>specification</strong> tests. The estimate must never fall below the logical
 * bytes the transfer is known to add, because an estimate that is too low lets an upload start that
 * cannot finish — and an upload that runs out of space part-way leaves an orphan content folder
 * behind, which nothing cleans up.
 *
 * <p>What it computes is a <strong>conservative upper bound</strong> rather than an exact figure:
 * the preflight runs before the device metadata is read, so the firmware family is unknown and the
 * worse of the two is assumed for the cipher padding and the boot file. The tests below pin the
 * bound, and say where it deliberately sits above the true cost.
 *
 * <h2>What it counts</h2>
 *
 * <p>Per source file, following {@link CipherUtils} rather than restating its rules:
 *
 * <ul>
 *   <li>{@code .cleartext} is not copied, so it costs nothing;</li>
 *   <li>{@code ni} and {@code nm} go over verbatim;</li>
 *   <li>everything else is ciphered, and the V3 cipher zero-pads its block up to the AES block size,
 *       so a file shorter than the 512-byte cipher block grows to the next multiple of 16. A file of
 *       512 bytes or more does not grow: the block is already a whole number of AES blocks and the
 *       tail is copied as is.</li>
 * </ul>
 *
 * <p>Then the boot file, which is generated on the device and appears in no source tree, and the two
 * index terms — the temporary index written in full alongside the existing one, and the sixteen
 * bytes the index itself grows by.
 *
 * <h2>What it deliberately does not count</h2>
 *
 * <p><strong>This is a bound on logical bytes, not on allocated space.</strong> FAT32 allocates by
 * cluster: C6c measured 440 logical bytes occupying 32768 allocated bytes in a synthetic pack, and
 * {@code FileStore.getBlockSize()} reports the 512-byte sector rather than the 4096-byte cluster, so
 * the cluster size is not available through the APIs in use. Directories, directory entries, cluster
 * slack and filesystem metadata are therefore not counted either. Nothing here should be read as a
 * guarantee that an accepted transfer will fit.
 */
class PackTransferSizeEstimatorTest {

    @TempDir
    Path source;

    /** The C6b fixture, so the numbers below can be compared with what that suite measured. */
    private void writeStandardPack() throws IOException {
        Files.write(source.resolve(".cleartext"), new byte[0]);
        Files.write(source.resolve("ni"), filler(200));   // clear, copied verbatim
        Files.write(source.resolve("ri"), filler(100));   // ciphered, 100 -> 112
        Files.write(source.resolve("si"), filler(50));    // ciphered, 50 -> 64
        Files.write(source.resolve("li"), filler(30));    // ciphered, 30 -> 32
    }

    /** 200 + 112 + 64 + 32 — what the C6b suite observed landing in the content folder, minus bt. */
    private static final long STANDARD_PACK_CONTENT_BYTES = 408L;

    private static byte[] filler(int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) 'x');
        return data;
    }

    private long estimate(long currentIndexBytes) throws IOException {
        return PackTransferSizeEstimator.additionalBytesForUpload(source, currentIndexBytes);
    }

    /** The two index terms and the boot file, for an index of {@code currentIndexBytes}. */
    private static long overheadFor(long currentIndexBytes) {
        return PackTransferSizeEstimator.BOOT_FILE_MAX_BYTES
                + (currentIndexBytes + PackTransferSizeEstimator.PACK_INDEX_RECORD_SIZE)
                + PackTransferSizeEstimator.PACK_INDEX_RECORD_SIZE;
    }

    // ------------------------------------------------------------------ R2: cipher growth

    @Nested
    @DisplayName("R2 — the ciphered files are counted at the size they will actually occupy")
    class R2CipherGrowth {

        @Test
        @DisplayName("a short ciphered file is counted padded up to the AES block size")
        void shortCipheredFilesArePaddedUp() throws IOException {
            Files.write(source.resolve("ri"), filler(100));

            assertEquals(112L + overheadFor(0), estimate(0),
                    "100 bytes are written as 112: the cipher zero-pads to the next multiple of 16");
        }

        @Test
        @DisplayName("a ciphered file already aligned on 16 bytes does not grow")
        void alignedCipheredFilesDoNotGrow() throws IOException {
            Files.write(source.resolve("ri"), filler(96));

            assertEquals(96L + overheadFor(0), estimate(0));
        }

        @Test
        @DisplayName("a ciphered file at or beyond the cipher block does not grow")
        void largeCipheredFilesDoNotGrow() throws IOException {
            // Only the first 512 bytes are ciphered, and 512 is already a whole number of AES blocks;
            // the tail is copied unchanged. Assuming every asset grows would be wrong.
            Files.write(source.resolve("ri"), filler(512));
            long withExactBlock = estimate(0);

            Files.write(source.resolve("ri"), filler(1000));
            long withTail = estimate(0);

            assertEquals(512L + overheadFor(0), withExactBlock);
            assertEquals(1000L + overheadFor(0), withTail, "the tail beyond 512 bytes is not padded");
        }

        @Test
        @DisplayName("clear files are counted as they are, and .cleartext costs nothing")
        void clearFilesAreNotPadded() throws IOException {
            Files.write(source.resolve("ni"), filler(100));
            Files.write(source.resolve("nm"), filler(30));
            Files.write(source.resolve(".cleartext"), filler(11));

            assertEquals(130L + overheadFor(0), estimate(0),
                    "ni and nm go over verbatim; .cleartext is never copied");
        }

        @Test
        @DisplayName("the whole C6b fixture is counted at no less than what that suite saw written")
        void theStandardPackIsNotUnderestimated() throws IOException {
            writeStandardPack();

            long estimated = estimate(0);

            assertEquals(STANDARD_PACK_CONTENT_BYTES + overheadFor(0), estimated);
            // C6b measured 440 bytes landing in the content folder for this pack, boot file included.
            assertTrue(estimated >= 440L, "must not fall below what C6b measured, got " + estimated);
        }
    }

    // ------------------------------------------------------------------ R3: boot file

    @Nested
    @DisplayName("R3 — the boot file is counted even though it is in no source tree")
    class R3BootFile {

        @Test
        @DisplayName("bt is counted at the larger of the two firmware sizes")
        void theBootFileIsCounted() throws IOException {
            // The preflight runs before the device metadata is read, so the firmware family is not
            // known yet. V2 writes at most 64 bytes of ciphered `ri`, V3 writes the 32-byte key
            // fragment; counting 64 covers both. Over-counting by 32 bytes on V3 is the safe side.
            Files.write(source.resolve("ni"), filler(64));
            long withoutAnyCipheredFile = estimate(0);

            assertEquals(64L + overheadFor(0), withoutAnyCipheredFile);
            assertEquals(64L, PackTransferSizeEstimator.BOOT_FILE_MAX_BYTES);
        }

        @Test
        @DisplayName("an empty source still costs the boot file and the index terms")
        void anEmptySourceStillCostsTheOverhead() throws IOException {
            assertEquals(overheadFor(0), estimate(0));
        }
    }

    // ------------------------------------------------------------------ R4: index terms

    @Nested
    @DisplayName("R4 — the index terms model the transient cost, not the whole index twice")
    class R4IndexGrowth {

        @Test
        @DisplayName("the temporary index and the sixteen bytes the index grows by are both counted")
        void bothIndexTermsAreCounted() throws IOException {
            // With four packs already on the device the index holds 64 bytes. Writing a fifth means
            // .pi.new occupies 80 bytes alongside the existing .pi, and .pi itself ends up 16 bytes
            // longer. The 64 bytes already on the card are NOT counted again: they are already spent.
            long fourPacks = 4 * 16L;

            long estimated = estimate(fourPacks);

            assertEquals(80L + 16L + PackTransferSizeEstimator.BOOT_FILE_MAX_BYTES, estimated);
        }

        @Test
        @DisplayName("a device with no index yet costs one record plus its growth")
        void anAbsentIndexCostsOneRecord() throws IOException {
            assertEquals(16L + 16L + PackTransferSizeEstimator.BOOT_FILE_MAX_BYTES, estimate(0));
        }

        @Test
        @DisplayName("the index term grows with the number of packs already present")
        void theIndexTermFollowsTheCurrentIndex() throws IOException {
            assertEquals(estimate(0) + 16L, estimate(16L),
                    "one more pack on the device means sixteen more transient bytes");
        }
    }

    // ------------------------------------------------------------------ R1: no int truncation

    @Nested
    @DisplayName("R1 — the arithmetic stays in long, and an overflow is refused rather than wrapped")
    class R1LongArithmetic {

        @Test
        @DisplayName("an estimate beyond Integer.MAX_VALUE is returned intact")
        void theEstimateIsNotTruncatedToAnInt() throws IOException {
            // The preflight used to do `int folderSize = (int) FileUtils.getFolderSize(inputPath)`.
            // Beyond 2 GiB that wraps negative, and a negative estimate passes `freeSpace < estimate`
            // unconditionally — the check silently stopped checking. Exercised through the index term
            // rather than a multi-gigabyte fixture, which would cost the suite time and disk for no
            // extra proof: a single `int` anywhere in the sum would still wrap.
            long threeGigabytes = 3L * 1024 * 1024 * 1024;

            long estimated = estimate(threeGigabytes);

            assertTrue(estimated > Integer.MAX_VALUE, "wrapped to " + estimated);
            assertEquals(threeGigabytes + 16L + 16L + PackTransferSizeEstimator.BOOT_FILE_MAX_BYTES, estimated);
        }

        @Test
        @DisplayName("a sum that cannot be represented raises rather than wrapping to a small number")
        void anOverflowingSumRaises() {
            // Not reachable with a real pack, but a wrapped total would read as "plenty of room" and
            // the check would pass. Failing loudly is the conservative direction.
            assertThrows(ArithmeticException.class, () -> estimate(Long.MAX_VALUE));
        }
    }

    // ------------------------------------------------------------------ R5: the decision

    @Nested
    @DisplayName("R5 — the preflight decision compares longs and has no overflow of its own")
    class R5FreeSpaceDecision {

        @Test
        @DisplayName("free space just below the estimate is refused, equal or above is accepted")
        void theBoundaryIsExact() {
            assertFalse(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(439L, 440L));
            assertTrue(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(440L, 440L),
                    "exactly enough is enough");
            assertTrue(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(441L, 440L));
        }

        @Test
        @DisplayName("the comparison holds beyond the int range")
        void theComparisonHoldsForLargeValues() {
            long fourGigabytes = 4L * 1024 * 1024 * 1024;

            assertFalse(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(fourGigabytes - 1, fourGigabytes));
            assertTrue(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(fourGigabytes, fourGigabytes));
            // The values that used to break it: a requirement past 2 GiB, and a difference that does
            // not fit in an int. Subtracting instead of comparing would wrap here.
            assertFalse(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(0L, fourGigabytes));
            assertTrue(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(Long.MAX_VALUE, fourGigabytes));
        }

        @Test
        @DisplayName("nothing free means nothing fits, except a transfer that needs nothing")
        void anEmptyDeviceRefusesEverything() {
            assertFalse(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(0L, 1L));
            assertTrue(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(0L, 0L));
        }
    }
}
