/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.metadata.DatabaseMetadataService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which artefact of a story comes first when the library holds several versions of it.
 *
 * <p>The library groups artefacts by UUID and used to order each group by file modification time,
 * newest first. The first entry is what the UI calls "latest", what the drop-on-device logic looks
 * at first, and what the unofficial metadata (title, thumbnail) is refreshed from. A modification
 * time says when a file was last written, not which version it holds: a v1 archive copied into the
 * library after the v2 was saved sorts first, its title takes over the tile, and it is what the
 * device would get. The story pack version is the one piece of identity the formats carry for this
 * (see {@code FORMATS.md} §9), so it orders the group; the modification time only breaks ties
 * between artefacts of the same version, which is what it was ever good for.
 *
 * <p>Same fixture as {@link LibraryCacheCoherenceTest}: a minimal {@code story.json} in a STORED
 * zip, the metadata database outside {@code @TempDir} for the reason documented there.
 */
class LibraryVersionOrderingTest {

    private static final String UUID_UNDER_TEST = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final long FIXED_ENTRY_TIME = 1_500_000_000_000L;
    private static final long OLD = 1_600_000_000_000L;
    private static final long NEW = 1_700_000_000_000L;

    @TempDir
    Path root;
    private Path library;
    private Path metadataDatabaseFolder;
    private LibraryService service;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("tmp"));
        metadataDatabaseFolder = Files.createTempDirectory("studio-metadata-db");
        metadataDatabaseFolder.toFile().deleteOnExit();
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library.toString() + java.io.File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp").toString() + java.io.File.separator);
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, metadataDatabaseFolder.resolve("unofficial.json").toString());
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
        try {
            Files.deleteIfExists(metadataDatabaseFolder.resolve("unofficial.json"));
            Files.deleteIfExists(metadataDatabaseFolder);
        } catch (IOException tolerated) {
            // The metadata database reader may still hold the file; deleteOnExit has another go.
        }
    }

    @Test
    @DisplayName("the highest version comes first, even when an older version's file is newer")
    void highestVersionFirstRegardlessOfModificationTime() throws IOException {
        writePack("story-v2.zip", 2, "Title v2", OLD);
        writePack("story-v1.zip", 1, "Title v1", NEW);

        assertEquals(List.of(2, 1), exposedVersions());
    }

    @Test
    @DisplayName("within one version, the most recently modified artefact comes first")
    void modificationTimeOnlyBreaksTiesWithinAVersion() throws IOException {
        writePack("story-v1-old.zip", 1, "Title", OLD);
        writePack("story-v1-new.zip", 1, "Title", NEW);
        writePack("story-v2.zip", 2, "Title", OLD);

        assertEquals(List.of("story-v2.zip", "story-v1-new.zip", "story-v1-old.zip"), exposedPaths());
    }

    @Test
    @DisplayName("the story's title on the tile is the highest version's, not the newest file's")
    void metadataIsRefreshedFromTheHighestVersion() throws IOException {
        writePack("story-v2.zip", 2, "Title v2", OLD);
        writePack("story-v1.zip", 1, "Title v1", NEW);

        // Every artefact of the group reports the group's title, which the unofficial database
        // refreshed from the first entry. Before the fix that was the v1 file, being the newest.
        JsonArray packs = exposedGroup().getJsonArray("packs");
        for (int i = 0; i < packs.size(); i++) {
            assertEquals("Title v2", packs.getJsonObject(i).getString("title"), "artefact " + i);
        }
    }

    // ---------------------------------------------------------------- fixtures and helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private JsonObject exposedGroup() {
        JsonArray groups = service.packs();
        assertEquals(1, groups.size(), "expected exactly one UUID group");
        return groups.getJsonObject(0);
    }

    private List<Integer> exposedVersions() {
        JsonArray packs = exposedGroup().getJsonArray("packs");
        return IntStream.range(0, packs.size()).mapToObj(i -> packs.getJsonObject(i).getInteger("version")).collect(Collectors.toList());
    }

    private List<String> exposedPaths() {
        JsonArray packs = exposedGroup().getJsonArray("packs");
        return IntStream.range(0, packs.size()).mapToObj(i -> packs.getJsonObject(i).getString("path")).collect(Collectors.toList());
    }

    private void writePack(String name, int version, String title, long modifiedMillis) throws IOException {
        String descriptor = "{\"version\":" + version
                + ",\"title\":\"" + title + "\""
                + ",\"description\":\"A story pack built for a test\""
                + ",\"nightModeAvailable\":false"
                + ",\"stageNodes\":[{\"uuid\":\"" + UUID_UNDER_TEST + "\"}]}";
        byte[] bytes = descriptor.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry("story.json");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        entry.setTime(FIXED_ENTRY_TIME);
        Path target = library.resolve(name);
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.STORED);
            zip.putNextEntry(entry);
            zip.write(bytes);
            zip.closeEntry();
        }
        Files.setLastModifiedTime(target, FileTime.fromMillis(modifiedMillis));
    }
}
