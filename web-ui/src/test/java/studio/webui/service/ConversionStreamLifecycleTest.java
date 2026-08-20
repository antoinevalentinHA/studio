/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a conversion lets go of its files when it goes wrong.
 *
 * <p>A conversion opens the source to read it and a temporary to write into. Both were closed on the
 * line after the work was done, which is fine right up until the reader or the writer throws — and
 * then neither close is reached and both handles stay open. That is not hypothetical: it surfaced
 * while writing the C9 confinement tests, where a deliberately refused conversion left its temporary
 * file locked and the temporary directory could not be cleaned up afterwards.
 *
 * <p>The test asserts the consequence rather than the mechanism. No counting of {@code close()}
 * calls, no mock stream: it makes a conversion fail for a real reason, then <strong>deletes the work
 * folder</strong>. On Windows an open handle makes that fail, which is the whole signal. One attempt
 * — no {@code System.gc()}, no sleep, no retry, since any of those would let the cleaner rescue the
 * defect and turn the test green while the leak was still there.
 *
 * <p>Windows-only for the deletion, and that is a statement about the platform rather than the code:
 * POSIX unlinks a file that is still open, so succeeding there would prove nothing. The handles leak
 * identically on both. What runs everywhere is the nominal case, which must keep working.
 */
class ConversionStreamLifecycleTest {

    private static final String PACK_UUID = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path root;

    private Path library;
    private Path workFolder;
    private LibraryService service;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = Files.createDirectories(root.resolve("library"));
        workFolder = Files.createDirectories(root.resolve("work"));
        Files.createDirectories(root.resolve("db"));

        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, workFolder + File.separator);
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

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a conversion whose writer throws still releases its temporary file")
    void failedWriteReleasesTheTemporary() throws Exception {
        // A pack the reader accepts and the writer refuses: the identifier is not a UUID, so
        // BinaryStoryPackWriter throws after the output stream has been opened — exactly the window
        // where the close used to be skipped.
        writeArchive("pack.zip", "not-a-uuid-at-all");

        assertThrows(RuntimeException.class, () -> service.addConvertedRawPackFile("pack.zip", false));

        assertWorkFolderCanBeEmptied();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a conversion whose reader throws still releases the source")
    void failedReadReleasesTheSource() throws Exception {
        // Half an archive: the reader fails part way through, before anything is written.
        writeTruncatedArchive("pack.zip");

        assertThrows(RuntimeException.class, () -> service.addConvertedFsPackFile("pack.zip", false));

        // The source is a library file rather than a work file, so this is what proves it was let go.
        Files.delete(library.resolve("pack.zip"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a conversion refused by the confinement leaves nothing open either")
    void refusedConversionReleasesEverything() throws Exception {
        writeArchive("pack.zip", "../escaped");

        assertThrows(RuntimeException.class, () -> service.addConvertedRawPackFile("pack.zip", false));

        assertWorkFolderCanBeEmptied();
        Files.delete(library.resolve("pack.zip"));
    }

    @Test
    @DisplayName("a conversion that succeeds still produces its artefact, and holds nothing after")
    void successfulConversionReleasesEverything() throws Exception {
        writeArchive("pack.zip", PACK_UUID);

        Optional<Path> converted = service.addConvertedRawPackFile("pack.zip", false);

        assertTrue(converted.isPresent(), "the ordinary case must keep working");
        assertTrue(Files.exists(library.resolve(converted.get())), "the artefact is installed");
        // The temporary is moved out on success, so the folder is empty and stays deletable.
        assertWorkFolderCanBeEmptied();
    }

    // ---------------------------------------------------------------- helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    /**
     * Deletes everything the conversion left in its work folder, once, and fails if it cannot.
     *
     * <p>Deliberately a single attempt: retrying or asking for a collection would let the JDK's
     * cleaner close a leaked handle and make this pass while the defect remained.
     */
    private void assertWorkFolderCanBeEmptied() throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(workFolder)) {
            for (Path entry : entries) {
                try {
                    Files.delete(entry);
                } catch (IOException e) {
                    throw new AssertionError("the conversion is still holding "
                            + entry.getFileName() + " open: " + e, e);
                }
            }
        }
    }

    /** A pack with one asset-free stage node, carrying the identifier under test. */
    private void writeArchive(String name, String uuid) throws Exception {
        StageNode node = new StageNode(uuid, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        StoryPack pack = new StoryPack(uuid, false, (short) 1, Collections.singletonList(node), null, false);
        try (OutputStream out = Files.newOutputStream(library.resolve(name))) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
    }

    /** The first half of a valid archive: enough to open, not enough to read. */
    private void writeTruncatedArchive(String name) throws Exception {
        Path complete = root.resolve("complete.zip");
        StageNode node = new StageNode(PACK_UUID, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        StoryPack pack = new StoryPack(PACK_UUID, false, (short) 1,
                Collections.singletonList(node), null, false);
        try (OutputStream out = Files.newOutputStream(complete)) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
        byte[] whole = Files.readAllBytes(complete);
        Files.write(library.resolve(name), java.util.Arrays.copyOf(whole, whole.length / 2));
        Files.delete(complete);
    }
}
