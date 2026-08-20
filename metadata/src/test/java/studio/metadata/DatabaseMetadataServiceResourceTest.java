/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether this service lets go of the files it reads.
 *
 * <p>Every read here is {@code new JsonParser().parse(new FileReader(path))} — the reader is never
 * named, so it is never closed. Nothing collects it until the garbage collector happens to, and
 * until then the handle is alive. On Windows a live handle stops the file being deleted, which is
 * how this surfaced: a test in another module could not clean up its own temporary directory,
 * because a file it owned was still held open by this service.
 *
 * <p>The tests below delete the database file immediately after a read and require it to succeed.
 * <strong>No {@code System.gc()}, no sleep, no retry loop</strong> — any of those would let the
 * cleaner rescue the defect and turn a red test green for the wrong reason.
 *
 * <p>The delete tests are Windows-only, and that is a statement about the platform, not about the
 * code: POSIX unlinks a file that is still open, so deleting one there proves nothing either way.
 * The handles leak identically on both. What runs everywhere is the functional half — write, read
 * back, compare — which must be unaffected by closing anything.
 *
 * <p>Offline by construction: temporary files, system properties pointing at them, and {@code
 * isAgent=true} wherever the official database is not the subject, so the network fetch is never
 * reached.
 */
class DatabaseMetadataServiceResourceTest {

    private static final String UUID_UNDER_TEST = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path root;

    private Path officialDatabase;
    private Path unofficialDatabase;
    private final Map<String, String> previousProperties = new HashMap<String, String>();

    @BeforeEach
    void setUp() {
        officialDatabase = root.resolve("official.json");
        unofficialDatabase = root.resolve("unofficial.json");
        setProperty(DatabaseMetadataService.OFFICIAL_DB_PROP, officialDatabase.toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, unofficialDatabase.toString());
    }

    @AfterEach
    void tearDown() {
        for (Map.Entry<String, String> entry : previousProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    // ------------------------------------------------- one test per site that opens a FileReader

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("the official database is not held open after the constructor has read it")
    void constructorReleasesTheOfficialDatabase() throws IOException {
        write(officialDatabase, "{}");

        // isAgent=false is what makes the constructor read the official database. The file has to
        // parse, otherwise the constructor falls back to fetching it over the network.
        new DatabaseMetadataService(false);

        assertDeletable(officialDatabase);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("the unofficial database is not held open after a lookup")
    void lookupReleasesTheUnofficialDatabase() {
        DatabaseMetadataService service = new DatabaseMetadataService(true);

        service.getUnofficialMetadata(UUID_UNDER_TEST);

        assertDeletable(unofficialDatabase);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("the unofficial database is not held open after a refresh")
    void refreshReleasesTheUnofficialDatabase() {
        DatabaseMetadataService service = new DatabaseMetadataService(true);

        service.refreshUnofficialMetadata(metadata("A pack"));

        assertDeletable(unofficialDatabase);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("the unofficial database is not held open after a clean")
    void cleanReleasesTheUnofficialDatabase() throws IOException {
        write(officialDatabase, "{}");
        write(unofficialDatabase, "{}");
        DatabaseMetadataService service = new DatabaseMetadataService(false);

        service.cleanUnofficialDatabase();

        assertDeletable(unofficialDatabase);
    }

    // ------------------------------------------------- the behaviour must not change

    @Test
    @DisplayName("metadata written to the unofficial database is read back unchanged")
    void writtenMetadataSurvivesARoundTrip() {
        DatabaseMetadataService service = new DatabaseMetadataService(true);

        service.refreshUnofficialMetadata(metadata("Cornebidouille"));
        Optional<DatabasePackMetadata> read = service.getUnofficialMetadata(UUID_UNDER_TEST);

        assertTrue(read.isPresent(), "the pack that was just written must be found");
        assertEquals(UUID_UNDER_TEST, read.get().getUuid());
        assertEquals("Cornebidouille", read.get().getTitle());
        assertEquals("A description", read.get().getDescription());
        assertEquals("data:image/png;base64,AAAA", read.get().getThumbnail());
        assertFalse(read.get().isOfficial(), "a pack from the unofficial database is not official");
    }

    @Test
    @DisplayName("a second refresh replaces the entry rather than duplicating it")
    void refreshingTwiceKeepsTheLatestValue() {
        DatabaseMetadataService service = new DatabaseMetadataService(true);

        service.refreshUnofficialMetadata(metadata("First title"));
        service.refreshUnofficialMetadata(metadata("Second title"));

        assertEquals("Second title", service.getUnofficialMetadata(UUID_UNDER_TEST).get().getTitle());
    }

    @Test
    @DisplayName("an unknown pack yields nothing rather than failing")
    void unknownPackIsAbsent() {
        DatabaseMetadataService service = new DatabaseMetadataService(true);

        assertFalse(service.getUnofficialMetadata("00000000-0000-0000-0000-000000000000").isPresent());
    }

    @Test
    @DisplayName("the constructor creates an empty unofficial database when there is none")
    void missingUnofficialDatabaseIsInitialised() throws IOException {
        assertFalse(Files.exists(unofficialDatabase));

        new DatabaseMetadataService(true);

        assertTrue(Files.exists(unofficialDatabase));
        assertEquals("{}", new String(Files.readAllBytes(unofficialDatabase), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------- helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private DatabasePackMetadata metadata(String title) {
        return new DatabasePackMetadata(UUID_UNDER_TEST, title, "A description",
                "data:image/png;base64,AAAA", false);
    }

    /**
     * Deletes the file, now, and fails if it cannot be deleted.
     *
     * <p>Deliberately a single attempt. Retrying, sleeping or asking for a collection would each let
     * the JDK's cleaner close the leaked handle and make this pass while the defect was still there.
     */
    private void assertDeletable(Path path) {
        assertTrue(Files.exists(path), "fixture problem: the database file is not there to be deleted");
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new AssertionError("the service is still holding " + path.getFileName()
                    + " open: " + e, e);
        }
    }
}
