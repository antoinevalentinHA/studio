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
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.core.v1.writer.binary.BinaryStoryPackWriter;
import studio.core.v1.writer.fs.FsStoryPackWriter;
import studio.metadata.DatabaseMetadataService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a conversion writes down about itself.
 *
 * <p>A conversion is the one moment STUdio knows both halves of the answer C7-2 needs: it holds the
 * source it is reading and it creates the artefact. Nothing later can reconstruct that — a file name
 * is a label, a UUID is shared by every version of a pack. So it has to be recorded as it happens.
 *
 * <p>Everything here is about <em>writing</em> provenance. Nothing reads it to decide anything: the
 * transfer path is untouched, the confirmation dialog still appears exactly when it did, and these
 * records sit in a file that no other code opens yet.
 *
 * <p>The refusals matter as much as the records. A conversion is a piece of work the user asked for
 * and got; failing to describe it must never take it away from them. So every test that stops a
 * record from being written also asserts the artefact is still there — because the alternative,
 * treating provenance as a condition of validity, would turn a bookkeeping problem into data loss.
 *
 * <p>Fixtures are built in code with no assets at all: {@code PackAssetsCompression} skips nodes
 * without them and {@code FsStoryPackWriter} substitutes a built-in blank sound, so no MP3 or BMP
 * has to be committed to exercise all six conversions.
 */
class ConversionProvenanceTest {

    private static final String PACK_UUID = "11111111-2222-3333-4444-555555555555";

    /** A pack folder is named after its UUID: FsStoryPackReader reads the identity off the name. */
    private static final String FS_PACK_NAME = PACK_UUID + ".folder";

    @TempDir
    Path root;

    private Path library;
    private Path store;
    private LibraryService service;
    private ConversionProvenanceStore provenance;
    private final ContentDigest digest = new ContentDigest();
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("db"));
        store = root.resolve("db").resolve("conversions.json");

        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + java.io.File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + java.io.File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, store.toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP,
                root.resolve("db").resolve("unofficial.json").toString());

        service = new LibraryService(new DatabaseMetadataService(true));
        provenance = new ConversionProvenanceStore();
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

    // ------------------------------------------------- the six conversions

    @Test
    @DisplayName("archive to raw is recorded, with a file on each side")
    void archiveToRawIsRecorded() throws IOException {
        Path source = writeArchive("pack.zip");

        Path artifact = convert(service.addConvertedRawPackFile("pack.zip", false));

        assertRecorded(artifact, source, ConversionRecord.Kind.FILE, ConversionRecord.Kind.FILE, "raw");
    }

    @Test
    @DisplayName("fs to raw is recorded, with a tree as the source")
    void fsToRawIsRecorded() throws Exception {
        Path source = writeFsPack(FS_PACK_NAME);

        Path artifact = convert(service.addConvertedRawPackFile(FS_PACK_NAME, false));

        assertRecorded(artifact, source, ConversionRecord.Kind.TREE, ConversionRecord.Kind.FILE, "raw");
    }

    @Test
    @DisplayName("raw to archive is recorded")
    void rawToArchiveIsRecorded() throws IOException {
        Path source = writeRaw("pack.pack");

        Path artifact = convert(service.addConvertedArchivePackFile("pack.pack"));

        assertRecorded(artifact, source, ConversionRecord.Kind.FILE, ConversionRecord.Kind.FILE, "archive");
    }

    @Test
    @DisplayName("fs to archive is recorded, with a tree as the source")
    void fsToArchiveIsRecorded() throws Exception {
        Path source = writeFsPack(FS_PACK_NAME);

        Path artifact = convert(service.addConvertedArchivePackFile(FS_PACK_NAME));

        assertRecorded(artifact, source, ConversionRecord.Kind.TREE, ConversionRecord.Kind.FILE, "archive");
    }

    @Test
    @DisplayName("archive to fs is recorded, with a tree as the artefact")
    void archiveToFsIsRecorded() throws IOException {
        Path source = writeArchive("pack.zip");

        Path artifact = convert(service.addConvertedFsPackFile("pack.zip", false));

        assertRecorded(artifact, source, ConversionRecord.Kind.FILE, ConversionRecord.Kind.TREE, "fs");
    }

    @Test
    @DisplayName("raw to fs is recorded, with a tree as the artefact")
    void rawToFsIsRecorded() throws IOException {
        Path source = writeRaw("pack.pack");

        Path artifact = convert(service.addConvertedFsPackFile("pack.pack", false));

        assertRecorded(artifact, source, ConversionRecord.Kind.FILE, ConversionRecord.Kind.TREE, "fs");
    }

    // ------------------------------------------------- what is recorded

    @Test
    @DisplayName("the converter version is the application's own, not an invented number")
    void converterVersionIsTheApplicationVersion() throws IOException {
        writeArchive("pack.zip");

        Path artifact = convert(service.addConvertedFsPackFile("pack.zip", false));

        String recorded = record(artifact).getConverterVersion();
        assertEquals(ApplicationVersion.current(), recorded);
        assertNotEquals(ApplicationVersion.UNKNOWN, recorded,
                "the build stamps evergreen.properties, so the version should be a real one here");
    }

    @Test
    @DisplayName("two conversions of one pack give two artefacts and two records")
    void twoConversionsAreRecordedSeparately() throws IOException {
        writeArchive("pack.zip");

        Path first = convert(service.addConvertedFsPackFile("pack.zip", false));
        Path second = convert(service.addConvertedRawPackFile("pack.zip", false));

        assertNotEquals(first.getFileName().toString(), second.getFileName().toString());
        assertEquals("fs", record(first).getTargetFormat());
        assertEquals("raw", record(second).getTargetFormat());
    }

    // ------------------------------------------------- when nothing is recorded

    @Test
    @DisplayName("a source that changes during the conversion leaves the artefact but no record")
    void unstableSourceIsNotRecorded() throws IOException {
        writeArchive("pack.zip");

        // The seam makes the change deterministic, with no timing and no thread. The source is
        // rewritten just before the second observation — that is, after the conversion has read it —
        // which is exactly the window this guard exists to close. Changing it before the first
        // observation would instead break the conversion itself, which is a different scenario.
        LibraryService mutating = new LibraryService(new DatabaseMetadataService(true)) {
            private int observations = 0;

            @Override
            Optional<String> sourceIdentity(String packPath) {
                if (++observations == 2) {
                    try {
                        Files.write(library.resolve(packPath),
                                "changed underneath".getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return super.sourceIdentity(packPath);
            }
        };

        Path artifact = convert(mutating.addConvertedFsPackFile("pack.zip", false));

        assertTrue(Files.exists(artifact), "the conversion the user asked for must survive");
        assertFalse(provenance.find(artifact.getFileName().toString()).isPresent(),
                "a source that moved during the window cannot be recorded as the one converted");
    }

    @Test
    @DisplayName("a conversion that fails before installing anything records nothing")
    void failedConversionRecordsNothing() throws IOException {
        // Not a pack at all: the reader rejects it before any artefact is produced.
        Files.write(library.resolve("pack.zip"), "not an archive".getBytes(StandardCharsets.UTF_8));

        try {
            service.addConvertedFsPackFile("pack.zip", false);
        } catch (RuntimeException expected) {
            // The conversion is supposed to fail; what matters is what it left behind.
        }

        assertEquals(ConversionProvenanceStore.State.ABSENT, provenance.state(),
                "a conversion that produced nothing must not have written anything either");
    }

    @Test
    @DisplayName("an unreadable ledger costs the record, never the conversion")
    void invalidStoreKeepsTheConversion() throws IOException {
        writeArchive("pack.zip");
        byte[] corrupt = "{ not json".getBytes(StandardCharsets.UTF_8);
        Files.write(store, corrupt);

        Path artifact = convert(service.addConvertedFsPackFile("pack.zip", false));

        assertTrue(Files.exists(artifact), "the conversion must not depend on the ledger");
        assertEquals(ConversionProvenanceStore.State.INVALID, provenance.state());
        assertArrayEqualsMessage(corrupt, Files.readAllBytes(store));
    }

    // ------------------------------------------------- helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    /** Unwraps the conversion result and resolves it against the library. */
    private Path convert(Optional<Path> converted) {
        assertTrue(converted.isPresent(), "expected the conversion to produce an artefact");
        Path artifact = library.resolve(converted.get());
        assertTrue(Files.exists(artifact), "expected the artefact to be installed");
        return artifact;
    }

    private ConversionRecord record(Path artifact) {
        Optional<ConversionRecord> found = provenance.find(artifact.getFileName().toString());
        assertTrue(found.isPresent(), "expected a record for " + artifact.getFileName());
        return found.get();
    }

    /**
     * Asserts the record describes this conversion, and that both digests are the ones the artefacts
     * actually have — recomputed here rather than trusted from the record.
     */
    private void assertRecorded(Path artifact, Path source, ConversionRecord.Kind sourceKind,
                                ConversionRecord.Kind artifactKind, String targetFormat) {
        ConversionRecord recorded = record(artifact);

        assertEquals(sourceKind, recorded.getSourceKind());
        assertEquals(artifactKind, recorded.getArtifactKind());
        assertEquals(targetFormat, recorded.getTargetFormat());
        assertEquals(source.getFileName().toString(), recorded.getSourceName());

        Optional<String> sourceDigest = sourceKind == ConversionRecord.Kind.FILE
                ? digest.ofFile(source) : digest.ofTree(source);
        Optional<String> artifactDigest = artifactKind == ConversionRecord.Kind.FILE
                ? digest.ofFile(artifact) : digest.ofTree(artifact);

        assertEquals(sourceDigest.orElse("<none>"), recorded.getSourceSha256(),
                "the recorded source digest must be the source's own");
        assertEquals(artifactDigest.orElse("<none>"), recorded.getArtifactSha256(),
                "the recorded artefact digest must be the artefact's own");
    }

    private static void assertArrayEqualsMessage(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8),
                new String(actual, StandardCharsets.UTF_8),
                "an unreadable ledger must be left exactly as it was found");
    }

    // ------------------------------------------------- fixtures

    /** A pack with one stage node and no assets, which every reader and writer accepts. */
    private static StoryPack minimalPack() {
        StageNode node = new StageNode(PACK_UUID, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        return new StoryPack(PACK_UUID, false, (short) 1, Collections.singletonList(node), null, false);
    }

    private Path writeArchive(String name) throws IOException {
        Path file = library.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            new ArchiveStoryPackWriter().write(minimalPack(), out);
        }
        return file;
    }

    private Path writeRaw(String name) throws IOException {
        Path file = library.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            new BinaryStoryPackWriter().write(minimalPack(), out, false);
        }
        return file;
    }

    private Path writeFsPack(String name) throws Exception {
        Path parent = Files.createDirectories(root.resolve("fixture"));
        Path written = new FsStoryPackWriter().write(minimalPack(), parent);
        Path destination = library.resolve(name);
        Files.move(written, destination);
        return destination;
    }
}
