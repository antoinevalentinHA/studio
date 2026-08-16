/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 * The C6b write-path characterization, replayed on a real FAT32 volume.
 *
 * <p>{@link WritePathCharacterizationTest} measures the write path on whatever the JVM's temporary
 * directory sits on, which on Windows is NTFS. A device uses FAT32, which has neither the same
 * rename semantics nor a journal, so none of those results transfer. This class re-asks the subset
 * of questions where the filesystem could plausibly change the answer, and adds the one question
 * that only FAT32 can answer: what a pack folder actually costs in allocated space.
 *
 * <p><strong>Opt-in, exactly like {@link Fat32AtomicMoveCharacterizationTest}.</strong> It runs only
 * when pointed at a disposable FAT32 volume:
 *
 * <pre>
 *   mvn -pl driver test -Dstudio.test.fat32.root=W:/
 * </pre>
 *
 * <p>Use a forward slash: {@code W:} alone means "the current directory on drive W" in Java, not its
 * root. The same two guards as the existing FAT32 test apply — the volume must report a FAT
 * filesystem, and the run is refused outright if the root looks like a story teller. Each test works
 * inside its own temporary sub-directory and cleans up after itself. <strong>Never point this at a
 * device.</strong>
 *
 * <p>These remain characterization tests. Recording that {@code .pi} loses its hidden attribute on
 * FAT32 states that it does, not that it should. No production code was changed to make any of this
 * observable: the driver is built by {@link DriverTestSupport} and the private index writer is
 * reached by reflection, as in C6b.
 */
@EnabledIfSystemProperty(named = "studio.test.fat32.root", matches = ".+",
        disabledReason = "set -Dstudio.test.fat32.root=<drive> to run the FAT32 characterization")
class Fat32WritePathCharacterizationTest {

    /** The volume root, e.g. {@code W:/}. Used for free-space measurements. */
    private Path volumeRoot;
    /** A fresh directory on that volume, standing in for the device partition. */
    private Path partition;
    /** Source packs, kept on the volume too so that nothing crosses filesystems mid-test. */
    private Path library;

    private static final UUID PACK_A = UUID.fromString("11111111-2222-3333-4444-5555aaaabbbb");
    private static final UUID PACK_B = UUID.fromString("66666666-7777-8888-9999-aaaaccccdddd");

    @BeforeEach
    void createWorkingDirectoriesOnTheFat32Volume() throws IOException {
        volumeRoot = Paths.get(System.getProperty("studio.test.fat32.root"));

        String type = Files.getFileStore(volumeRoot).type().toUpperCase(Locale.ROOT);
        Assumptions.assumeTrue(type.contains("FAT"),
                () -> "studio.test.fat32.root points at a " + type + " volume, not a FAT one");

        Assumptions.assumeFalse(
                Files.exists(volumeRoot.resolve(".md")) || Files.exists(volumeRoot.resolve(".pi")),
                "refusing to run: the target volume looks like a story teller device");

        partition = Files.createTempDirectory(volumeRoot, "c6c-part-");
        library = Files.createTempDirectory(volumeRoot, "c6c-lib-");
        writeDeviceMetadataV7();
    }

    @AfterEach
    void removeWorkingDirectories() {
        for (Path root : new Path[]{partition, library}) {
            if (root == null) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        // A test may have left a read-only attribute behind; clear it first.
                        if (Files.isRegularFile(path)) {
                            Files.setAttribute(path, "dos:readonly", false);
                        }
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Best effort: the volume is disposable.
                    }
                });
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }
    }

    // ------------------------------------------------------------------ fixtures
    // Duplicated from WritePathCharacterizationTest rather than shared: that class is already merged
    // and this one must not perturb it.

    private void writeDeviceMetadataV7() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(7);
        out.write(0);
        out.write('3');
        out.write('.');
        out.write('3');
        out.write(new byte[21], 0, 21);
        byte[] serial = new byte[24];
        System.arraycopy("SN0123456789ABCDEF012345".getBytes(StandardCharsets.US_ASCII), 0, serial, 0, 24);
        out.write(serial, 0, 24);
        out.write(new byte[14], 0, 14);
        byte[] keyMaterial = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyMaterial[i] = (byte) (0xC0 + i);
        }
        out.write(keyMaterial, 0, 32);
        Files.write(partition.resolve(".md"), out.toByteArray());
    }

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

    private Path sourcePack() throws IOException {
        Path pack = library.resolve("source-pack");
        Files.createDirectories(pack);
        Files.write(pack.resolve(".cleartext"), new byte[0]);
        Files.write(pack.resolve("ni"), filler(200, 'n'));
        Files.write(pack.resolve("ri"), filler(100, 'r'));
        Files.write(pack.resolve("si"), filler(50, 's'));
        Files.write(pack.resolve("li"), filler(30, 'l'));
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

    private static List<String> fileNames(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<String> names = new ArrayList<>();
            paths.filter(Files::isRegularFile).forEach(p -> names.add(root.relativize(p).toString().replace('\\', '/')));
            names.sort(Comparator.naturalOrder());
            return names;
        }
    }

    private static long totalFileBytes(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

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

    /* ================================================================== F1 */

    @Nested
    @DisplayName("F1 — the hidden attribute of .pi, through the real driver path")
    class F1HiddenAttribute {

        @Test
        @DisplayName("rewriting the index DROPS the hidden attribute on FAT32 too")
        void rewritingTheIndexDropsTheHiddenAttributeOnFat32() throws IOException {
            // Same measurement C6b made on NTFS, repeated here through writePackIndex rather than a
            // bare NIO call, because FAT32 stores attributes in the directory entry and could in
            // principle have behaved differently.
            writeIndexFile(PACK_A);
            Path index = partition.resolve(".pi");
            Files.setAttribute(index, "dos:hidden", true);
            assertEquals(Boolean.TRUE, Files.getAttribute(index, "dos:hidden"), "precondition");

            writePackIndex(driver(), List.of(PACK_A, PACK_B));

            assertEquals(Boolean.FALSE, Files.getAttribute(index, "dos:hidden"),
                    "the rewrite clears the attribute — current behaviour, not a requirement");
            assertArrayEquals(encodeIndex(PACK_A, PACK_B), indexBytes(), "content is still correct");
        }
    }

    /* ================================================================== F3 / F6 */

    @Nested
    @DisplayName("F3 / F6 — a rewrite that fails, and what it leaves behind")
    class F3FailedRewrite {

        @Test
        @DisplayName("a read-only .pi makes the replacement fail and the old index survives intact")
        void aReadOnlyIndexSurvivesAFailedReplacement() throws IOException {
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
        @DisplayName("F6: a failed rewrite removes the temporary it created, on FAT32 too")
        void aFailedRewriteRemovesTheTemporaryItCreated() throws IOException {
            // Same property as its NTFS twin, and measured to behave the same way here: FAT32 changes
            // neither the cleanup nor the survival of the previous index.
            writeIndexFile(PACK_A, PACK_B);
            byte[] before = indexBytes();
            Path index = partition.resolve(".pi");
            Files.setAttribute(index, "dos:readonly", true);

            try {
                assertThrows(CompletionException.class, () -> writePackIndex(driver(), List.of(PACK_A)));

                assertFalse(Files.exists(partition.resolve(".pi.new")),
                        "the temporary must not survive a failed installation on FAT32 either");
                assertArrayEquals(before, indexBytes(), "and the previous index is untouched");
            } finally {
                Files.setAttribute(index, "dos:readonly", false);
            }
        }

        @Test
        @DisplayName("a nominal rewrite removes .pi.new on FAT32")
        void aNominalRewriteRemovesTheTemporaryFile() throws IOException {
            writeIndexFile(PACK_A);

            writePackIndex(driver(), List.of(PACK_A, PACK_B));

            assertFalse(Files.exists(partition.resolve(".pi.new")));
            assertEquals(32, indexBytes().length);
        }
    }

    /* ================================================================== F4 */

    @Nested
    @DisplayName("F4 — open handles versus deletion")
    class F4OpenHandles {

        @Test
        @DisplayName("a legacy FileInputStream BLOCKS deletion on FAT32, as on NTFS")
        void aLegacyStreamBlocksDeletion() throws Exception {
            Path victim = partition.resolve("victim");
            Files.write(victim, filler(10, 'v'));

            try (InputStream ignored = new FileInputStream(victim.toFile())) {
                assertThrows(IOException.class, () -> Files.delete(victim),
                        "an open FileInputStream should keep the file undeletable");
            }
            Files.delete(victim);
        }

        @Test
        @DisplayName("Files.newInputStream does NOT block deletion on FAT32 either")
        void aNioStreamDoesNotBlockDeletion() throws Exception {
            // NIO opens with FILE_SHARE_DELETE on Windows, so the deletion is allowed and the entry
            // disappears while the handle is still open. Same distinction C6b hit on NTFS, and the
            // reason the delete-failure scenario needs the legacy stream.
            Path victim = partition.resolve("victim-nio");
            Files.write(victim, filler(10, 'v'));

            try (InputStream ignored = Files.newInputStream(victim)) {
                Files.delete(victim);
                assertFalse(Files.exists(victim), "deletion succeeded despite the open NIO handle");
            }
        }

        @Test
        @DisplayName("a legacy handle inside a pack folder blocks the whole folder removal")
        void aLegacyHandleBlocksFolderRemoval() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();
            Path contentFolder = partition.resolve(".content")
                    .resolve(driver.computePackFolderName(PACK_B.toString()));

            try (InputStream ignored = new FileInputStream(contentFolder.resolve("ni").toFile())) {
                assertThrows(CompletionException.class, () -> driver.deletePack(PACK_B.toString()).join());

                assertEquals(List.of(), readPackIndex(driver), "the index entry is already gone");
                assertTrue(Files.isDirectory(contentFolder), "while the content is still there");
            }
        }
    }

    /* ================================================================== F5 */

    @Nested
    @DisplayName("F5 — what a pack folder really costs in allocated space")
    class F5ClusterSlack {

        private long usableSpace() throws IOException {
            FileStore store = Files.getFileStore(volumeRoot);
            return store.getUsableSpace();
        }

        /**
         * The real allocation unit, measured rather than asked for: a one-byte file costs exactly one
         * cluster, so the drop in usable space is the cluster size.
         */
        private long measureClusterSize() throws IOException {
            Path probe = partition.resolve("cluster-probe");
            long before = usableSpace();
            Files.write(probe, new byte[]{1});
            long consumed = before - usableSpace();
            Files.delete(probe);
            return consumed;
        }

        @Test
        @DisplayName("Java's getBlockSize reports the SECTOR size, not the FAT32 cluster size")
        void javaReportsTheSectorSizeNotTheClusterSize() throws IOException {
            // Worth pinning, because it is a trap for any future free-space arithmetic: on Windows
            // FileStore.getBlockSize() returns bytes-per-sector, while what actually governs
            // consumption is the allocation unit. On this volume Windows reports a 4096-byte cluster
            // and Java reports 512.
            FileStore store = Files.getFileStore(volumeRoot);

            long reportedBlockSize = store.getBlockSize();
            long measuredCluster = measureClusterSize();

            System.out.println("[F5] type=" + store.type()
                    + " getBlockSize=" + reportedBlockSize
                    + " measuredCluster=" + measuredCluster
                    + " total=" + store.getTotalSpace());

            assertEquals(512, reportedBlockSize, "getBlockSize reports the sector size");
            assertTrue(measuredCluster > reportedBlockSize,
                    "the real allocation unit is larger than what Java reports: measured="
                            + measuredCluster + " reported=" + reportedBlockSize);
        }

        @Test
        @DisplayName("small pack files consume a whole cluster each: allocation dwarfs logical size")
        void allocatedSpaceIsFarLargerThanTheLogicalSize() throws Exception {
            // C6b established, on NTFS, that 440 logical bytes are written for a 380-byte estimate.
            // On FAT32 the question is different: how much space is actually taken from the volume.
            // Every file rounds up to a full cluster, and directories cost clusters of their own.
            //
            // Read the ratio below for what it is. This fixture is deliberately tiny — five files of
            // a few hundred bytes — so the rounding dominates completely, and the figure says nothing
            // about a real pack, whose assets are far larger and whose ratio would be much closer to
            // one. What survives generalisation is narrower and is the actual point of this test:
            // the free-space preflight compares source bytes against free bytes, and therefore does
            // not upper-bound the space a transfer will actually allocate on FAT32.
            long cluster = measureClusterSize();
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();

            long before = usableSpace();
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();
            long consumed = before - usableSpace();

            Path contentFolder = partition.resolve(".content")
                    .resolve(driver.computePackFolderName(PACK_B.toString()));
            long logical = totalFileBytes(contentFolder);

            System.out.println("[F5] files=" + fileNames(contentFolder)
                    + " logicalBytes=" + logical
                    + " consumedBytes=" + consumed
                    + " measuredCluster=" + cluster
                    + " clusters=" + (consumed / cluster)
                    + " ratio=" + (logical == 0 ? 0 : (double) consumed / logical));

            assertEquals(440, logical, "same logical payload as measured on NTFS in C6b");
            assertEquals(0, consumed % cluster, "allocation moves in whole clusters");
            assertTrue(consumed >= 5 * cluster,
                    "the five pack files alone cost five clusters, consumed=" + consumed);
            assertTrue(consumed >= logical * 10,
                    "allocation dwarfs the logical size: consumed=" + consumed + " logical=" + logical);
        }

        @Test
        @DisplayName("a 16-byte index costs a whole cluster")
        void theIndexCostsAWholeCluster() throws Exception {
            long cluster = measureClusterSize();
            long before = usableSpace();

            writeIndexFile(PACK_A);   // 16 logical bytes

            long consumed = before - usableSpace();
            System.out.println("[F5] index of 16 logical bytes consumed " + consumed
                    + " bytes (cluster=" + cluster + ")");

            assertEquals(16, indexBytes().length);
            assertEquals(cluster, consumed, "a 16-byte index takes exactly one cluster");
        }
    }

    /* ================================================================== F7 / F8 */

    @Nested
    @DisplayName("F7 / F8 — upload failure and retry on FAT32")
    class F7F8UploadFailureAndRetry {

        @Test
        @DisplayName("F7: a failed copy leaves an orphan content folder and does not touch the index")
        void aFailedCopyLeavesAnOrphanContentFolder() throws Exception {
            writeIndexFile(PACK_A);
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = partition.resolve(".content")
                    .resolve(driver.computePackFolderName(PACK_B.toString()));
            Files.createDirectories(contentFolder);
            Files.write(contentFolder.resolve("ni"), filler(7, 'X'));

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertTrue(hasCause(raised, FileAlreadyExistsException.class));
            assertTrue(Files.isDirectory(contentFolder), "the content folder is left behind");
            assertEquals(List.of(PACK_A), readPackIndex(driver), "the index is untouched");
        }

        @Test
        @DisplayName("F8: retrying the same pack fails on the clear-text files, folder left incomplete")
        void retryingOverAnExistingFolderFails() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();
            Path contentFolder = partition.resolve(".content")
                    .resolve(driver.computePackFolderName(PACK_B.toString()));

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertTrue(hasCause(raised, FileAlreadyExistsException.class));
            assertEquals(List.of(PACK_B), readPackIndex(driver), "no duplicate index entry");
            assertTrue(Files.exists(contentFolder.resolve("bt")),
                    "bt survives from the first, successful upload");
        }

        @Test
        @DisplayName("a nominal upload then delete round-trips cleanly on FAT32")
        void nominalUploadThenDeleteRoundTrips() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();

            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();
            Path contentFolder = partition.resolve(".content")
                    .resolve(driver.computePackFolderName(PACK_B.toString()));
            assertEquals(List.of("bt", "li", "ni", "ri", "si"), fileNames(contentFolder));
            assertEquals(List.of(PACK_B), readPackIndex(driver));

            assertTrue(driver.deletePack(PACK_B.toString()).join());

            assertEquals(List.of(), readPackIndex(driver));
            assertFalse(Files.exists(contentFolder));
        }
    }
}
