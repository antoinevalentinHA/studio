/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A photograph of the write path as it stands, taken before anything is hardened.
 *
 * <p>These are <strong>characterization tests</strong> in the strict sense: they encode what
 * {@code writePackIndex}, {@code uploadPack} and {@code deletePack} do today, including the parts
 * that are plainly undesirable. A test here asserting that {@code .pi} loses its hidden attribute is
 * not a statement that it should; it is a statement that it does, so that the day the behaviour
 * changes the change is visible and deliberate. Names describe the observed behaviour, not the
 * wished-for one.
 *
 * <p>Everything runs against a temporary directory standing in for the device partition. On Windows
 * that is NTFS. <strong>None of this says anything about FAT32</strong>, which is what a device
 * actually uses and which has neither the same rename semantics nor a journal.
 *
 * <p>No production code was changed to make these tests possible: the driver is built by
 * {@link DriverTestSupport} without running its constructor, and the private index writer is reached
 * by reflection.
 */
class WritePathCharacterizationTest {

    /** Stands in for the device partition root. */
    @TempDir
    Path partition;

    /** Stands in for the local library, i.e. the source of an upload. */
    @TempDir
    Path library;

    private static final UUID PACK_A = UUID.fromString("11111111-2222-3333-4444-5555aaaabbbb");
    private static final UUID PACK_B = UUID.fromString("66666666-7777-8888-9999-aaaaccccdddd");
    private static final UUID PACK_C = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffff11112222");

    // ------------------------------------------------------------------ fixtures

    /**
     * A metadata file the driver reads as a V3 device (format version 7). Only the fields the driver
     * actually parses are meaningful; the rest is padding.
     */
    private void writeDeviceMetadataV7() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(7);
        out.write(0);                                   // format version 7, little endian
        out.write('3');
        out.write('.');
        out.write('3');                                 // firmware 3.3, one ASCII digit each
        out.write(new byte[21], 0, 21);
        byte[] serial = new byte[24];
        System.arraycopy("SN0123456789ABCDEF012345".getBytes(StandardCharsets.US_ASCII), 0, serial, 0, 24);
        out.write(serial, 0, 24);
        out.write(new byte[14], 0, 14);
        byte[] keyMaterial = new byte[32];              // 16 bytes of AES key, then 16 of IV
        for (int i = 0; i < 32; i++) {
            keyMaterial[i] = (byte) (0xC0 + i);
        }
        out.write(keyMaterial, 0, 32);
        Files.write(partition.resolve(".md"), out.toByteArray());
    }

    /** The raw bytes of an index holding exactly these packs, in this order. */
    private static byte[] encodeIndex(UUID... uuids) {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        return buffer.array();
    }

    private void writeIndexFile(UUID... uuids) throws IOException {
        Files.write(partition.resolve(".pi"), encodeIndex(uuids));
    }

    private byte[] indexBytes() throws IOException {
        return Files.readAllBytes(partition.resolve(".pi"));
    }

    /**
     * A minimal source pack. `ni` is never ciphered and is copied verbatim; `ri`, `si` and `li` go
     * through the cipher. `.cleartext` marks the assets as unciphered and is deliberately not copied.
     * Sizes are chosen not to be multiples of the AES block size so that any growth is visible.
     */
    private Path sourcePack() throws IOException {
        Path pack = library.resolve("source-pack");
        Files.createDirectories(pack);
        Files.write(pack.resolve(".cleartext"), new byte[0]);
        Files.write(pack.resolve("ni"), filler(200, 'n'));   // clear, copied as is
        Files.write(pack.resolve("ri"), filler(100, 'r'));   // ciphered
        Files.write(pack.resolve("si"), filler(50, 's'));    // ciphered
        Files.write(pack.resolve("li"), filler(30, 'l'));    // ciphered
        return pack;
    }

    private static byte[] filler(int length, char seed) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) seed);
        return data;
    }

    private FsStoryTellerAsyncDriver driver() {
        return DriverTestSupport.pluggedDriverMountedOn(partition);
    }

    // ------------------------------------------------------------------ reflection helpers

    /** {@code writePackIndex} is private and takes an argument, so DriverTestSupport cannot reach it. */
    @SuppressWarnings("unchecked")
    private static void writePackIndex(FsStoryTellerAsyncDriver driver, List<UUID> uuids) {
        try {
            Method method = FsStoryTellerAsyncDriver.class.getDeclaredMethod("writePackIndex", List.class);
            method.setAccessible(true);
            ((CompletableFuture<Boolean>) method.invoke(driver, uuids)).join();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> readPackIndex(FsStoryTellerAsyncDriver driver) {
        try {
            return ((CompletableFuture<List<UUID>>) DriverTestSupport.invokePrivate(driver, "readPackIndex")).join();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static long totalFileBytes(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private static List<String> fileNames(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<String> names = new ArrayList<>();
            paths.filter(Files::isRegularFile).forEach(p -> names.add(root.relativize(p).toString().replace('\\', '/')));
            names.sort(Comparator.naturalOrder());
            return names;
        }
    }

    @BeforeEach
    void prepareDevice() throws IOException {
        writeDeviceMetadataV7();
    }

    /* ================================================================== W1 */

    @Nested
    @DisplayName("W1 — writePackIndex, nominal rewrite")
    class W1NominalIndexRewrite {

        @Test
        @DisplayName("writes 16 bytes per pack, big-endian, in list order")
        void writesSixteenBigEndianBytesPerPack() throws IOException {
            writeIndexFile(PACK_A);

            writePackIndex(driver(), List.of(PACK_A, PACK_B, PACK_C));

            assertArrayEquals(encodeIndex(PACK_A, PACK_B, PACK_C), indexBytes());
            assertEquals(48, indexBytes().length);
        }

        @Test
        @DisplayName("the resulting size is always a multiple of 16 — nothing pads or ciphers the index")
        void indexSizeIsAlwaysAMultipleOfSixteen() throws IOException {
            // Worth pinning explicitly: `.pi` is written by DataOutputStream.writeLong twice per pack
            // and never goes through the cipher, unlike the pack asset files. Whatever the transfer
            // layer does to asset sizes does not apply here.
            for (int packCount = 0; packCount <= 4; packCount++) {
                List<UUID> packs = new ArrayList<>();
                for (int i = 0; i < packCount; i++) {
                    packs.add(new UUID(i, i));
                }
                writePackIndex(driver(), packs);

                assertEquals(packCount * 16, indexBytes().length);
                assertEquals(0, indexBytes().length % 16);
            }
        }

        @Test
        @DisplayName("an empty index is written as a zero-byte file, not deleted")
        void anEmptyIndexIsAZeroByteFile() throws IOException {
            writeIndexFile(PACK_A, PACK_B);

            writePackIndex(driver(), List.of());

            assertTrue(Files.exists(partition.resolve(".pi")));
            assertEquals(0, indexBytes().length);
        }

        @Test
        @DisplayName("the temporary .pi.new is removed once the rewrite succeeds")
        void temporaryFileIsRemovedOnSuccess() throws IOException {
            writeIndexFile(PACK_A);

            writePackIndex(driver(), List.of(PACK_A, PACK_B));

            assertFalse(Files.exists(partition.resolve(".pi.new")), ".pi.new must not survive a successful rewrite");
            assertEquals(List.of(".md", ".pi"), fileNames(partition));
        }

        @Test
        @DisplayName("what is written is what readPackIndex reads back")
        void roundTripsThroughTheReader() throws IOException {
            writeIndexFile();

            FsStoryTellerAsyncDriver driver = driver();
            writePackIndex(driver, List.of(PACK_C, PACK_A, PACK_B));

            assertEquals(List.of(PACK_C, PACK_A, PACK_B), readPackIndex(driver), "order is preserved");
        }
    }

    /* ================================================================== W2 */

    @Nested
    @DisplayName("W2 — the hidden attribute of .pi")
    class W2HiddenAttribute {

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("rewriting the index DROPS the hidden attribute of .pi")
        void rewritingTheIndexDropsTheHiddenAttribute() throws IOException {
            // On a device `.pi` is hidden. The rewrite replaces it with Files.copy(REPLACE_EXISTING)
            // from a temporary file created with default attributes, and the copy carries the source's
            // attributes over. Measured here through the driver's own code path, not through an
            // isolated NIO call. Whether the firmware cares is unknown and untested.
            writeIndexFile(PACK_A);
            Path index = partition.resolve(".pi");
            Files.setAttribute(index, "dos:hidden", true);
            assertEquals(Boolean.TRUE, Files.getAttribute(index, "dos:hidden"), "precondition");

            writePackIndex(driver(), List.of(PACK_A, PACK_B));

            assertEquals(Boolean.FALSE, Files.getAttribute(index, "dos:hidden"),
                    "the rewrite clears the attribute — this is the current behaviour, not a requirement");
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("the content is still correct after the attribute is lost")
        void contentIsStillCorrectWhenTheAttributeIsLost() throws IOException {
            writeIndexFile(PACK_A);
            Files.setAttribute(partition.resolve(".pi"), "dos:hidden", true);

            writePackIndex(driver(), List.of(PACK_A, PACK_B));

            assertArrayEquals(encodeIndex(PACK_A, PACK_B), indexBytes());
        }
    }

    /* ================================================================== W3 */

    /**
     * What a failed rewrite leaves behind.
     *
     * <p><strong>Not covered here:</strong> observing {@code .pi} <em>during</em> the replacement,
     * i.e. proving directly that there is an instant where it is absent or partial. {@code
     * writePackIndex} builds its paths with {@code Paths.get(String)}, so it always works on the
     * default filesystem: no instrumented {@code FileSystem} can be substituted, and there is no
     * extension point between writing {@code .pi.new} and the end of the copy. Closing that gap would
     * need a production seam — an injectable filesystem root or an index-writing abstraction — which
     * this session deliberately did not add. The tests below therefore characterise the reachable
     * failure modes only.
     */
    @Nested
    @DisplayName("W3 — a rewrite that fails part-way")
    class W3FailedRewrite {

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("a read-only .pi makes the replacement fail, and the old index survives intact")
        void aReadOnlyIndexSurvivesAFailedReplacement() throws IOException {
            // Useful for two reasons. It shows that a failure is reported rather than swallowed, and
            // it shows that Files.copy(REPLACE_EXISTING) tries to REMOVE the target before writing
            // anything: a read-only target cannot be deleted, so the copy fails before the old
            // content is touched. The corollary is the part that matters and that this test cannot
            // observe directly — on the nominal path the target is removed first, so between that
            // removal and the end of the copy there is a window with no valid `.pi` on the device.
            writeIndexFile(PACK_A, PACK_B);
            byte[] before = indexBytes();
            Path index = partition.resolve(".pi");
            Files.setAttribute(index, "dos:readonly", true);

            try {
                assertThrows(CompletionException.class, () -> writePackIndex(driver(), List.of(PACK_A)));

                assertArrayEquals(before, indexBytes(), "the previous index is still readable");
            } finally {
                Files.setAttribute(index, "dos:readonly", false);
            }
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("a failed rewrite removes the temporary it created")
        void aFailedRewriteRemovesTheTemporaryItCreated() throws IOException {
            // `Files.delete` used to sit after the copy, so any failure of the copy skipped it and the
            // device was left carrying a stray `.pi.new` holding an index that was never installed.
            // The temporary is now removed on the way out. The failure is still reported and the
            // previous index is still intact, so the device is left no worse than before the attempt.
            writeIndexFile(PACK_A, PACK_B);
            byte[] before = indexBytes();
            Path index = partition.resolve(".pi");
            Files.setAttribute(index, "dos:readonly", true);

            try {
                assertThrows(CompletionException.class, () -> writePackIndex(driver(), List.of(PACK_A)));

                assertFalse(Files.exists(partition.resolve(".pi.new")),
                        "the temporary this operation created must not survive its failure");
                assertArrayEquals(before, indexBytes(), "and the previous index is untouched");
            } finally {
                Files.setAttribute(index, "dos:readonly", false);
            }
        }

        @Test
        @DisplayName("a rewrite into a partition that no longer exists fails without leaving anything")
        void aRewriteOntoAMissingPartitionFailsCleanly() throws IOException {
            // Closest reachable analogue of a device removed mid-operation: the mount point is gone,
            // so even the temporary file cannot be created.
            Path vanished = partition.resolve("not-mounted");
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(vanished);

            assertThrows(CompletionException.class, () -> writePackIndex(driver, List.of(PACK_A)));

            assertFalse(Files.exists(vanished), "nothing was created");
        }
    }

    /* ================================================================== W4 */

    @Nested
    @DisplayName("W4 — uploadPack, content before index")
    class W4UploadOrdering {

        @Test
        @DisplayName("a successful upload writes the content folder and then adds the pack to the index")
        void nominalUploadWritesContentThenIndex() throws Exception {
            writeIndexFile(PACK_A);
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();

            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();

            Path contentFolder = partition.resolve(".content").resolve(driver.computePackFolderName(PACK_B.toString()));
            assertTrue(Files.isDirectory(contentFolder));
            assertEquals(List.of("bt", "li", "ni", "ri", "si"), fileNames(contentFolder),
                    "`.cleartext` is not copied, and `bt` is generated on the device side");
            assertEquals(List.of(PACK_A, PACK_B), readPackIndex(driver), "the new pack is appended to the index");
        }

        @Test
        @DisplayName("a copy that fails LEAVES an orphan content folder and does NOT touch the index")
        void aFailedCopyLeavesAnOrphanContentFolder() throws Exception {
            // The content folder is created, and the index is only updated once the copy has finished.
            // A failure in between therefore leaves a directory under `.content` that no index entry
            // refers to: invisible to the UI, consuming space, and never cleaned up.
            //
            // The failure is produced without touching production code, by planting a conflicting
            // clear-text file in the destination: those are copied with Files.copy and no
            // REPLACE_EXISTING, so the copy throws.
            writeIndexFile(PACK_A);
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = partition.resolve(".content").resolve(driver.computePackFolderName(PACK_B.toString()));
            Files.createDirectories(contentFolder);
            Files.write(contentFolder.resolve("ni"), filler(7, 'X'));

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertTrue(hasCause(raised, FileAlreadyExistsException.class),
                    "expected a FileAlreadyExistsException somewhere in " + raised);
            assertTrue(Files.isDirectory(contentFolder), "the content folder is left behind");
            assertEquals(List.of(PACK_A), readPackIndex(driver), "the index is untouched, so the folder is an orphan");
        }

        @Test
        @DisplayName("the orphan folder is not detected by the caller: nothing reports it")
        void nothingReportsTheOrphanFolder() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = partition.resolve(".content").resolve(driver.computePackFolderName(PACK_B.toString()));
            Files.createDirectories(contentFolder);
            Files.write(contentFolder.resolve("ni"), filler(7, 'X'));

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            // getPacksList works off the index, so an orphan directory is simply invisible.
            assertEquals(List.of(), readPackIndex(driver));
            assertTrue(Files.isDirectory(contentFolder));
        }
    }

    /* ================================================================== W5 */

    @Nested
    @DisplayName("W5 — retrying an upload over a partial state")
    class W5Retry {

        @Test
        @DisplayName("retrying the same pack FAILS on the clear-text files, which are copied without REPLACE_EXISTING")
        void retryingOverAnExistingFolderFails() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertTrue(hasCause(raised, FileAlreadyExistsException.class));
        }

        @Test
        @DisplayName("a failed retry leaves an INCOMPLETE folder: the copy stops where it failed")
        void aFailedRetryLeavesAnIncompleteFolder() throws Exception {
            // Files.walk visits the source entries in directory order, and the copy aborts on the
            // first clear-text file that already exists in the destination. Whatever the walk had not
            // reached yet is simply never written, so the folder is left neither old nor new.
            //
            // Which entries survive depends on the order the filesystem returns them, so this test
            // asserts only what holds on any platform: the conflicting file keeps its previous
            // content, and `bt` — generated after the walk finishes — is absent, which is the
            // reliable marker of a folder that was never completed.
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = partition.resolve(".content").resolve(driver.computePackFolderName(PACK_B.toString()));
            Files.createDirectories(contentFolder);
            Files.write(contentFolder.resolve("ni"), filler(7, 'X'));       // clear file: makes the copy fail

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertArrayEquals(filler(7, 'X'), Files.readAllBytes(contentFolder.resolve("ni")),
                    "the conflicting clear file kept its previous content");
            assertFalse(Files.exists(contentFolder.resolve("bt")),
                    "bt is written only once the walk completes, so its absence marks an unfinished folder");
            assertTrue(fileNames(contentFolder).size() < 5,
                    "a complete upload produces bt, li, ni, ri and si — this folder has "
                            + fileNames(contentFolder));
        }

        @Test
        @DisplayName("a retry after a successful upload does NOT add a second index entry")
        void aFailedRetryDoesNotDuplicateTheIndexEntry() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertEquals(List.of(PACK_B), readPackIndex(driver), "the failure happens before the index is touched");
        }
    }

    /* ================================================================== W6 */

    @Nested
    @DisplayName("W6 — deletePack, index before content")
    class W6DeleteOrdering {

        @Test
        @DisplayName("a successful delete removes the index entry and then the content folder")
        void nominalDeleteRemovesIndexEntryThenFolder() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();
            Path contentFolder = partition.resolve(".content").resolve(driver.computePackFolderName(PACK_B.toString()));
            assertTrue(Files.isDirectory(contentFolder), "precondition");

            assertTrue(driver.deletePack(PACK_B.toString()).join());

            assertEquals(List.of(), readPackIndex(driver));
            assertFalse(Files.exists(contentFolder));
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("if the folder cannot be removed, the pack is ALREADY de-indexed: content is orphaned")
        void aFailedFolderRemovalLeavesDeIndexedContent() throws Exception {
            // The index is rewritten first, the directory deleted second. Holding a legacy
            // FileInputStream open on a file inside the folder makes the deletion fail on Windows,
            // which exposes the intermediate state: the pack has disappeared from the device as far
            // as the index is concerned, while its content still occupies the card.
            //
            // The stream has to be a java.io.FileInputStream. Files.newInputStream opens with
            // FILE_SHARE_DELETE on Windows, so deletion succeeds under it and the failure never
            // happens — the same distinction C4 ran into with the metadata handle.
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();
            Path contentFolder = partition.resolve(".content").resolve(driver.computePackFolderName(PACK_B.toString()));

            try (InputStream keepOpen = new FileInputStream(contentFolder.resolve("ni").toFile())) {
                assertThrows(CompletionException.class, () -> driver.deletePack(PACK_B.toString()).join());

                assertEquals(List.of(), readPackIndex(driver), "the index entry is already gone");
                assertTrue(Files.isDirectory(contentFolder), "while the content is still there");
                assertTrue(keepOpen.available() >= 0);
            }
        }

        @Test
        @DisplayName("deleting a pack that is not in the index fails without touching anything")
        void deletingAnUnknownPackChangesNothing() throws Exception {
            writeIndexFile(PACK_A);
            FsStoryTellerAsyncDriver driver = driver();
            byte[] before = indexBytes();

            assertThrows(CompletionException.class, () -> driver.deletePack(PACK_B.toString()).join());

            assertArrayEquals(before, indexBytes());
        }
    }

    /* ================================================================== W7 */

    @Nested
    @DisplayName("W7 — the free-space precheck versus the bytes actually written")
    class W7FreeSpaceEstimate {

        @Test
        @DisplayName("the source bytes can be fewer than the logical bytes actually written")
        void sourceBytesCanBeLessThanLogicalBytesWritten() throws Exception {
            // A property of the cipher and the transfer, not of any estimate: the V3 cipher pads each
            // asset up to the AES block size, and `bt` is generated on the device from device
            // material, so it is in no source tree at all. Summing the source therefore measures less
            // than what lands on the card. This is the measurement a free-space preflight has to
            // account for; what the current one does with it is specified in
            // PackTransferSizeEstimatorTest, not here.
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();

            long sourceBytes = totalFileBytes(source);
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();
            Path contentFolder = partition.resolve(".content").resolve(driver.computePackFolderName(PACK_B.toString()));
            long actuallyWritten = totalFileBytes(contentFolder);

            assertEquals(380, sourceBytes, "200 + 100 + 50 + 30, plus a zero-byte .cleartext");
            assertTrue(actuallyWritten > sourceBytes,
                    "written " + actuallyWritten + " bytes for " + sourceBytes + " bytes of source");
            assertEquals(440, actuallyWritten,
                    "ni 200 verbatim, ri 100->112, si 50->64, li 30->32, plus a 32-byte bt");
        }

        @Test
        @DisplayName("the estimate ignores the files the transfer does not copy")
        void theEstimateIgnoresFilesTheTransferDoesNotCopy() throws Exception {
            // The `.cleartext` marker is a library-side flag that the transfer drops. The old estimate
            // summed the source tree blindly and counted it; the current one asks CipherUtils the same
            // question the copy asks, so the marker's size cannot move it. Observed here against a
            // real upload, which is what keeps the rule anchored to what the transfer does rather than
            // to a restatement of itself.
            Path source = sourcePack();
            writeIndexFile();
            FsStoryTellerAsyncDriver driver = driver();

            long withEmptyMarker = PackTransferSizeEstimator.additionalBytesForUpload(source, 0L);
            Files.write(source.resolve(".cleartext"), filler(11, 'c'));
            long withElevenByteMarker = PackTransferSizeEstimator.additionalBytesForUpload(source, 0L);

            assertEquals(withEmptyMarker, withElevenByteMarker,
                    "the marker's size must not move the estimate");

            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();

            Path contentFolder = partition.resolve(".content").resolve(driver.computePackFolderName(PACK_B.toString()));
            assertFalse(Files.exists(contentFolder.resolve(".cleartext")),
                    "the transfer drops the marker, which is why the estimate may ignore it");
        }

        @Test
        @DisplayName("the precheck keeps its arithmetic in long, past the int range")
        void thePrecheckKeepsItsArithmeticInLong() throws Exception {
            // The precheck used to do `int folderSize = (int) FileUtils.getFolderSize(inputPath)`.
            // Beyond 2 GiB that wrapped negative and `getFreeSpace() < folderSize` passed
            // unconditionally: the check silently stopped checking. Both halves are now long — the
            // requirement itself, and the comparison against free space.
            long fourGigabytes = 4L * 1024 * 1024 * 1024;
            Path source = sourcePack();

            long requirement = PackTransferSizeEstimator.additionalBytesForUpload(source, fourGigabytes);

            assertTrue(requirement > Integer.MAX_VALUE, "the requirement wrapped, saw " + requirement);
            assertFalse(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(requirement - 1, requirement),
                    "one byte short must refuse, whatever the magnitude");
            assertTrue(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(requirement, requirement));
            assertFalse(FsStoryTellerAsyncDriver.hasEnoughFreeSpace(0L, requirement),
                    "an empty device must not accept a multi-gigabyte transfer");
        }
    }

    // ------------------------------------------------------------------ helper

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}

