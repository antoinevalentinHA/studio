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
import org.junit.jupiter.api.io.TempDir;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uploading a zip that holds a pack in the device's own layout — {@code ni}, {@code li}, {@code ri},
 * {@code si} and the {@code rf/} and {@code sf/} asset folders — rather than a {@code story.json}.
 *
 * <p>Such zips are how packs copied straight off an SD card get passed around. The library reads
 * that layout already, but only as a folder: a zip was handed to the archive reader, which found no
 * {@code story.json} and the pack was silently dropped from the listing. The upload now unpacks it
 * into a folder instead, and the zip itself is not kept.
 *
 * <p>The folder takes its name from the zip's single top-level folder when there is one — the
 * device's packs are named by UUID and the reader takes the UUID from the folder name — and from
 * the upload's own name otherwise. A zip that holds a {@code story.json}, or anything the library
 * does not recognise, is stored as it was: saving a pack from the editor goes through the same
 * upload, and that flow must not change.
 */
class LibraryFsPackImportTest {

    private static final String UUID = "EF1C88E3F1D34413B75F96BAF2A20C6E";
    private static final String SENTINEL = "outside-sentinel.txt";

    @TempDir
    Path root;

    private Path library;
    private Path sentinel;
    private LibraryService service;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("db"));
        Files.createDirectories(root.resolve("tmp"));
        sentinel = root.resolve(SENTINEL);
        Files.write(sentinel, "untouched".getBytes(StandardCharsets.UTF_8));

        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + File.separator);
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
    @DisplayName("a zipped pack folder is unpacked under the folder's own name, and the zip is not kept")
    void zippedPackFolderIsUnpackedUnderItsOwnName() throws IOException {
        Path uploaded = zip("upload.zip", fsPack(UUID + "/"));

        assertTrue(service.addPackFile("Ruben.zip", uploaded.toString()));

        Path pack = library.resolve(UUID);
        assertTrue(Files.isDirectory(pack), "the pack must be a folder of the library");
        assertArrayEquals(bytes("ni"), Files.readAllBytes(pack.resolve("ni")));
        assertArrayEquals(bytes("image"), Files.readAllBytes(pack.resolve("rf/000/AABBCCDD")));
        assertArrayEquals(bytes("sound"), Files.readAllBytes(pack.resolve("sf/000/11223344")));
        assertFalse(Files.exists(library.resolve("Ruben.zip")), "the zip is not a library entry");
        assertFalse(Files.exists(uploaded), "the upload has been consumed");
    }

    @Test
    @DisplayName("a zip with the pack files at its root is unpacked under the upload's name")
    void packFilesAtTheRootAreUnpackedUnderTheUploadName() throws IOException {
        Path uploaded = zip("upload.zip", fsPack(""));

        assertTrue(service.addPackFile("Ruben.zip", uploaded.toString()));

        assertArrayEquals(bytes("ni"), Files.readAllBytes(library.resolve("Ruben").resolve("ni")));
        assertFalse(Files.exists(library.resolve("Ruben.zip")));
    }

    @Test
    @DisplayName("a story.json archive is stored as it was")
    void archiveIsStoredUnchanged() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("story.json", bytes("{\"format\":\"v1\"}"));
        entries.put("assets/a.png", bytes("png"));
        Path uploaded = zip("upload.zip", entries);
        byte[] asUploaded = Files.readAllBytes(uploaded);

        assertTrue(service.addPackFile("pack.zip", uploaded.toString()));

        assertArrayEquals(asUploaded, Files.readAllBytes(library.resolve("pack.zip")));
    }

    @Test
    @DisplayName("a zip that is neither an archive nor a pack folder is stored as it was")
    void unrecognisedZipIsStoredUnchanged() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("readme.txt", bytes("not a pack"));
        Path uploaded = zip("upload.zip", entries);
        byte[] asUploaded = Files.readAllBytes(uploaded);

        assertTrue(service.addPackFile("pack.zip", uploaded.toString()));

        assertArrayEquals(asUploaded, Files.readAllBytes(library.resolve("pack.zip")));
    }

    @Test
    @DisplayName("an entry that climbs out of the pack folder is refused, and nothing is written")
    void entryLeavingThePackFolderIsRefused() throws IOException {
        Map<String, byte[]> entries = fsPack(UUID + "/");
        entries.put(UUID + "/rf/../../../" + SENTINEL, bytes("overwritten"));
        Path uploaded = zip("upload.zip", entries);

        assertFalse(service.addPackFile("Ruben.zip", uploaded.toString()));

        assertEquals("untouched", new String(Files.readAllBytes(sentinel), StandardCharsets.UTF_8));
        assertFalse(Files.exists(library.resolve(UUID)), "a refused import leaves no folder behind");
        assertFalse(Files.exists(library.resolve("Ruben.zip")));
    }

    @Test
    @DisplayName("a pack folder already in the library is replaced")
    void existingPackFolderIsReplaced() throws IOException {
        Path existing = Files.createDirectories(library.resolve(UUID));
        Files.write(existing.resolve("ni"), bytes("old"));
        Files.write(existing.resolve("stale"), bytes("should go"));
        Path uploaded = zip("upload.zip", fsPack(UUID + "/"));

        assertTrue(service.addPackFile("Ruben.zip", uploaded.toString()));

        assertArrayEquals(bytes("ni"), Files.readAllBytes(existing.resolve("ni")));
        assertFalse(Files.exists(existing.resolve("stale")), "the old folder is not merged into");
    }

    // ---------------------------------------------------------------- fixtures and helpers

    /** The files of a pack folder, under {@code prefix} ({@code ""} for the zip root). */
    private static Map<String, byte[]> fsPack(String prefix) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(prefix + "ni", bytes("ni"));
        entries.put(prefix + "li", bytes("li"));
        entries.put(prefix + "ri", bytes("ri"));
        entries.put(prefix + "si", bytes("si"));
        entries.put(prefix + "rf/000/AABBCCDD", bytes("image"));
        entries.put(prefix + "sf/000/11223344", bytes("sound"));
        return entries;
    }

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        Path file = root.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return file;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}
