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
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the library may still say about a file after that file has changed underneath it.
 *
 * <p>{@code LibraryService} keeps parsed packs in a five-minute cache keyed by path, and the entry
 * carries the whole {@code LibraryPack} — the parsed metadata and the modification time it was read
 * with. Replacing a file at a path it already knows therefore had no effect on what the library
 * reported: it kept serving the previous parse, including the previous timestamp, until the entry
 * expired. Overwriting a pack in place is not an edge case; saving from the editor does it by
 * design, and so does dropping the same file into the library twice.
 *
 * <p>These tests fix what "the same file" means, and they do it on the two attributes a stat gives
 * for free: <strong>size and last-modified time</strong>. That is a coherence guarantee about one
 * path at one moment, and it is deliberately not an identity guarantee — {@link
 * #sameSizeAndMtimeContentChangeIsOutsideTheCoherenceGuarantee()} draws the line and explains which
 * problem lives on the other side of it.
 *
 * <p><strong>On what these tests assert.</strong> Not the title. {@code getPackMetadata} overrides
 * title, description and image from the metadata database whenever it knows the UUID, and {@code
 * packs()} refreshes that database from a deliberately uncached read on every call — so a stale
 * cache is invisible through the title, which arrives fresh by another route. {@code version} and
 * {@code timestamp} come from the cached parse and nothing overwrites them, so those are what the
 * assertions use.
 *
 * <p>Offline: a temporary library, a temporary metadata database, {@code isAgent=true} so the
 * official database is never read or fetched, and fixtures built in code. No network, no device, no
 * user file.
 */
class LibraryCacheCoherenceTest {

    private static final String UUID_UNDER_TEST = "11111111-2222-3333-4444-555555555555";

    /** Fixed so a fixture's byte length depends only on its JSON, never on when it was built. */
    private static final long FIXED_ENTRY_TIME = 1_500_000_000_000L;

    @TempDir
    Path root;

    private Path library;
    private Path pack;
    private Path metadataDatabaseFolder;
    private LibraryService service;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("tmp"));
        pack = library.resolve("u.zip");

        // The metadata database deliberately lives outside @TempDir. DatabaseMetadataService reads
        // it through a FileReader it never closes, and a legacy java.io stream does not open with
        // FILE_SHARE_DELETE, so on Windows the handle survives the test and @TempDir's cleanup fails
        // on a file this test does not own. That leak is real and is not this lot's to fix; putting
        // the file elsewhere keeps the failure out of tests that are about something else.
        metadataDatabaseFolder = Files.createTempDirectory("studio-metadata-db");
        metadataDatabaseFolder.toFile().deleteOnExit();

        // LibraryService concatenates libraryPath() with a file name, so the property has to end
        // with a separator.
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library.toString() + java.io.File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp").toString() + java.io.File.separator);
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP,
                metadataDatabaseFolder.resolve("unofficial.json").toString());

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
            // See setUp: the handle may still be open. deleteOnExit will have another go.
        }
    }

    // ---------------------------------------------------------------- the cache must let go

    @Test
    @DisplayName("R1: a replacement with a different modification time is re-read")
    void changedModificationTimeIsReRead() throws IOException {
        writePack(version(1));
        assertEquals(1, exposedVersion());

        // Same length, so only the modification time can betray the change.
        long lengthBefore = Files.size(pack);
        writePack(version(2));
        assertEquals(lengthBefore, Files.size(pack), "fixture bug: this case needs an equal size");
        Files.setLastModifiedTime(pack, FileTime.fromMillis(Files.getLastModifiedTime(pack).toMillis() + 2000));

        assertEquals(2, exposedVersion());
    }

    @Test
    @DisplayName("R2: a replacement of a different size is re-read even with the old modification time")
    void changedSizeIsReReadEvenWhenTheModificationTimeIsRestored() throws IOException {
        writePack(version(1));
        assertEquals(1, exposedVersion());
        FileTime originalTime = Files.getLastModifiedTime(pack);
        long lengthBefore = Files.size(pack);

        writePack(version(2) + padding(64));
        Files.setLastModifiedTime(pack, originalTime);

        // The modification time is now indistinguishable from before, on purpose: this asserts that
        // the size alone is enough.
        assertEquals(originalTime, Files.getLastModifiedTime(pack));
        assertNotEquals(lengthBefore, Files.size(pack), "fixture bug: this case needs a different size");
        assertEquals(2, exposedVersion());
    }

    @Test
    @DisplayName("R3: a pack deleted and recreated at the same path is re-read")
    void deletedAndRecreatedPathIsReRead() throws IOException {
        writePack(version(1));
        assertEquals(1, exposedVersion());

        Files.delete(pack);
        writePack(version(2) + padding(32));

        assertEquals(2, exposedVersion());
    }

    @Test
    @DisplayName("R4: a repaired pack is visible immediately, not after the cache expires")
    void repairedPackIsNotHiddenByTheCachedFailure() throws IOException {
        // A pack read while it was still being copied in. The listing walks the library folder, so a
        // half-written file is read, fails to parse, and the failure is cached like any result.
        writeTruncatedPack();
        assertEquals(0, service.packs().size(), "a pack that does not parse must not be listed");

        writePack(version(1));

        assertEquals(1, service.packs().size(), "the repaired pack must appear without waiting for the cache to expire");
        assertEquals(1, exposedVersion());
    }

    // ---------------------------------------------------------------- the cache must still hold

    @Test
    @DisplayName("R5: an untouched pack keeps reporting the same thing")
    void untouchedPackIsStable() throws IOException {
        writePack(version(1));

        JsonObject first = exposedPack();
        JsonObject second = exposedPack();

        assertEquals(first.getInteger("version"), second.getInteger("version"));
        assertEquals(first.getLong("timestamp"), second.getLong("timestamp"));
        // Stability alone cannot tell a cache hit from a second parse of an unchanged file — both
        // produce this result. What proves the entry was reused is the test below, where a change
        // the guarantee does not cover goes unnoticed precisely because nothing was re-read.
    }

    @Test
    @DisplayName("R6: LIMIT — a change with the same size and modification time is not detected")
    void sameSizeAndMtimeContentChangeIsOutsideTheCoherenceGuarantee() throws IOException {
        writePack(version(1));
        assertEquals(1, exposedVersion());
        FileTime originalTime = Files.getLastModifiedTime(pack);
        long lengthBefore = Files.size(pack);

        // Different content, byte-for-byte the same length, and the modification time put back.
        writePack(version(2));
        Files.setLastModifiedTime(pack, originalTime);
        assertEquals(lengthBefore, Files.size(pack), "fixture bug: this case needs an equal size");

        // The old parse is served. This is the boundary of what a stat can establish, not a defect
        // to be fixed here: answering it would mean reading the file to compare its content, which
        // is the work the cache exists to avoid. Establishing that a particular converted pack came
        // from a particular source is a different problem and is not addressed by this guarantee.
        assertEquals(1, exposedVersion(), "documented limit: size and modification time are all this checks");
    }

    // ---------------------------------------------------------------- STUdio overwriting its own library

    @Test
    @DisplayName("An in-place overwrite through addPackFile exposes the new metadata at once")
    void packFileOverwrittenInPlaceIsReReadImmediately() throws IOException {
        writePack(version(1));
        assertEquals(1, exposedVersion());

        // Saving a pack from the editor writes to the file name it was opened under, so the
        // destination is one the library has already parsed and cached.
        Path uploaded = root.resolve("tmp").resolve("uploaded.zip");
        writePackTo(uploaded, version(2) + padding(48));
        assertTrue(service.addPackFile("u.zip", uploaded.toString()));

        assertEquals(2, exposedVersion());
    }

    // ---------------------------------------------------------------- fixtures and helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    /** The listing exposes exactly one artefact under exactly one UUID; this returns it. */
    private JsonObject exposedPack() {
        JsonArray groups = service.packs();
        assertEquals(1, groups.size(), "expected exactly one UUID group");
        JsonArray packs = groups.getJsonObject(0).getJsonArray("packs");
        assertEquals(1, packs.size(), "expected exactly one artefact for that UUID");
        return packs.getJsonObject(0);
    }

    private int exposedVersion() {
        return exposedPack().getInteger("version");
    }

    /**
     * A story descriptor holding what {@code ArchiveStoryPackReader} actually requires: a version
     * and a first stage node carrying a UUID. Title and description are present because a pack
     * without them is unrepresentative, not because the parser needs them; no thumbnail and no
     * assets, which it does not read for metadata.
     *
     * <p>{@code trailer} pads the descriptor so a fixture's length can be varied on purpose — or
     * held identical on purpose, which is what the documented limit needs.
     */
    private String version(int version) {
        return "{\"version\":" + version
                + ",\"title\":\"Pack under test\""
                + ",\"description\":\"A story pack built for a test\""
                + ",\"nightModeAvailable\":false"
                + ",\"stageNodes\":[{\"uuid\":\"" + UUID_UNDER_TEST + "\"}]";
    }

    private String padding(int characters) {
        StringBuilder builder = new StringBuilder(",\"pad\":\"");
        for (int i = 0; i < characters; i++) {
            builder.append('x');
        }
        return builder.append('"').toString();
    }

    private void writePack(String descriptorWithoutClosingBrace) throws IOException {
        writePackTo(pack, descriptorWithoutClosingBrace);
    }

    /**
     * Written with STORED entries and a fixed entry time, so the archive's byte length is a
     * deterministic function of the descriptor. Deflate would make "the same length" a matter of how
     * two inputs happen to compress, which is not something a test should rely on.
     */
    private void writePackTo(Path target, String descriptorWithoutClosingBrace) throws IOException {
        byte[] descriptor = (descriptorWithoutClosingBrace + "}").getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(descriptor);

        ZipEntry entry = new ZipEntry("story.json");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(descriptor.length);
        entry.setCompressedSize(descriptor.length);
        entry.setCrc(crc.getValue());
        entry.setTime(FIXED_ENTRY_TIME);

        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.STORED);
            zip.putNextEntry(entry);
            zip.write(descriptor);
            zip.closeEntry();
        }
    }

    /** Half of a valid archive — what is on disk while a copy into the library is under way. */
    private void writeTruncatedPack() throws IOException {
        Path complete = root.resolve("tmp").resolve("complete.zip");
        writePackTo(complete, version(1));
        byte[] whole = Files.readAllBytes(complete);
        Files.write(pack, java.util.Arrays.copyOf(whole, whole.length / 2));
        Files.delete(complete);
    }
}
