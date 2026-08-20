/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * A ledger of the conversions STUdio performed, kept beside the metadata databases.
 *
 * <p>It answers one question: was this converted artefact produced by us, and from what content?
 * Nothing else in the library records that. A file name says nothing about provenance — the
 * {@code .converted_} convention is a label STUdio writes, not a fact it can check — and a UUID is
 * shared by every version of a pack. So the answer has to be written down when it is known, which is
 * at the moment a conversion succeeds.
 *
 * <p>Kept separate from {@code DatabaseMetadataService} on purpose. That is a catalogue of packs
 * keyed by UUID — title, description, thumbnail — living in the {@code metadata} module and shared
 * with the agent. This is a ledger of library artefacts keyed by file name. Merging them would put a
 * library concern into a module that has nothing to do with the library.
 *
 * <h2>Three states</h2>
 *
 * <p>{@code ABSENT} is the ordinary first run: nothing is known, and writing creates the ledger.
 * {@code LOADED} is the normal case. {@code INVALID} is the one that needed a decision: the file
 * exists but does not parse, or announces a schema this version cannot read.
 *
 * <p>In {@code INVALID} the ledger yields nothing <em>and refuses to be written</em>. The tempting
 * alternative — treat a parse failure as an empty ledger — turns one bad read into the silent
 * destruction of every proof still in that file the next time anything is recorded. Provenance is
 * not critical enough to justify that: losing it costs a confirmation dialog, whereas overwriting it
 * costs the user something they cannot get back. The file is left exactly as it was found, and the
 * failure is logged with its path so a person can decide.
 *
 * <p>Every operation re-reads the file. It is small, the operations are rare — a conversion, a
 * transfer decision — and a cached copy would go stale against another instance or against the user
 * editing the file by hand.
 */
public class ConversionProvenanceStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversionProvenanceStore.class);

    public static final String CONVERSIONS_DB_PROP = "studio.db.conversions";
    public static final String CONVERSIONS_DB_JSON_PATH = "/.studio/db/conversions.json";

    /** Bumped when the canonical form of a record changes; an unknown version reads as INVALID. */
    public static final int SCHEMA_VERSION = 1;

    private static final String SCHEMA_VERSION_FIELD = "schemaVersion";
    private static final String CONVERSIONS_FIELD = "conversions";
    private static final String TEMPORARY_SUFFIX = ".new";

    /** Whether the ledger can be read, does not exist yet, or exists and cannot be understood. */
    public enum State {
        LOADED, ABSENT, INVALID
    }

    /**
     * How the ledger stands right now. Reads the file; callers that also need a record should use
     * {@link #find(String)}, which reports the same states through its own result.
     */
    public State state() {
        Path path = storePath();
        if (!Files.isRegularFile(path)) {
            return State.ABSENT;
        }
        return read(path).isPresent() ? State.LOADED : State.INVALID;
    }

    /**
     * The record for a converted artefact, by the file name the library knows it under.
     *
     * <p>The name is a lookup key and never a proof: finding a record says only that one was filed
     * under that name, not that the artefact on disk is the one it describes. Establishing that is
     * the caller's job, by comparing digests.
     *
     * <p>Empty covers every case in which no record is available — absent ledger, unreadable ledger,
     * no entry, unusable entry. None of them means the artefacts differ.
     */
    public Optional<ConversionRecord> find(String artifactName) {
        Path path = storePath();
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        Optional<JsonObject> root = read(path);
        if (!root.isPresent()) {
            return Optional.empty();
        }
        JsonObject conversions = root.get().getAsJsonObject(CONVERSIONS_FIELD);
        if (conversions == null || !conversions.has(artifactName)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(gson().fromJson(conversions.get(artifactName),
                    ConversionRecord.class));
        } catch (RuntimeException e) {
            LOGGER.warn("Unusable provenance record for `" + artifactName + "`", e);
            return Optional.empty();
        }
    }

    /**
     * Files a record for a converted artefact, replacing any record already under that name.
     *
     * @return whether the ledger now holds it. False means the record was not filed, for one of two
     *         reasons that do not carry the same promise about the file on disk. If the ledger is
     *         {@code INVALID} nothing is attempted and the file is untouched. If a write was
     *         attempted and failed, the state of the ledger afterwards is <strong>not known</strong>
     *         — see {@link #write}. Neither case grants provenance to anything: whatever is on disk
     *         next time is read as {@code LOADED}, {@code ABSENT} or {@code INVALID}, and only the
     *         first can prove anything.
     */
    public boolean record(String artifactName, ConversionRecord record) {
        Path path = storePath();
        JsonObject root;
        if (Files.isRegularFile(path)) {
            Optional<JsonObject> existing = read(path);
            if (!existing.isPresent()) {
                LOGGER.error("Refusing to write over a provenance database that could not be read: "
                        + path + ". Move or delete that file to start a new one; nothing else will "
                        + "touch it, and conversions will simply go unproven until then.");
                return false;
            }
            root = existing.get();
        } else {
            root = newRoot();
        }

        JsonObject conversions = root.getAsJsonObject(CONVERSIONS_FIELD);
        conversions.add(artifactName, gson().toJsonTree(record));
        return write(path, root);
    }

    /**
     * Installs a new version of the ledger: serialise in full to a sibling temporary, close it, then
     * move it over the target.
     *
     * <p>An atomic move is attempted first and a non-atomic replacement is accepted if the
     * filesystem cannot do it — logged, never silent. The driver refuses that fallback when it
     * installs the device's pack index, and rightly: a torn {@code .pi} costs the user their packs.
     * Here a torn ledger costs a confirmation dialog, no user content is lost and the program stays
     * usable, so refusing to run on a filesystem without atomic moves would trade a harmless
     * degradation for a hard failure.
     *
     * <p>What that costs is a weaker promise, and it is worth stating rather than glossing over.
     * Three phases, with three different guarantees:
     *
     * <ul>
     *   <li>while serialising, writing or closing the temporary, the ledger has not been opened at
     *       all and is untouched;</li>
     *   <li>when the atomic move succeeds, the substitution has whatever atomicity the filesystem
     *       provides;</li>
     *   <li>when the non-atomic replacement is used and <em>fails</em>, the state of the ledger
     *       afterwards is <strong>unknown</strong>. It may be the old one, the new one, or neither.
     *       This method reports the failure and claims nothing more.</li>
     * </ul>
     *
     * <p>That last case needs no stronger promise, which is why the fallback is acceptable. A ledger
     * that ends up missing reads as {@code ABSENT}; one that ends up damaged reads as
     * {@code INVALID}; both yield no provenance, so every affected conversion becomes unproven and
     * is confirmed with the user instead of reused silently. No pack, no conversion and no user
     * content depends on this file — losing it costs a dialog, and never a wrong answer.
     */
    private boolean write(Path path, JsonObject root) {
        Path temporary = path.resolveSibling(path.getFileName() + TEMPORARY_SUFFIX);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                gson().toJson(root, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                LOGGER.warn("Atomic move unavailable for " + path + ", replacing without it", e);
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to write provenance database: " + path, e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                LOGGER.warn("Failed to remove the temporary provenance file: " + temporary, cleanup);
            }
            return false;
        }
    }

    /**
     * Parses the ledger, or reports that it cannot be understood.
     *
     * <p>A missing or unknown {@code schemaVersion} is as unreadable as broken JSON, and matters
     * more: a file written by a later version of STUdio must be left alone rather than reinterpreted
     * or overwritten by this one.
     *
     * <p>A stale {@code conversions.json.new} left by an interrupted write is never consulted. Only
     * the ledger itself is read; the temporary is inert until the next write truncates it.
     */
    private Optional<JsonObject> read(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            // The instance form, not the static parseReader: gson is pinned at 2.8.5 by the parent,
            // and the static overload only exists from 2.8.6. Same call the metadata database makes.
            JsonElement parsed = new JsonParser().parse(reader);
            if (!parsed.isJsonObject()) {
                LOGGER.error("Provenance database is not a JSON object: " + path);
                return Optional.empty();
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement version = root.get(SCHEMA_VERSION_FIELD);
            if (version == null || !version.isJsonPrimitive() || version.getAsInt() != SCHEMA_VERSION) {
                LOGGER.error("Provenance database has an unsupported schema version, leaving it "
                        + "untouched: " + path);
                return Optional.empty();
            }
            if (!root.has(CONVERSIONS_FIELD) || !root.get(CONVERSIONS_FIELD).isJsonObject()) {
                LOGGER.error("Provenance database has no usable conversions object: " + path);
                return Optional.empty();
            }
            return Optional.of(root);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Failed to read provenance database, leaving it untouched: " + path, e);
            return Optional.empty();
        }
    }

    private static JsonObject newRoot() {
        JsonObject root = new JsonObject();
        root.addProperty(SCHEMA_VERSION_FIELD, SCHEMA_VERSION);
        root.add(CONVERSIONS_FIELD, new JsonObject());
        return root;
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().create();
    }

    /** Path may be overridden by system property {@code studio.db.conversions}. */
    static Path storePath() {
        return Paths.get(System.getProperty(CONVERSIONS_DB_PROP,
                System.getProperty("user.home") + CONVERSIONS_DB_JSON_PATH));
    }
}
