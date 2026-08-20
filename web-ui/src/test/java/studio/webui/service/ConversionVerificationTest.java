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
import studio.metadata.DatabaseMetadataService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a converted pack may be sent without asking, and — far more often — when it may not.
 *
 * <p>Only {@code MATCH} removes the confirmation the user sees today, and it is only reached when
 * both artefacts have been read in full, now, and both digests equal the ones recorded when the
 * conversion ran. Every other outcome keeps the question. That asymmetry is the whole design: the
 * cost of asking unnecessarily is a dialog, and the cost of not asking wrongly is the wrong story
 * playing on a child's device.
 *
 * <p>{@code MISMATCH} and {@code UNKNOWN} are kept apart deliberately. "These do not correspond" is
 * something STUdio established; "I cannot tell" is the ordinary condition of every conversion made
 * before any of this existed. Both lead to the same dialog, and they do not deserve the same
 * sentence in it.
 */
class ConversionVerificationTest {

    private static final String PACK_UUID = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path root;

    private Path library;
    private Path store;
    private LibraryService service;
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

    // ------------------------------------------------- proven

    @Test
    @DisplayName("a conversion still matching its source is proven")
    void unchangedPairMatches() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");

        assertEquals(ProvenanceVerdict.MATCH, service.verifyConversion("pack.zip", converted));
    }

    @Test
    @DisplayName("a source renamed but unchanged is still proven")
    void renamedSourceStillMatches() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");

        Files.move(library.resolve("pack.zip"), library.resolve("renamed.zip"));

        // The record carries the source's name for diagnostics only; the comparison is on content.
        assertEquals(ProvenanceVerdict.MATCH, service.verifyConversion("renamed.zip", converted));
    }

    @Test
    @DisplayName("a touched but unmodified source is still proven")
    void touchedSourceStillMatches() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");

        Files.setLastModifiedTime(library.resolve("pack.zip"), FileTime.fromMillis(1_000_000_000_000L));

        // A modification time is not content. Nothing here is decided on one.
        assertEquals(ProvenanceVerdict.MATCH, service.verifyConversion("pack.zip", converted));
    }

    // ------------------------------------------------- established difference

    @Test
    @DisplayName("a source rewritten with different content no longer matches")
    void changedSourceMismatches() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");

        writeArchive("pack.zip", "a different pack");

        assertEquals(ProvenanceVerdict.MISMATCH, service.verifyConversion("pack.zip", converted));
    }

    @Test
    @DisplayName("a conversion replaced by hand no longer matches, whatever its size and date say")
    void changedArtifactMismatches() throws IOException {
        writeArchive("pack.zip");
        // A raw conversion, so the artefact is a single file and can be tampered with byte for
        // byte. The fs conversions produce a folder; the guarantee is the same either way.
        String converted = convertToRaw("pack.zip");
        Path artifact = library.resolve(converted);

        // Same length, same modification time, different bytes: the case C7-0b documented as
        // invisible to a stat. A proof that authorises a silent transfer may not rely on one.
        byte[] original = Files.readAllBytes(artifact);
        FileTime originalTime = Files.getLastModifiedTime(artifact);
        byte[] tampered = original.clone();
        tampered[tampered.length - 1] = (byte) (tampered[tampered.length - 1] ^ 0xFF);
        Files.write(artifact, tampered);
        Files.setLastModifiedTime(artifact, originalTime);

        assertEquals(original.length, Files.size(artifact), "fixture: the size must be unchanged");
        assertEquals(originalTime, Files.getLastModifiedTime(artifact), "fixture: the date must be unchanged");
        assertEquals(ProvenanceVerdict.MISMATCH, service.verifyConversion("pack.zip", converted));
    }

    @Test
    @DisplayName("another pack of the same UUID does not inherit the proof")
    void sameUuidDifferentContentMismatches() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");

        // Same UUID, different content. A UUID is shared by every version of a pack, so it can carry
        // no proof; only the bytes can.
        writeArchive("other.zip", "same uuid, other content");

        assertEquals(ProvenanceVerdict.MISMATCH, service.verifyConversion("other.zip", converted));
    }

    // ------------------------------------------------- no proof available

    @Test
    @DisplayName("a conversion from before any of this was recorded is simply unknown")
    void legacyConversionIsUnknown() throws IOException {
        writeArchive("pack.zip");
        // A conversion that exists but was never recorded, like every one made until now.
        Path legacy = library.resolve(PACK_UUID + ".converted_1600000000000");
        Files.createDirectory(legacy);
        Files.write(legacy.resolve("ni"), "index".getBytes(StandardCharsets.UTF_8));

        assertEquals(ProvenanceVerdict.UNKNOWN,
                service.verifyConversion("pack.zip", legacy.getFileName().toString()));
    }

    @Test
    @DisplayName("no ledger at all means unknown, not a mismatch")
    void absentStoreIsUnknown() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");
        Files.delete(store);

        assertEquals(ProvenanceVerdict.UNKNOWN, service.verifyConversion("pack.zip", converted));
    }

    @Test
    @DisplayName("an unreadable ledger means unknown, and is left alone")
    void invalidStoreIsUnknown() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");
        byte[] corrupt = "{ not json".getBytes(StandardCharsets.UTF_8);
        Files.write(store, corrupt);

        assertEquals(ProvenanceVerdict.UNKNOWN, service.verifyConversion("pack.zip", converted));
        assertEquals(new String(corrupt, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(store), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a source that is no longer there is unknown, not a mismatch")
    void missingSourceIsUnknown() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");
        Files.delete(library.resolve("pack.zip"));

        assertEquals(ProvenanceVerdict.UNKNOWN, service.verifyConversion("pack.zip", converted));
    }

    @Test
    @DisplayName("a conversion that is no longer there is unknown, not a mismatch")
    void missingArtifactIsUnknown() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");
        deleteRecursively(library.resolve(converted));

        assertEquals(ProvenanceVerdict.UNKNOWN, service.verifyConversion("pack.zip", converted));
    }

    @Test
    @DisplayName("an artefact read while it is changing is unknown, never a match")
    void unstableReadIsUnknown() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");

        // The digest primitive refuses a torn read; this asserts the verdict follows it rather than
        // falling back to anything.
        LibraryService unstable = new LibraryService(new DatabaseMetadataService(true)) {
            @Override
            Optional<String> sourceIdentity(String packPath) {
                return Optional.empty();
            }
        };

        assertEquals(ProvenanceVerdict.UNKNOWN, unstable.verifyConversion("pack.zip", converted));
    }

    // ------------------------------------------------- malformed requests

    @Test
    @DisplayName("a path leaving the library folder is refused, not answered")
    void pathTraversalIsRefused() throws IOException {
        writeArchive("pack.zip");
        String converted = convert("pack.zip");
        Path outside = root.resolve("outside.zip");
        Files.write(outside, "not yours".getBytes(StandardCharsets.UTF_8));

        // Uncertainty is a verdict; being asked about a file outside the library is not. Answering
        // it at all would make this a way to probe the filesystem.
        assertThrows(IllegalArgumentException.class,
                () -> service.verifyConversion("../outside.zip", converted));
        assertThrows(IllegalArgumentException.class,
                () -> service.verifyConversion("pack.zip", "../outside.zip"));
        assertThrows(IllegalArgumentException.class,
                () -> service.verifyConversion("sub/pack.zip", converted));
        assertThrows(IllegalArgumentException.class,
                () -> service.verifyConversion("", converted));
        assertThrows(IllegalArgumentException.class,
                () -> service.verifyConversion("pack.zip", null));
    }

    // ------------------------------------------------- helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private String convertToRaw(String sourceName) {
        Optional<Path> converted = service.addConvertedRawPackFile(sourceName, false);
        assertTrue(converted.isPresent(), "expected the fixture conversion to succeed");
        return converted.get().getFileName().toString();
    }

    private String convert(String sourceName) {
        Optional<Path> converted = service.addConvertedFsPackFile(sourceName, false);
        assertTrue(converted.isPresent(), "expected the fixture conversion to succeed");
        return converted.get().getFileName().toString();
    }

    private Path writeArchive(String name) throws IOException {
        return writeArchive(name, "a pack");
    }

    /** The description varies the content, so two fixtures of one UUID differ by their bytes. */
    private Path writeArchive(String name, String description) throws IOException {
        StageNode node = new StageNode(PACK_UUID, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        StoryPack pack = new StoryPack(PACK_UUID, false, (short) description.length(),
                Collections.singletonList(node), null, false);
        Path file = library.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
        return file;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
