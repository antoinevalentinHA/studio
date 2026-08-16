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
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An upload owns the content folder it creates, or it owns nothing at all.
 *
 * <p>These are <strong>specification</strong> tests. They state what {@code uploadPack} must do when
 * {@code .content/<folder>} already exists on the partition, which is the same rule
 * {@link TemporaryFilePackIndexWriter} already applies to {@code .pi.new}: a resource that was not
 * created by this operation is not ours, so it is neither written into, nor merged with, nor
 * removed. The destination folder is claimed by an exclusive creation, and that creation is the
 * single place where the question "is this ours?" gets answered.
 *
 * <h2>What was wrong before</h2>
 *
 * <p>{@code destFolder.mkdirs()} ignored its own return value, so nothing distinguished a folder the
 * upload had just created from one that was already there. What happened next depended on which
 * files the pre-existing folder happened to contain, and none of the three outcomes was acceptable:
 *
 * <ul>
 *   <li>a pre-existing {@code ni} or {@code nm} made {@code Files.copy} throw
 *       {@code FileAlreadyExistsException} — but only <em>after</em> the ciphered assets the walk had
 *       already reached were overwritten by {@code Files.write}, which truncates;</li>
 *   <li>a pre-existing folder <em>without</em> {@code ni} let the whole copy run to completion. Files
 *       the new pack does not have were left in place, {@code bt} was regenerated, and the resulting
 *       mixture of two packs was appended to {@code .pi} as a valid entry. This is the case
 *       {@link SilentMerge} pins, and it used to report success;</li>
 *   <li>either way, nothing said which folder was in the way, nor that the destination existed.</li>
 * </ul>
 *
 * <h2>What this does not do</h2>
 *
 * <p>Nothing here cleans up, resumes, retries or repairs. A pre-existing folder stays exactly where
 * it is, byte for byte, and the operator is told about it. Whether it is the residue of an
 * interrupted upload, of a delete that could not remove it, of another tool or of a hand edit is not
 * knowable from the filesystem, and this deliberately does not guess.
 */
class UploadDestinationOwnershipTest {

    /** Stands in for the device partition root. */
    @TempDir
    Path partition;

    /** Stands in for the local library, i.e. the source of an upload. */
    @TempDir
    Path library;

    private static final UUID PACK_A = UUID.fromString("11111111-2222-3333-4444-5555aaaabbbb");
    private static final UUID PACK_B = UUID.fromString("66666666-7777-8888-9999-aaaaccccdddd");

    /** A file name no source pack carries, so its survival can only mean "left untouched". */
    private static final String SENTINEL = "sentinel-not-from-any-pack";

    // ------------------------------------------------------------------ fixtures

    /** Metadata the driver reads as a V3 device (format version 7), as in the C6b fixtures. */
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

    private void writeIndexFile(UUID... uuids) throws IOException {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        Files.write(partition.resolve(".pi"), buffer.array());
    }

    private byte[] indexBytes() throws IOException {
        return Files.readAllBytes(partition.resolve(".pi"));
    }

    /** The same minimal source pack the write-path characterization uses. */
    private Path sourcePack() throws IOException {
        Path pack = library.resolve("source-pack");
        Files.createDirectories(pack);
        Files.write(pack.resolve(".cleartext"), new byte[0]);
        Files.write(pack.resolve("ni"), filler(200, 'n'));   // clear, copied verbatim
        Files.write(pack.resolve("ri"), filler(100, 'r'));   // ciphered, written with truncation
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

    private Path contentFolderFor(FsStoryTellerAsyncDriver driver, UUID uuid) {
        return partition.resolve(".content").resolve(driver.computePackFolderName(uuid.toString()));
    }

    private static List<String> fileNames(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<String> names = new ArrayList<>();
            paths.filter(Files::isRegularFile).forEach(p -> names.add(root.relativize(p).toString().replace('\\', '/')));
            names.sort(Comparator.naturalOrder());
            return names;
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

    private static String messageChain(Throwable thrown) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            chain.append(current.getMessage()).append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return chain.toString();
    }

    @BeforeEach
    void prepareDevice() throws IOException {
        writeDeviceMetadataV7();
    }

    /* ================================================================== D1 */

    /**
     * The case that used to <em>succeed</em>. A destination folder that does not contain {@code ni}
     * offers nothing for {@code Files.copy} to collide with, so the whole copy ran, the leftovers
     * stayed, and the mixture was indexed as a pack.
     */
    @Nested
    @DisplayName("D1 — a pre-existing destination is never merged into")
    class SilentMerge {

        @Test
        @DisplayName("an upload into a pre-existing folder is refused instead of merging into it")
        void refusesToMergeIntoAPreexistingFolder() throws Exception {
            writeIndexFile(PACK_A);
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = contentFolderFor(driver, PACK_B);

            // A folder that predates the upload: a sentinel no pack carries, plus a witness on a name
            // the source pack DOES carry and would write through. Deliberately no `ni`, which is what
            // used to let the copy run to the end.
            Files.createDirectories(contentFolder);
            Files.write(contentFolder.resolve(SENTINEL), filler(23, 'S'));
            Files.write(contentFolder.resolve("ri"), filler(41, 'W'));

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertNotNull(raised.getCause(), "the refusal must carry a cause");
            assertArrayEquals(filler(23, 'S'), Files.readAllBytes(contentFolder.resolve(SENTINEL)),
                    "the sentinel must be byte-identical: nothing in that folder is ours to touch");
            assertArrayEquals(filler(41, 'W'), Files.readAllBytes(contentFolder.resolve("ri")),
                    "the witness must be byte-identical: `ri` is written with truncation, so this is"
                            + " exactly the file the old code destroyed before deciding anything");
        }

        @Test
        @DisplayName("no file of the uploaded pack is added to the pre-existing folder")
        void addsNoFileOfTheUploadedPack() throws Exception {
            writeIndexFile(PACK_A);
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = contentFolderFor(driver, PACK_B);
            Files.createDirectories(contentFolder);
            Files.write(contentFolder.resolve(SENTINEL), filler(23, 'S'));
            Files.write(contentFolder.resolve("ri"), filler(41, 'W'));

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertEquals(List.of("ri", SENTINEL), fileNames(contentFolder),
                    "the folder must hold exactly what it held before: no ni, no si, no li, and no bt");
        }

        @Test
        @DisplayName("the index is byte-identical: the refused pack is not registered")
        void leavesTheIndexByteIdentical() throws Exception {
            writeIndexFile(PACK_A);
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = contentFolderFor(driver, PACK_B);
            Files.createDirectories(contentFolder);
            Files.write(contentFolder.resolve(SENTINEL), filler(23, 'S'));
            byte[] before = indexBytes();

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertArrayEquals(before, indexBytes(), "the index must not gain the refused pack");
            assertEquals(List.of(PACK_A), readPackIndex(driver));
            assertFalse(Files.exists(partition.resolve(".pi.new")), "and no index temporary was created");
        }

        @Test
        @DisplayName("the pre-existing folder is still there: refusing is not deleting")
        void leavesThePreexistingFolderInPlace() throws Exception {
            writeIndexFile(PACK_A);
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = contentFolderFor(driver, PACK_B);
            Files.createDirectories(contentFolder);
            Files.write(contentFolder.resolve(SENTINEL), filler(23, 'S'));

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertTrue(Files.isDirectory(contentFolder),
                    "the folder is left exactly as found — this lot refuses, it does not clean up");
        }

        @Test
        @DisplayName("an empty pre-existing folder is refused just the same")
        void refusesAnEmptyPreexistingFolder() throws Exception {
            // Emptiness is not evidence of ownership. A folder created by an upload that died before
            // writing anything looks exactly like a folder somebody made by hand.
            writeIndexFile(PACK_A);
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = contentFolderFor(driver, PACK_B);
            Files.createDirectories(contentFolder);

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertEquals(List.of(), fileNames(contentFolder), "still empty: nothing was written into it");
            assertEquals(List.of(PACK_A), readPackIndex(driver));
        }
    }

    /* ================================================================== D2 */

    @Nested
    @DisplayName("D2 — the refusal is explicit enough to act on")
    class ExplicitRefusal {

        @Test
        @DisplayName("the refusal names the folder and says it was not created by this operation")
        void namesTheFolderAndDisclaimsOwnership() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = contentFolderFor(driver, PACK_B);
            Files.createDirectories(contentFolder);

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            String message = messageChain(raised);
            assertTrue(message.contains(contentFolder.toString()),
                    "the message must name the folder that is in the way, got:\n" + message);
            assertTrue(message.contains("already exists"),
                    "the message must say the destination already exists, got:\n" + message);
            assertTrue(message.contains("not created by this operation"),
                    "the message must disclaim ownership, got:\n" + message);
        }

        @Test
        @DisplayName("the refusal keeps the filesystem cause rather than inventing one")
        void keepsTheFilesystemCause() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Files.createDirectories(contentFolderFor(driver, PACK_B));

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertTrue(hasCause(raised, FileAlreadyExistsException.class),
                    "the exclusive creation's own failure must survive in the chain, got: " + raised);
        }

        @Test
        @DisplayName("the refusal arrives as a failed future, like every other driver refusal")
        void arrivesAsAFailedFuture() throws Exception {
            // uploadPack is asked for a CompletableFuture, and the callers in StoryTellerService use
            // whenComplete to report the transfer. A refusal thrown synchronously would bypass that
            // path, so it is delivered the same way the "no device plugged" refusals already are.
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Files.createDirectories(contentFolderFor(driver, PACK_B));

            CompletableFuture<?> future = driver.uploadPack(PACK_B.toString(), source.toString(), null);

            assertTrue(future.isCompletedExceptionally(), "the future itself must carry the refusal");
            assertThrows(CompletionException.class, future::join);
        }

        @Test
        @DisplayName("a destination name taken by a FILE is refused too, not written through")
        void refusesADestinationTakenByAFile() throws Exception {
            // Same question, same answer: the name is taken by something this operation did not
            // create, and what it is does not change who owns it.
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Path contentFolder = contentFolderFor(driver, PACK_B);
            Files.createDirectories(contentFolder.getParent());
            Files.write(contentFolder, filler(9, 'F'));

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());

            assertArrayEquals(filler(9, 'F'), Files.readAllBytes(contentFolder),
                    "the file that holds the name is left exactly as found");
        }
    }

    /* ================================================================== D3 */

    /**
     * The nominal path, which is the whole point of keeping the guard on the leaf folder only:
     * {@code .content} itself is shared by every pack and its existence is entirely normal.
     */
    @Nested
    @DisplayName("D3 — an absent destination still uploads exactly as before")
    class NominalUploadUnchanged {

        @Test
        @DisplayName("a first upload onto a partition with no .content folder at all still works")
        void uploadsWhenTheContentFolderDoesNotExistYet() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            assertFalse(Files.exists(partition.resolve(".content")), "precondition: nothing there yet");

            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();

            Path contentFolder = contentFolderFor(driver, PACK_B);
            assertEquals(List.of("bt", "li", "ni", "ri", "si"), fileNames(contentFolder),
                    "the transfer is untouched: `.cleartext` is dropped and `bt` is generated");
            assertEquals(List.of(PACK_B), readPackIndex(driver), "and the pack is appended to the index");
        }

        @Test
        @DisplayName("uploading a second pack alongside an existing one still works")
        void uploadsWhenContentAlreadyHoldsOtherPacks() throws Exception {
            // `.content` exists and is not empty, but the destination LEAF does not exist. That must
            // stay allowed, otherwise the guard would refuse every upload after the first.
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            driver.uploadPack(PACK_A.toString(), source.toString(), null).join();

            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();

            assertEquals(List.of("bt", "li", "ni", "ri", "si"), fileNames(contentFolderFor(driver, PACK_B)));
            assertEquals(List.of(PACK_A, PACK_B), readPackIndex(driver));
        }

        @Test
        @DisplayName("an upload that is refused does not stop a later, legitimate one")
        void aRefusalDoesNotPoisonSubsequentUploads() throws Exception {
            writeIndexFile();
            Path source = sourcePack();
            FsStoryTellerAsyncDriver driver = driver();
            Files.createDirectories(contentFolderFor(driver, PACK_A));

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_A.toString(), source.toString(), null).join());
            driver.uploadPack(PACK_B.toString(), source.toString(), null).join();

            assertEquals(List.of(PACK_B), readPackIndex(driver), "only the legitimate upload is indexed");
        }
    }
}
