/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Where a library operation may reach, and where it may not.
 *
 * <p>These were characterization tests, and they recorded something that should never have been
 * true: a name arriving over HTTP was concatenated onto the library folder and handed straight to
 * the filesystem, so {@code ../} left the library and a file outside it could be read, overwritten
 * or deleted. Those assertions have been inverted onto the same fixtures — the same sentinel, the
 * same temporary library — so what once documented the hole now states the rule.
 *
 * <p>The rule is one sentence: <strong>a library entry is a direct child of the library folder</strong>.
 * Not a nested path, not an absolute one, not something reached by climbing out and back in, and not
 * a link pointing elsewhere. Anything else is refused before a single filesystem call, and never
 * quietly rewritten into something acceptable — a caller asking for the wrong thing is told no, not
 * given a different thing.
 *
 * <p>Refusals alone would be a poor test of a confinement: a method that rejected everything would
 * pass them all. The nominal cases carry equal weight here for that reason.
 *
 * <p>Nothing outside a temporary directory is touched. The sentinel sits beside the temporary
 * library — not in the user's real one, not in the repository, nowhere on the system.
 */
class LibraryPathConfinementTest {

    private static final String SENTINEL = "outside-sentinel.txt";
    private static final String SENTINEL_CONTENT = "a file the library has no business touching";

    @TempDir
    Path root;

    private Path library;
    private Path sentinel;
    private Path conversionWorkFolder;
    private LibraryService service;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("db"));

        // One step outside the library, still inside the temporary directory.
        sentinel = root.resolve(SENTINEL);
        Files.write(sentinel, SENTINEL_CONTENT.getBytes(StandardCharsets.UTF_8));

        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        // The conversion work folder deliberately lives outside @TempDir. A conversion that fails
        // part-way leaves its FileInputStream and FileOutputStream open — they are closed on the
        // nominal path only — and on Windows those handles block the temporary directory's cleanup.
        // That leak is real and is not this lot's to fix; keeping the folder elsewhere stops it
        // failing tests that are about something else.
        conversionWorkFolder = Files.createTempDirectory("studio-conversion-work");
        conversionWorkFolder.toFile().deleteOnExit();
        setProperty(LibraryService.TMP_DIR_PROP, conversionWorkFolder + File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP,
                root.resolve("db").resolve("conversions.json").toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP,
                root.resolve("db").resolve("unofficial.json").toString());

        service = new LibraryService(new DatabaseMetadataService(true));
    }

    @AfterEach
    void tearDown() {
        previousProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Nested
    @DisplayName("nothing leaves the library")
    class Confinement {

        @Test
        @DisplayName("a traversal is refused by every operation, and the file outside is untouched")
        void traversalIsRefusedEverywhere() throws IOException {
            Path uploaded = writeTemporary("uploaded.zip", "uploaded content");

            assertFalse(service.getRawPackFile("../" + SENTINEL).isPresent(),
                    "download must not hand back a file outside the library");
            assertFalse(service.addPackFile("../" + SENTINEL, uploaded.toString()),
                    "upload must not write outside the library");
            assertFalse(service.deletePack("../" + SENTINEL),
                    "removal must not delete outside the library");

            assertSentinelIntact();
        }

        @Test
        @DisplayName("an absolute path is refused, not resolved")
        void absolutePathIsRefused() throws IOException {
            Path uploaded = writeTemporary("uploaded.zip", "uploaded content");

            assertFalse(service.getRawPackFile(sentinel.toString()).isPresent());
            assertFalse(service.addPackFile(sentinel.toString(), uploaded.toString()));
            assertFalse(service.deletePack(sentinel.toString()));

            assertSentinelIntact();
        }

        @Test
        @DisplayName("a nested path is refused: the library is one folder deep")
        void nestedPathIsRefused() throws IOException {
            Path nested = Files.createDirectories(library.resolve("sub"));
            Files.write(nested.resolve("pack.zip"), "nested".getBytes(StandardCharsets.UTF_8));

            // The listing walks the library one level deep, so nothing below that is a library
            // entry. Accepting one would widen the surface for no functional gain.
            assertFalse(service.getRawPackFile("sub/pack.zip").isPresent());
            assertFalse(service.deletePack("sub/pack.zip"));
            assertTrue(Files.exists(nested.resolve("pack.zip")), "the nested file is left alone");
        }

        @Test
        @DisplayName("degenerate names are refused rather than resolved to the library folder itself")
        void degenerateNamesAreRefused() {
            for (String name : new String[]{null, "", "   ", ".", ".."}) {
                assertFalse(service.getRawPackFile(name).isPresent(), "refused: " + name);
                assertFalse(service.deletePack(name), "refused: " + name);
            }
            assertTrue(Files.isDirectory(library), "the library folder itself is never the target");
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("a backslash traversal is refused too, since Windows reads it as a separator")
        void backslashTraversalIsRefusedOnWindows() {
            assertFalse(service.deletePack("..\\" + SENTINEL));

            assertSentinelIntact();
        }
    }

    @Nested
    @DisplayName("a library entry is not a way out")
    class Links {

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("a symbolic link inside the library is refused rather than followed")
        void symbolicLinkEntryIsRefused() throws IOException {
            Files.createSymbolicLink(library.resolve("link.txt"), sentinel);

            // Lexically this is a direct child, so containment alone would let it through and the
            // operation would act on whatever it points at.
            assertFalse(service.getRawPackFile("link.txt").isPresent());
            assertFalse(service.deletePack("link.txt"));

            assertSentinelIntact();
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("an NTFS junction inside the library is refused rather than followed")
        void junctionEntryIsRefused() throws Exception {
            Path outsideFolder = Files.createDirectories(root.resolve("outside-folder"));
            Path inside = outsideFolder.resolve("kept.txt");
            Files.write(inside, "kept".getBytes(StandardCharsets.UTF_8));
            Path junction = library.resolve("link");

            Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    junction.toString(), outsideFolder.toString()).redirectErrorStream(true).start();
            assumeTrue(mklink.waitFor() == 0, "could not create a junction on this machine");

            try {
                // A junction reports isSymbolicLink() as false — measured in C7-2b — so checking for
                // links alone would miss it, and a recursive delete would go through it.
                assertFalse(service.deletePack("link"));
                assertTrue(Files.exists(inside), "the folder outside the library is untouched");
            } finally {
                Files.deleteIfExists(junction);
            }
        }
    }

    @Nested
    @DisplayName("the ordinary paths still work")
    class Nominal {

        @Test
        @DisplayName("a direct child is downloaded")
        void directChildIsDownloaded() throws IOException {
            Files.write(library.resolve("pack.zip"), "content".getBytes(StandardCharsets.UTF_8));

            Optional<File> file = service.getRawPackFile("pack.zip");

            assertTrue(file.isPresent());
            assertEquals(library.resolve("pack.zip").toFile().getAbsolutePath(),
                    file.get().getAbsolutePath());
        }

        @Test
        @DisplayName("a direct child is uploaded, and an existing one is replaced")
        void directChildIsUploaded() throws IOException {
            Path uploaded = writeTemporary("uploaded.zip", "first");

            assertTrue(service.addPackFile("pack.zip", uploaded.toString()));
            assertEquals("first", read(library.resolve("pack.zip")));

            // Replacing a pack in place is a designed flow; the confinement must not change it.
            Path second = writeTemporary("second.zip", "second");
            assertTrue(service.addPackFile("pack.zip", second.toString()));
            assertEquals("second", read(library.resolve("pack.zip")));
        }

        @Test
        @DisplayName("a direct child file is removed")
        void directChildFileIsRemoved() throws IOException {
            Files.write(library.resolve("pack.zip"), "content".getBytes(StandardCharsets.UTF_8));

            assertTrue(service.deletePack("pack.zip"));
            assertFalse(Files.exists(library.resolve("pack.zip")));
        }

        @Test
        @DisplayName("a direct child folder is removed, since a pack can be one")
        void directChildFolderIsRemoved() throws IOException {
            Path folder = Files.createDirectories(library.resolve("pack.folder"));
            Files.write(folder.resolve("ni"), "index".getBytes(StandardCharsets.UTF_8));

            assertTrue(service.deletePack("pack.folder"));
            assertFalse(Files.exists(folder));
        }

        @Test
        @DisplayName("removing something that is not there fails without touching anything")
        void missingEntryIsNotRemoved() {
            assertFalse(service.deletePack("never-existed.zip"));

            assertSentinelIntact();
        }
    }

    @Nested
    @DisplayName("a conversion is named after a real UUID")
    class ConversionNaming {

        @Test
        @DisplayName("a pack whose identifier is a traversal is refused, and nothing is written outside")
        void traversalUuidIsRefused() throws Exception {
            writePack("pack.zip", "../escaped");

            assertTrue(throwsRuntime(() -> service.addConvertedRawPackFile("pack.zip", false)),
                    "the conversion must fail rather than compose a path from that identifier");

            assertFalse(Files.exists(root.resolve("escaped.converted_0.pack")), "nothing outside");
            assertEquals(1, countLibraryEntries(), "and nothing new inside either");
        }

        @Test
        @DisplayName("a pack whose identifier is an absolute path is refused")
        void absoluteUuidIsRefused() throws Exception {
            writePack("pack.zip", root.resolve("absolute").toString());

            // Through a conversion that really reads it: archive to archive is refused for an
            // unrelated reason and would prove nothing.
            assertTrue(throwsRuntime(() -> service.addConvertedFsPackFile("pack.zip", false)));
            assertEquals(1, countLibraryEntries());
        }

        @Test
        @DisplayName("a pack whose identifier is simply not a UUID is refused")
        void nonUuidIsRefused() throws Exception {
            // Checked by parsing, not by hunting for separators: a blacklist only refuses what it
            // was told to imagine.
            writePack("pack.zip", "not-a-uuid-at-all");

            assertTrue(throwsRuntime(() -> service.addConvertedRawPackFile("pack.zip", false)));
            assertEquals(1, countLibraryEntries());
        }

        @Test
        @DisplayName("a real UUID converts normally, into the library")
        void realUuidConverts() throws Exception {
            writePack("pack.zip", "11111111-2222-3333-4444-555555555555");

            Optional<Path> converted = service.addConvertedRawPackFile("pack.zip", false);

            assertTrue(converted.isPresent(), "the ordinary case must keep working");
            Path artifact = library.resolve(converted.get());
            assertTrue(Files.exists(artifact), "the artefact is installed in the library");
            assertEquals(library.toAbsolutePath().normalize(),
                    artifact.toAbsolutePath().normalize().getParent(),
                    "and it is a direct child of it");
            assertSentinelIntact();
        }
    }

    @Nested
    @DisplayName("the endpoint added by C7-2d")
    class VerifyConversion {

        @Test
        @DisplayName("still refuses everything that is not a library entry")
        void stillRefusesNonEntries() {
            for (String name : new String[]{"../" + SENTINEL, "sub/nested.zip", "", null, ".."}) {
                assertTrue(throwsIllegalArgument(() -> service.verifyConversion(name, "x")),
                        "refused as source: " + name);
                assertTrue(throwsIllegalArgument(() -> service.verifyConversion("x", name)),
                        "refused as artefact: " + name);
            }
            assertTrue(throwsIllegalArgument(() -> service.verifyConversion(sentinel.toString(), "x")));

            assertSentinelIntact();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private Path writeTemporary(String name, String content) throws IOException {
        Path file = conversionWorkFolder.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** The whole point, asserted the same way every time: still there, still byte for byte itself. */
    private void assertSentinelIntact() {
        assertTrue(Files.exists(sentinel), "the file outside the library must still exist");
        try {
            assertArrayEquals(SENTINEL_CONTENT.getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(sentinel), "the file outside the library must be unchanged");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A minimal pack carrying the identifier under test, written into the library. */
    private void writePack(String name, String uuid) throws Exception {
        // The archive format carries no pack-level identifier: the reader rebuilds one from the
        // first stage node, so that is where the value under test has to go.
        studio.core.v1.model.StageNode node = new studio.core.v1.model.StageNode(
                uuid, null, null, null, null,
                new studio.core.v1.model.ControlSettings(false, false, false, false, false), null);
        studio.core.v1.model.StoryPack pack = new studio.core.v1.model.StoryPack(
                uuid, false, (short) 1, java.util.Collections.singletonList(node), null, false);
        try (java.io.OutputStream out = Files.newOutputStream(library.resolve(name))) {
            new studio.core.v1.writer.archive.ArchiveStoryPackWriter().write(pack, out);
        }
    }

    private long countLibraryEntries() throws IOException {
        try (Stream<Path> entries = Files.list(library)) {
            return entries.count();
        }
    }

    private static boolean throwsRuntime(Runnable call) {
        try {
            call.run();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static boolean throwsIllegalArgument(Runnable call) {
        try {
            call.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
