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
import studio.driver.StoryTellerException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which folders sit under {@code .content} without any entry of {@code .pi} referring to them.
 *
 * <p><strong>Specifications.</strong> The invariant is deliberately narrow: <em>a folder under
 * {@code .content} that the index does not reference must be observable without ever being
 * modified</em>. Observable — not identified, not attributed, not resolved.
 *
 * <h2>What "unreferenced" does and does not mean</h2>
 *
 * <p>It means exactly one fact: a directory exists under {@code .content}, and no current entry of
 * {@code .pi} maps to its name. It does <strong>not</strong> mean the folder is a leftover of
 * STUdio, that it is safe to delete, that it can be recovered, or that anyone knows where it came
 * from. It could be the residue of an interrupted upload, of a delete that could not finish, of
 * another tool, or of a hand edit — and nothing on the card records which.
 *
 * <p>The folder name is the only thing reported. A {@code .content} folder is named after the
 * <em>last eight hex characters</em> of a pack UUID, so the other twenty-four are not on the card at
 * all: <strong>no full UUID is ever reconstructed from a folder name</strong>. The comparison only
 * ever runs the other way, from an index entry to the name it implies.
 *
 * <p>This observes and nothing else. It deletes nothing, renames nothing, quarantines nothing, and
 * exposes nothing to the user; whether and how to surface it is a separate decision.
 */
class UnreferencedPackFolderTest {

    @TempDir
    Path partition;

    private static final UUID PACK_A = UUID.fromString("11111111-2222-3333-4444-5555aaaabbbb");
    private static final UUID PACK_B = UUID.fromString("66666666-7777-8888-9999-aaaaccccdddd");
    private static final UUID PACK_C = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffff11112222");

    // ------------------------------------------------------------------ fixtures

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

    private Path contentRoot() {
        return partition.resolve(".content");
    }

    /** A folder named the way the driver names one, holding a token file so it is not empty. */
    private Path packFolder(FsStoryTellerAsyncDriver driver, UUID uuid) throws IOException {
        return folderNamed(driver.computePackFolderName(uuid.toString()));
    }

    private Path folderNamed(String name) throws IOException {
        Path folder = contentRoot().resolve(name);
        Files.createDirectories(folder);
        Files.write(folder.resolve("ni"), ("content of " + name).getBytes(StandardCharsets.US_ASCII));
        return folder;
    }

    private FsStoryTellerAsyncDriver driver() {
        return DriverTestSupport.pluggedDriverMountedOn(partition);
    }

    private List<String> unreferenced(FsStoryTellerAsyncDriver driver) {
        return driver.getUnreferencedPackContentFolders().join();
    }

    /** Every regular file under a root with its bytes, so a read-only claim can be checked. */
    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (!Files.exists(root)) {
            return files;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.filter(Files::isRegularFile).sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        }
        for (Path path : paths) {
            files.put(relative(root, path), Files.readAllBytes(path));
        }
        return files;
    }

    private static List<String> treeOf(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.map(p -> relative(root, p)).sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static List<UUID> listedUuids(FsStoryTellerAsyncDriver driver) {
        return driver.getPacksList().join().stream()
                .map(studio.driver.model.fs.FsStoryPackInfos::getUuid)
                .collect(Collectors.toList());
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

    // ------------------------------------------------------------------ U1

    @Nested
    @DisplayName("U1 — a folder the index does not reference is reported")
    class SingleUnreferencedFolder {

        @Test
        @DisplayName("exactly the unreferenced folder is reported, by name")
        void reportsTheUnreferencedFolder() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A, PACK_B);
            packFolder(driver, PACK_A);
            packFolder(driver, PACK_B);
            folderNamed("CCCCCCCC");

            assertEquals(List.of("CCCCCCCC"), unreferenced(driver));
        }

        @Test
        @DisplayName("an index entry whose folder is missing produces no report")
        void aMissingFolderIsNotReported() throws IOException {
            // The mirror case, and a different question: this answers "which folders have no index
            // entry", never "which entries have no folder".
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A, PACK_B);
            packFolder(driver, PACK_A);

            assertEquals(List.of(), unreferenced(driver));
        }
    }

    // ------------------------------------------------------------------ U2

    @Nested
    @DisplayName("U2 — several folders, in a stable order")
    class DeterministicOrder {

        @Test
        @DisplayName("the result is sorted by name, whatever order the filesystem returns")
        void ordersByName() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A);
            packFolder(driver, PACK_A);
            folderNamed("EEEEEEEE");
            folderNamed("11111111");
            folderNamed("CCCCCCCC");

            assertEquals(List.of("11111111", "CCCCCCCC", "EEEEEEEE"), unreferenced(driver));
        }

        @Test
        @DisplayName("the same partition read twice gives the same answer")
        void isRepeatable() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A);
            packFolder(driver, PACK_A);
            folderNamed("CCCCCCCC");
            folderNamed("DDDDDDDD");

            assertEquals(unreferenced(driver), unreferenced(driver));
        }
    }

    // ------------------------------------------------------------------ U3 / U4

    @Nested
    @DisplayName("U3 / U4 — nothing to report")
    class NothingToReport {

        @Test
        @DisplayName("a partition whose folders all match the index reports nothing")
        void everythingReferenced() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A, PACK_B, PACK_C);
            packFolder(driver, PACK_A);
            packFolder(driver, PACK_B);
            packFolder(driver, PACK_C);

            assertEquals(List.of(), unreferenced(driver));
        }

        @Test
        @DisplayName("an absent .content reports nothing, raises nothing, and is not created")
        void absentContentFolder() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A);

            assertFalse(Files.exists(contentRoot()), "the fixture has no .content at all");
            assertEquals(List.of(), unreferenced(driver));
            assertFalse(Files.exists(contentRoot()), "and asking must not create it");
        }

        @Test
        @DisplayName("an empty .content reports nothing")
        void emptyContentFolder() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A);
            Files.createDirectories(contentRoot());

            assertEquals(List.of(), unreferenced(driver));
        }

        @Test
        @DisplayName("an empty index makes every folder present unreferenced")
        void emptyIndex() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile();
            folderNamed("CCCCCCCC");

            assertEquals(List.of("CCCCCCCC"), unreferenced(driver));
        }
    }

    // ------------------------------------------------------------------ U5

    @Nested
    @DisplayName("U5 — asking changes nothing")
    class ReadOnly {

        @Test
        @DisplayName("the index, every file and the whole tree are untouched")
        void nothingIsModified() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A, PACK_B);
            packFolder(driver, PACK_A);
            packFolder(driver, PACK_B);
            folderNamed("CCCCCCCC");

            byte[] indexBefore = Files.readAllBytes(partition.resolve(".pi"));
            Map<String, byte[]> filesBefore = snapshot(partition);
            List<String> treeBefore = treeOf(partition);

            assertEquals(List.of("CCCCCCCC"), unreferenced(driver));

            assertArrayEquals(indexBefore, Files.readAllBytes(partition.resolve(".pi")),
                    "the index must be byte-identical");
            assertEquals(treeBefore, treeOf(partition),
                    "nothing may be created or removed anywhere on the partition");
            Map<String, byte[]> filesAfter = snapshot(partition);
            assertEquals(filesBefore.keySet(), filesAfter.keySet());
            for (Map.Entry<String, byte[]> entry : filesBefore.entrySet()) {
                assertArrayEquals(entry.getValue(), filesAfter.get(entry.getKey()),
                        entry.getKey() + " must be byte-identical");
            }
        }

        @Test
        @DisplayName("getPacksList answers exactly as before, and still ignores the folder")
        void theListingIsUnaffected() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A, PACK_B);
            packFolder(driver, PACK_A);
            packFolder(driver, PACK_B);
            folderNamed("CCCCCCCC");

            List<UUID> listedBefore = listedUuids(driver);

            unreferenced(driver);

            assertEquals(List.of(PACK_A, PACK_B), listedBefore);
            assertEquals(listedBefore, listedUuids(driver), "the listing must not change");
        }
    }

    // ------------------------------------------------------------------ U6

    @Nested
    @DisplayName("U6 — what is reported is a filesystem fact, not a pack identity")
    class StructuralOnly {

        @Test
        @DisplayName("a folder whose name is not a pack folder name is reported all the same")
        void anUnexpectedNameIsStillReported() throws IOException {
            // `.content` holds nothing but pack folders — `rf` and `sf` live inside one, never beside
            // it — so a directory with any other name is, structurally, unreferenced. Reporting it
            // claims nothing about what it is; hiding it would be a judgement that cannot be made
            // from here.
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A);
            packFolder(driver, PACK_A);
            folderNamed("not-a-pack");

            assertEquals(List.of("not-a-pack"), unreferenced(driver));
        }

        @Test
        @DisplayName("a folder matching an index entry in another case is NOT reported")
        void caseDoesNotCreateAFalsePositive() throws IOException {
            // The card is FAT32, which does not distinguish case, and the driver opens a pack folder
            // by the upper-case name the UUID implies. A folder differing only in case is the very
            // one the driver would open, so calling it unreferenced would be a false positive — the
            // worst error available here, since it would point at a pack that is perfectly in use.
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A);
            String expected = driver.computePackFolderName(PACK_A.toString());
            folderNamed(expected.toLowerCase(Locale.ROOT));

            assertEquals(List.of(), unreferenced(driver));
        }

        @Test
        @DisplayName("a plain file directly under .content is not reported as a folder")
        void filesUnderContentAreIgnored() throws IOException {
            // The question is about pack content folders. A stray file is a different observation,
            // and no meaning is invented for it here.
            FsStoryTellerAsyncDriver driver = driver();
            writeIndexFile(PACK_A);
            packFolder(driver, PACK_A);
            Files.write(contentRoot().resolve("stray.txt"), new byte[]{1, 2, 3});

            assertEquals(List.of(), unreferenced(driver));
        }
    }

    // ------------------------------------------------------------------ errors

    @Nested
    @DisplayName("A failure is reported, never turned into an empty answer")
    class ErrorSemantics {

        @Test
        @DisplayName("a structurally invalid .pi fails, as it does for a normal listing")
        void anInvalidIndexFails() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            Files.write(partition.resolve(".pi"), new byte[]{1, 2, 3});   // not a multiple of 16
            folderNamed("CCCCCCCC");

            Throwable raised = assertThrows(Throwable.class, () -> unreferenced(driver));

            assertTrue(hasCause(raised, StoryTellerException.class),
                    "an unreadable index must surface, not be reported as 'nothing unreferenced', got: "
                            + raised);
        }

        @Test
        @DisplayName("a missing .pi fails rather than reporting every folder as unreferenced")
        void anAbsentIndexFails() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            folderNamed("CCCCCCCC");

            Throwable raised = assertThrows(Throwable.class, () -> unreferenced(driver));

            assertTrue(hasCause(raised, StoryTellerException.class), "got: " + raised);
        }

        @Test
        @DisplayName("with no device plugged the call is refused like every other driver call")
        void noDevicePlugged() throws IOException {
            FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);
            writeIndexFile(PACK_A);

            Throwable raised = assertThrows(Throwable.class, () -> unreferenced(driver));

            assertTrue(hasCause(raised, StoryTellerException.class), "got: " + raised);
        }
    }
}
