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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the provenance ledger promises, and the one promise that matters most: it never destroys
 * itself.
 *
 * <p>The interesting tests here are the refusals. A ledger that cannot be parsed must not become an
 * empty one that the next conversion happily overwrites — that would turn a single bad read into the
 * silent loss of every proof still in the file. Losing provenance costs a confirmation dialog;
 * overwriting it costs something the user cannot get back, and the two are not comparable.
 *
 * <p>Everything runs against a temporary ledger through the system property, offline, with no
 * library and no device.
 */
class ConversionProvenanceStoreTest {

    @TempDir
    Path root;

    private Path storeFile;
    private ConversionProvenanceStore store;
    private String previousProperty;

    @BeforeEach
    void setUp() {
        storeFile = root.resolve("conversions.json");
        previousProperty = System.getProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP);
        System.setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, storeFile.toString());
        store = new ConversionProvenanceStore();
    }

    @AfterEach
    void tearDown() {
        if (previousProperty == null) {
            System.clearProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP);
        } else {
            System.setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, previousProperty);
        }
    }

    // ---------------------------------------------------------------- absent

    @Test
    @DisplayName("a ledger that does not exist yet knows nothing and says so")
    void absentLedgerKnowsNothing() {
        assertEquals(ConversionProvenanceStore.State.ABSENT, store.state());
        assertFalse(store.find("anything.converted_1").isPresent());
        assertFalse(Files.exists(storeFile), "asking a question must not create the ledger");
    }

    @Test
    @DisplayName("the first record creates the ledger")
    void firstRecordCreatesTheLedger() {
        assertTrue(store.record("u.converted_1", record("u.zip", "aaa")));

        assertTrue(Files.exists(storeFile));
        assertEquals(ConversionProvenanceStore.State.LOADED, store.state());
    }

    // ---------------------------------------------------------------- round trip

    @Test
    @DisplayName("a record comes back exactly as it was filed")
    void recordSurvivesARoundTrip() {
        ConversionRecord written = new ConversionRecord("Cornebidouille.zip",
                ConversionRecord.Kind.FILE, "a".repeat(64),
                ConversionRecord.Kind.TREE, "b".repeat(64), 123456789L, 1_700_000_000_000L,
                "fs", 1);

        assertTrue(store.record("u.converted_1", written));

        ConversionRecord read = require(store.find("u.converted_1"));
        assertEquals("Cornebidouille.zip", read.getSourceName());
        assertEquals(ConversionRecord.Kind.FILE, read.getSourceKind());
        assertEquals("a".repeat(64), read.getSourceSha256());
        assertEquals(ConversionRecord.Kind.TREE, read.getArtifactKind());
        assertEquals("b".repeat(64), read.getArtifactSha256());
        assertEquals(123456789L, read.getArtifactSize());
        assertEquals(1_700_000_000_000L, read.getArtifactLastModified());
        assertEquals("fs", read.getTargetFormat());
        assertEquals(1, read.getConverterVersion());
    }

    @Test
    @DisplayName("several conversions live side by side")
    void severalRecordsCoexist() {
        store.record("u.converted_1", record("u.zip", "aaa"));
        store.record("u.converted_2", record("u.zip", "bbb"));
        store.record("v.converted_3", record("v.pack", "ccc"));

        assertEquals("aaa", require(store.find("u.converted_1")).getSourceSha256());
        assertEquals("bbb", require(store.find("u.converted_2")).getSourceSha256());
        assertEquals("ccc", require(store.find("v.converted_3")).getSourceSha256());
    }

    @Test
    @DisplayName("filing under a name that already has a record replaces it")
    void recordingTwiceReplaces() {
        store.record("u.converted_1", record("u.zip", "first"));
        store.record("u.converted_1", record("u.zip", "second"));

        assertEquals("second", require(store.find("u.converted_1")).getSourceSha256());
    }

    @Test
    @DisplayName("the ledger carries its schema version")
    void ledgerCarriesItsSchemaVersion() throws IOException {
        store.record("u.converted_1", record("u.zip", "aaa"));

        String content = new String(Files.readAllBytes(storeFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("\"schemaVersion\": " + ConversionProvenanceStore.SCHEMA_VERSION),
                "expected the schema version in " + content);
    }

    @Test
    @DisplayName("an unknown name has no record, which is not a statement about the artefacts")
    void unknownNameHasNoRecord() {
        store.record("u.converted_1", record("u.zip", "aaa"));

        assertFalse(store.find("somethingElse.converted_9").isPresent());
    }

    // ---------------------------------------------------------------- invalid, and left alone

    @Test
    @DisplayName("a ledger that does not parse is INVALID, yields nothing, and is never written over")
    void corruptLedgerIsNeverOverwritten() throws IOException {
        byte[] corrupt = "{ this is not json".getBytes(StandardCharsets.UTF_8);
        Files.write(storeFile, corrupt);

        assertEquals(ConversionProvenanceStore.State.INVALID, store.state());
        assertFalse(store.find("u.converted_1").isPresent());

        assertFalse(store.record("u.converted_1", record("u.zip", "aaa")),
                "recording into an unreadable ledger must fail rather than replace it");
        assertArrayEquals(corrupt, Files.readAllBytes(storeFile),
                "the unreadable ledger must be left byte for byte as it was found");
    }

    @Test
    @DisplayName("a ledger from a future schema is left alone rather than reinterpreted")
    void unknownSchemaVersionIsLeftAlone() throws IOException {
        byte[] future = ("{\"schemaVersion\": " + (ConversionProvenanceStore.SCHEMA_VERSION + 1)
                + ", \"conversions\": {}}").getBytes(StandardCharsets.UTF_8);
        Files.write(storeFile, future);

        assertEquals(ConversionProvenanceStore.State.INVALID, store.state());
        assertFalse(store.record("u.converted_1", record("u.zip", "aaa")));
        assertArrayEquals(future, Files.readAllBytes(storeFile));
    }

    @Test
    @DisplayName("valid JSON that is not a ledger is INVALID too")
    void wellFormedButWrongShapeIsInvalid() throws IOException {
        Files.write(storeFile, "[1, 2, 3]".getBytes(StandardCharsets.UTF_8));

        assertEquals(ConversionProvenanceStore.State.INVALID, store.state());
        assertFalse(store.find("u.converted_1").isPresent());
    }

    // ---------------------------------------------------------------- the temporary

    @Test
    @DisplayName("a temporary left by an interrupted write is never read as provenance")
    void staleTemporaryIsNotProvenance() throws IOException {
        Path temporary = root.resolve("conversions.json.new");
        Files.write(temporary, ("{\"schemaVersion\": 1, \"conversions\": {\"u.converted_1\": "
                + "{\"sourceSha256\": \"ghost\"}}}").getBytes(StandardCharsets.UTF_8));

        assertEquals(ConversionProvenanceStore.State.ABSENT, store.state());
        assertFalse(store.find("u.converted_1").isPresent(),
                "only the ledger itself is ever read");
    }

    @Test
    @DisplayName("a stale temporary does not stop the next write, and does not survive it")
    void staleTemporaryIsReplacedByTheNextWrite() throws IOException {
        Path temporary = root.resolve("conversions.json.new");
        Files.write(temporary, "leftover".getBytes(StandardCharsets.UTF_8));

        assertTrue(store.record("u.converted_1", record("u.zip", "aaa")));

        assertEquals("aaa", require(store.find("u.converted_1")).getSourceSha256());
        assertFalse(Files.exists(temporary), "the temporary is consumed by the move");
    }

    // ---------------------------------------------------------------- helpers

    private static ConversionRecord record(String sourceName, String sourceDigest) {
        return new ConversionRecord(sourceName, ConversionRecord.Kind.FILE, sourceDigest,
                ConversionRecord.Kind.TREE, "artifact", 1L, 2L, "fs", 1);
    }

    private static ConversionRecord require(Optional<ConversionRecord> record) {
        assertTrue(record.isPresent(), "expected a record");
        return record.get();
    }
}
