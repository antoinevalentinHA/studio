/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.io.FileUtils;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.reader.binary.BinaryStoryPackReader;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.utils.PackAssetsCompression;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.core.v1.writer.binary.BinaryStoryPackWriter;
import studio.core.v1.writer.fs.FsStoryPackWriter;
import studio.metadata.DatabaseMetadataService;
import studio.metadata.DatabasePackMetadata;
import studio.webui.model.LibraryPack;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LibraryService {
    public static final String LOCAL_LIBRARY_PROP = "studio.library";
    public static final String LOCAL_LIBRARY_PATH = "/.studio/library/";
    public static final String TMP_DIR_PROP = "studio.tmpdir";
    public static final String TMP_DIR_PATH = "/.studio/tmp/";

    private final Logger LOGGER = LoggerFactory.getLogger(LibraryService.class);

    private final DatabaseMetadataService databaseMetadataService;

    /*
     * Parsed packs, keyed by path, alongside the file attributes they were read with.
     *
     * Reading a pack means walking its whole archive, so the result is worth keeping. What is not
     * worth keeping is the assumption that a path still holds the same file: this cache used to
     * store the parse alone and hand it back for five minutes regardless of what had happened on
     * disk, which meant a pack overwritten in place kept reporting its previous title, timestamp and
     * everything else until the entry expired. Overwriting in place is not unusual — saving from the
     * editor writes to the file name the pack was opened under, and dropping the same file into the
     * library twice does the same thing.
     *
     * So an entry now carries the size and modification time observed at the moment of the parse,
     * and is reused only while both still match. See readPackFileCached.
     */
    private final Cache<Path, CachedPack> cachedPacks = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5)).build();

    /**
     * A parse, and the observable state of the file it came from.
     *
     * <p>{@code pack} is an {@code Optional} because a failed parse is a result like any other and
     * is cached like any other — but a failed parse of <em>that</em> version of the file. A pack
     * copied into the library and read while the copy was still running fails to parse, and used to
     * stay invisible for five minutes after the copy finished; bound to the attributes it was read
     * with, the failure is forgotten as soon as the file changes.
     */
    private static final class CachedPack {
        private final Optional<LibraryPack> pack;
        private final long size;
        private final FileTime lastModifiedTime;

        private CachedPack(Optional<LibraryPack> pack, BasicFileAttributes attributes) {
            this.pack = pack;
            this.size = attributes.size();
            this.lastModifiedTime = attributes.lastModifiedTime();
        }

        private boolean matches(BasicFileAttributes attributes) {
            return size == attributes.size() && lastModifiedTime.equals(attributes.lastModifiedTime());
        }
    }

    private final ContentDigest contentDigest = new ContentDigest();

    private final ConversionProvenanceStore provenanceStore = new ConversionProvenanceStore();

    public LibraryService(DatabaseMetadataService databaseMetadataService) {
        this.databaseMetadataService = databaseMetadataService;

        // Create the local library folder if needed
        File libraryFolder = new File(libraryPath());
        if (!libraryFolder.exists() || !libraryFolder.isDirectory()) {
            try {
                Files.createDirectories(Paths.get(libraryPath()));
            } catch (IOException e) {
                LOGGER.error("Failed to initialize local library", e);
                throw new IllegalStateException("Failed to initialize local library");
            }
        }

        // Create the temp folder if needed
        File tmpFolder = new File(tmpDirPath());
        if (!tmpFolder.exists() || !tmpFolder.isDirectory()) {
            try {
                Files.createDirectories(Paths.get(tmpDirPath()));
            } catch (IOException e) {
                LOGGER.error("Failed to initialize temp folder", e);
                throw new IllegalStateException("Failed to initialize temp folder");
            }
        }
    }

    public JsonObject libraryInfos() {
        return new JsonObject()
                .put("path", libraryPath());
    }

    public JsonArray packs() {
        // Check that local library folder exists
        File libraryFolder = new File(libraryPath());
        if (!libraryFolder.exists() || !libraryFolder.isDirectory()) {
            return new JsonArray();
        } else {
            // First, refresh unofficial database with metadata from archive packs
            try (Stream<Path> paths = Files.walk(Paths.get(libraryPath()), 1)) {
                paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".zip"))
                        .map(this::readPackFileCached)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        // Group packs by UUID
                        .collect(Collectors.groupingBy(p -> p.getMetadata().getUuid()))
                        .entrySet()
                        .forEach(entry -> {
                            List<LibraryPack> packs = entry.getValue();
                            packs.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
                            LOGGER.debug("Refreshing metadata for pack `" + entry.getKey() + "` from file `" + packs.get(0).getPath() + "`");
                            this.readPackFile(packs.get(0).getPath()).ifPresent(
                                    meta -> databaseMetadataService.refreshUnofficialMetadata(
                                            new DatabasePackMetadata(
                                                    meta.getMetadata().getUuid(),
                                                    meta.getMetadata().getTitle(),
                                                    meta.getMetadata().getDescription(),
                                                    Optional.ofNullable(meta.getMetadata().getThumbnail()).map(thumb -> "data:image/png;base64," + Base64.getEncoder().encodeToString(thumb)).orElse(null),
                                                    false
                                            )
                                    )
                            );
                        });
            } catch (IOException e) {
                LOGGER.error("Failed to read packs from local library", e);
                throw new RuntimeException(e);
            }

            // List pack files in library folder
            try (Stream<Path> paths = Files.walk(Paths.get(libraryPath()), 1)) {
                return new JsonArray(
                        paths
                                .filter(path -> !path.equals(Paths.get(libraryPath())))
                                .map(this::readPackFileCached)
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                // Group packs by UUID
                                .collect(Collectors.groupingBy(p -> p.getMetadata().getUuid()))
                                .entrySet().stream()
                                .collect(Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> new JsonArray(
                                                entry.getValue().stream()
                                                        // Sort packs by timestamp descending
                                                        .sorted((a,b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                                                        .map(this::getPackMetadata)
                                                        .collect(Collectors.toList())
                                        )
                                ))
                                .entrySet().stream()
                                .map(entry -> new JsonObject().put("uuid", entry.getKey()).put("packs", entry.getValue()))
                                .collect(Collectors.toList())
                );
            } catch (IOException e) {
                LOGGER.error("Failed to read packs from local library", e);
                throw new RuntimeException(e);
            }
        }
    }

    public Optional<File> getRawPackFile(String packPath) {
        // Only ever a direct library entry: whatever this returns is sent straight to the client.
        return libraryEntry(packPath).map(Path::toFile);
    }

    public Optional<Path> addConvertedRawPackFile(String packPath, Boolean allowEnriched) {
        // Archive format packs must first be converted to raw format
        if (packPath.endsWith(".zip")) {
            try {
                // Observed before the conversion reads it; observed again afterwards, and
                // recorded only if the two agree. See recordProvenance.
                Optional<String> sourceBefore = sourceIdentity(packPath);
                File tmp = createTempFile(packPath, ".pack").toFile();

                LOGGER.info("Pack is in archive format. Converting to raw format and storing in temporary file: " + tmp.getAbsolutePath());

                LOGGER.info("Reading archive format pack");
                ArchiveStoryPackReader packReader = new ArchiveStoryPackReader();
                StoryPack storyPack;
                try (FileInputStream fis = new FileInputStream(requireLibraryEntry(packPath).toFile())) {
                    storyPack = packReader.read(fis);
                }

                // Uncompress pack assets
                StoryPack uncompressedPack = storyPack;
                if (PackAssetsCompression.hasCompressedAssets(storyPack)) {
                    LOGGER.info("Uncompressing pack assets");
                    uncompressedPack = PackAssetsCompression.withUncompressedAssets(storyPack);
                }

                LOGGER.info("Writing raw format pack");
                BinaryStoryPackWriter packWriter = new BinaryStoryPackWriter();
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    packWriter.write(uncompressedPack, fos, allowEnriched);
                }

                String destinationFileName = requirePackUuid(storyPack.getUuid()) + ".converted_" + System.currentTimeMillis() + ".pack";
                Path destinationPath = requireLibraryEntry(destinationFileName);
                LOGGER.info("Moving raw format pack into local library: " + destinationPath);
                Files.move(tmp.toPath(), destinationPath);
                recordProvenance(packPath, sourceBefore, destinationPath, "raw");

                return Optional.of(Paths.get(destinationFileName));
            } catch (Exception e) {
                LOGGER.error("Failed to convert archive format pack to raw format", e);
                throw new RuntimeException("Failed to convert archive format pack to raw format", e);
            }
        } else if (packPath.endsWith(".pack")) {
            LOGGER.error("Pack is already in raw format");
            throw new RuntimeException("Pack is already in raw format");
        } else {
            try {
                // Observed before the conversion reads it; observed again afterwards, and
                // recorded only if the two agree. See recordProvenance.
                Optional<String> sourceBefore = sourceIdentity(packPath);
                File tmp = createTempFile(packPath, ".pack").toFile();

                LOGGER.info("Pack is in FS format. Converting to raw format and storing in temporary file: " + tmp.getAbsolutePath());

                LOGGER.info("Reading FS format pack");
                FsStoryPackReader packReader = new FsStoryPackReader();
                StoryPack storyPack = packReader.read(requireLibraryEntry(packPath));

                // Uncompress pack assets
                StoryPack uncompressedPack = storyPack;
                if (PackAssetsCompression.hasCompressedAssets(storyPack)) {
                    LOGGER.info("Uncompressing pack assets");
                    uncompressedPack = PackAssetsCompression.withUncompressedAssets(storyPack);
                }

                LOGGER.info("Writing raw format pack");
                BinaryStoryPackWriter packWriter = new BinaryStoryPackWriter();
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    packWriter.write(uncompressedPack, fos, allowEnriched);
                }

                String destinationFileName = requirePackUuid(storyPack.getUuid()) + ".converted_" + System.currentTimeMillis() + ".pack";
                Path destinationPath = requireLibraryEntry(destinationFileName);
                LOGGER.info("Moving raw format pack into local library: " + destinationPath);
                Files.move(tmp.toPath(), destinationPath);
                recordProvenance(packPath, sourceBefore, destinationPath, "raw");

                return Optional.of(Paths.get(destinationFileName));
            } catch (Exception e) {
                LOGGER.error("Failed to convert FS format pack to raw format", e);
                throw new RuntimeException("Failed to convert FS format pack to raw format", e);
            }
        }
    }

    public Optional<Path> addConvertedArchivePackFile(String packPath) {
        // Binary format packs must first be converted to archive format
        if (packPath.endsWith(".zip")) {
            LOGGER.error("Pack is already in archive format");
            throw new RuntimeException("Pack is already in archive format");
        } else if (packPath.endsWith(".pack")) {
            try {
                // Observed before the conversion reads it; observed again afterwards, and
                // recorded only if the two agree. See recordProvenance.
                Optional<String> sourceBefore = sourceIdentity(packPath);
                File tmp = createTempFile(packPath, ".zip").toFile();

                LOGGER.info("Pack is in raw format. Converting to archive format and storing in temporary file: " + tmp.getAbsolutePath());

                LOGGER.info("Reading raw format pack");
                BinaryStoryPackReader packReader = new BinaryStoryPackReader();
                StoryPack storyPack;
                try (FileInputStream fis = new FileInputStream(requireLibraryEntry(packPath).toFile())) {
                    storyPack = packReader.read(fis);
                }

                // Compress pack assets
                LOGGER.info("Compressing pack assets");
                StoryPack compressedPack = PackAssetsCompression.withCompressedAssets(storyPack);

                LOGGER.info("Writing archive format pack");
                ArchiveStoryPackWriter packWriter = new ArchiveStoryPackWriter();
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    packWriter.write(compressedPack, fos);
                }

                String destinationFileName = requirePackUuid(compressedPack.getUuid()) + ".converted_" + System.currentTimeMillis() + ".zip";
                Path destinationPath = requireLibraryEntry(destinationFileName);
                LOGGER.info("Moving archive format pack into local library: " + destinationPath);
                Files.move(tmp.toPath(), destinationPath);
                recordProvenance(packPath, sourceBefore, destinationPath, "archive");

                return Optional.of(Paths.get(destinationFileName));
            } catch (Exception e) {
                LOGGER.error("Failed to convert raw format pack to archive format", e);
                throw new RuntimeException("Failed to convert raw format pack to archive format", e);
            }
        } else {
            try {
                // Observed before the conversion reads it; observed again afterwards, and
                // recorded only if the two agree. See recordProvenance.
                Optional<String> sourceBefore = sourceIdentity(packPath);
                File tmp = createTempFile(packPath, ".zip").toFile();

                LOGGER.info("Pack is in FS format. Converting to archive format and storing in temporary file: " + tmp.getAbsolutePath());

                LOGGER.info("Reading FS format pack");
                FsStoryPackReader packReader = new FsStoryPackReader();
                StoryPack storyPack = packReader.read(requireLibraryEntry(packPath));

                // No need to compress pack assets

                LOGGER.info("Writing archive format pack");
                ArchiveStoryPackWriter packWriter = new ArchiveStoryPackWriter();
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    packWriter.write(storyPack, fos);
                }

                String destinationFileName = requirePackUuid(storyPack.getUuid()) + ".converted_" + System.currentTimeMillis() + ".zip";
                Path destinationPath = requireLibraryEntry(destinationFileName);
                LOGGER.info("Moving archive format pack into local library: " + destinationPath);
                Files.move(tmp.toPath(), destinationPath);
                recordProvenance(packPath, sourceBefore, destinationPath, "archive");

                return Optional.of(Paths.get(destinationFileName));
            } catch (Exception e) {
                LOGGER.error("Failed to convert FS format pack to archive format", e);
                throw new RuntimeException("Failed to convert FS format pack to archive format", e);
            }
        }
    }

    public Optional<Path> addConvertedFsPackFile(String packPath, Boolean allowEnriched) {
        // Archive format packs must first be converted to FS format
        if (packPath.endsWith(".zip")) {
            try {
                // Observed before the conversion reads it; observed again afterwards, and
                // recorded only if the two agree. See recordProvenance.
                Optional<String> sourceBefore = sourceIdentity(packPath);
                Path tmp = createTempDirectory(packPath);

                LOGGER.info("Pack to transfer is in archive format. Converting to FS format and storing in temporary folder: " + tmp.toAbsolutePath().toString());

                LOGGER.info("Reading archive format pack");
                ArchiveStoryPackReader packReader = new ArchiveStoryPackReader();
                StoryPack storyPack;
                try (FileInputStream fis = new FileInputStream(requireLibraryEntry(packPath).toFile())) {
                    storyPack = packReader.read(fis);
                }

                // Prepare assets (RLE-encoded BMP, audio must already be MP3)
                LOGGER.info("Converting assets if necessary");
                StoryPack packWithPreparedAssets = PackAssetsCompression.withPreparedAssetsFirmware2dot4(storyPack);

                LOGGER.info("Writing FS format pack");
                FsStoryPackWriter writer = new FsStoryPackWriter();
                Path folderPath = writer.write(packWithPreparedAssets, tmp);

                String destinationFolder = requirePackUuid(packWithPreparedAssets.getUuid()) + ".converted_" + System.currentTimeMillis();
                Path destinationPath = requireLibraryEntry(destinationFolder);
                LOGGER.info("Moving FS format pack into local library: " + destinationPath);
                Files.move(folderPath, destinationPath);
                recordProvenance(packPath, sourceBefore, destinationPath, "fs");

                return Optional.of(Paths.get(destinationFolder));
            } catch (Exception e) {
                LOGGER.error("Failed to convert archive format pack to FS format", e);
                throw new RuntimeException("Failed to convert archive format pack to FS format", e);
            }
        } else if (packPath.endsWith(".pack")) {
            try {
                // Observed before the conversion reads it; observed again afterwards, and
                // recorded only if the two agree. See recordProvenance.
                Optional<String> sourceBefore = sourceIdentity(packPath);
                Path tmp = createTempDirectory(packPath);

                LOGGER.info("Pack is in raw format. Converting to FS format and storing in temporary folder: " + tmp.toAbsolutePath().toString());

                LOGGER.info("Reading raw format pack");
                BinaryStoryPackReader packReader = new BinaryStoryPackReader();
                StoryPack storyPack;
                try (FileInputStream fis = new FileInputStream(requireLibraryEntry(packPath).toFile())) {
                    storyPack = packReader.read(fis);
                }

                // Prepare assets (RLE-encoded BMP, audio must already be MP3)
                LOGGER.info("Converting assets if necessary");
                StoryPack packWithPreparedAssets = PackAssetsCompression.withPreparedAssetsFirmware2dot4(storyPack);

                LOGGER.info("Writing FS format pack");
                FsStoryPackWriter writer = new FsStoryPackWriter();
                Path folderPath = writer.write(packWithPreparedAssets, tmp);

                String destinationFolder = requirePackUuid(packWithPreparedAssets.getUuid()) + ".converted_" + System.currentTimeMillis();
                Path destinationPath = requireLibraryEntry(destinationFolder);
                LOGGER.info("Moving FS format pack into local library: " + destinationPath);
                Files.move(folderPath, destinationPath);
                recordProvenance(packPath, sourceBefore, destinationPath, "fs");

                return Optional.of(Paths.get(destinationFolder));
            } catch (Exception e) {
                LOGGER.error("Failed to convert raw format pack to FS format", e);
                throw new RuntimeException("Failed to convert raw format pack to FS format", e);
            }
        } else {
            LOGGER.error("Pack is already in FS format");
            throw new RuntimeException("Pack is already in FS format");
        }
    }

    /**
     * Whether a converted pack can stand in for a source without asking the user.
     *
     * <p>Called when a pack is dropped on a device and never while listing, because it reads both
     * artefacts in full. Both digests are recomputed here, every time. Neither the size nor the
     * modification time is allowed to stand in for that: C7-0b established they do not identify
     * content, which is fine for deciding whether to re-parse a file and not fine at all for
     * deciding to send bytes to a device without telling anyone.
     *
     * @throws IllegalArgumentException if either name does not denote a direct child of the library
     *                                  folder. That is a malformed request, not an uncertainty.
     */
    public ProvenanceVerdict verifyConversion(String sourcePath, String convertedPath) {
        Path source = requireLibraryEntry(sourcePath);
        Path converted = requireLibraryEntry(convertedPath);
        try {
            Optional<ConversionRecord> recorded = provenanceStore.find(converted.getFileName().toString());
            if (!recorded.isPresent()) {
                // No ledger, an unreadable one, or a conversion made before any of this existed.
                // None of those is a statement that the artefacts differ.
                return ProvenanceVerdict.UNKNOWN;
            }
            Optional<String> currentSource = sourceIdentity(sourcePath);
            Optional<String> currentArtifact = identityOf(converted);
            if (!currentSource.isPresent() || !currentArtifact.isPresent()) {
                // Missing, unreadable, or moving while being read. Nothing was established.
                return ProvenanceVerdict.UNKNOWN;
            }
            boolean sameSource = currentSource.get().equals(recorded.get().getSourceSha256());
            boolean sameArtifact = currentArtifact.get().equals(recorded.get().getArtifactSha256());
            return sameSource && sameArtifact ? ProvenanceVerdict.MATCH : ProvenanceVerdict.MISMATCH;
        } catch (RuntimeException e) {
            // An unexpected failure is an absence of knowledge, never a proof.
            LOGGER.error("Failed to verify the provenance of `" + convertedPath + "`", e);
            return ProvenanceVerdict.UNKNOWN;
        }
    }

    /** The identity of a library artefact, read as a file or as a tree according to its name. */
    private Optional<String> identityOf(Path artifact) {
        return kindOf(artifact.getFileName().toString()) == ConversionRecord.Kind.FILE
                ? contentDigest.ofFile(artifact)
                : contentDigest.ofTree(artifact);
    }

    /**
     * Resolves a name the client supplied against the library, refusing anything that is not a
     * direct child of it.
     *
     * <p>Uncertainty about two packs is a verdict; being asked about a file somewhere else on the
     * disk is not a question this is willing to answer. Without this, the two names below would let
     * a caller point the digests at arbitrary paths, and a verification endpoint would become a way
     * to probe the filesystem. The listing only ever offers names from one directory, so requiring
     * exactly that costs nothing legitimate.
     *
     * @throws IllegalArgumentException if the name is empty, or escapes the library folder, or names
     *                                  something nested inside it
     */
    private Path requireLibraryEntry(String name) {
        return libraryEntry(name).orElseThrow(
                () -> new IllegalArgumentException("Not an entry of the local library: " + name));
    }

    /**
     * The single authority turning a name into a path this service is willing to act on.
     *
     * <p>A library entry is <strong>a direct child of the library folder</strong>. That is not a
     * restriction invented here: the listing walks the folder one level deep, the client only ever
     * receives names taken from that listing, and every conversion returns a bare name. Anything
     * else — a nested path, an absolute one, a climb out and back in, {@code .} or {@code ..} — is
     * refused.
     *
     * <p>Refused, never repaired. A caller asking for the wrong thing is told no rather than handed
     * a different thing, because silently rewriting a path is how a caller ends up acting on
     * something it did not name.
     *
     * <p>Containment is measured against the <em>configured</em> root, normalised lexically and not
     * resolved through links. A library that a user has placed behind a symbolic link is a perfectly
     * ordinary setup and stays supported; resolving the root to its real path would break it for no
     * security gain, since the question is whether an <em>entry</em> leaves the library, not where
     * the library itself lives.
     *
     * <p>Which is why lexical containment is not the whole check. An entry that already exists is
     * examined with {@code NOFOLLOW_LINKS} and refused if it is a symbolic link, an NTFS junction or
     * any other special entry: those are lexically direct children while pointing somewhere else
     * entirely, and a recursive delete would follow one straight out of the library. Junctions in
     * particular report {@code isSymbolicLink()} as false — measured in C7-2b — so {@code isOther()}
     * is what catches them.
     *
     * <p>An entry that does not exist yet is allowed: an upload and a conversion both have to name a
     * destination before creating it. The lexical rule still applies to it.
     */
    Optional<Path> libraryEntry(String name) {
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        Path libraryRoot = Paths.get(libraryPath()).toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = libraryRoot.resolve(name).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            LOGGER.warn("Not a usable library entry name: " + name);
            return Optional.empty();
        }
        if (!libraryRoot.equals(resolved.getParent())) {
            LOGGER.warn("Refused a name that is not a direct entry of the local library: " + name);
            return Optional.empty();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(resolved, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                LOGGER.warn("Refused a library entry that is a link or a special entry: " + name);
                return Optional.empty();
            }
        } catch (NoSuchFileException absent) {
            // A destination that does not exist yet. The lexical rule above already holds.
        } catch (IOException e) {
            LOGGER.warn("Could not examine a library entry, refusing it: " + name, e);
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    /**
     * The identifier a conversion output is named after, refused unless it really is one.
     *
     * <p>The name of every converted artefact starts with the pack's UUID, and that UUID comes from
     * the pack's own content — from a {@code story.json} for an archive, from a folder name for an
     * fs pack. It is therefore not trusted input, and a value containing a separator would compose a
     * destination path outside the library before anything else had a chance to object.
     *
     * <p>Checked by parsing it as a UUID rather than by looking for dangerous characters. A blacklist
     * only refuses what it was told to think of; this states what is required. And it requires
     * nothing new: {@code BinaryStoryPackWriter} and {@code FsStoryPackWriter} already call
     * {@code UUID.fromString} on these values, as does the device driver in five places. All this
     * does is ask the question before a path is built rather than after.
     */
    private static String requirePackUuid(String uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("A pack UUID is required to name a conversion");
        }
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a usable pack UUID: " + uuid, e);
        }
        return uuid;
    }

    /**
     * The content identity of a library artefact used as a conversion source.
     *
     * <p>Package-private and overridable so a test can make the source change between the two
     * observations that bracket a conversion, deterministically and without timing.
     */
    Optional<String> sourceIdentity(String packPath) {
        Path source = requireLibraryEntry(packPath);
        return kindOf(packPath) == ConversionRecord.Kind.FILE
                ? contentDigest.ofFile(source)
                : contentDigest.ofTree(source);
    }

    /** A library artefact is a file when its name says so, and a tree otherwise. */
    private static ConversionRecord.Kind kindOf(String name) {
        return name.endsWith(".zip") || name.endsWith(".pack")
                ? ConversionRecord.Kind.FILE
                : ConversionRecord.Kind.TREE;
    }

    /**
     * Writes down what this conversion was made from, once the artefact is installed and only if
     * that can be said truthfully.
     *
     * <p>The source is observed twice, before the conversion reads it and again now, and a record is
     * written only if the two observations are identical. Observing once would not do. Observe only
     * before, and a source edited during the conversion is recorded as the one that produced an
     * artefact made from something else. Observe only after, and the opposite: the edited source is
     * recorded, so later it will match, and an artefact that did not come from it is reused without
     * a word — the silent wrong transfer this whole line of work exists to prevent.
     *
     * <p>What the bracket establishes is narrower than it may look, and worth stating plainly: two
     * stable observations of the source, taken before and after the whole conversion, were equal. It
     * is not a filesystem transaction, and it does not prove that the bytes hashed are the bytes the
     * converter consumed — a change followed by an exact restoration inside the window is invisible
     * to it. It does close the two ordinary races: a source edited during the conversion and left
     * edited, and a source edited before the final observation.
     *
     * <p>Nothing here can make a conversion fail. The artefact is installed before this runs, and
     * every way of failing to describe it — an unstable source, an unreadable artefact, a ledger
     * that cannot be written — ends the same way: a log line, no record, and the conversion returned
     * to the caller exactly as it would have been. The artefact simply stays unproven, which is what
     * every artefact in every library is today.
     */
    private void recordProvenance(String packPath, Optional<String> sourceBefore, Path artifact,
                                  String targetFormat) {
        try {
            String artifactName = artifact.getFileName().toString();
            if (!sourceBefore.isPresent()) {
                LOGGER.warn("No provenance for `" + artifactName + "`: the source could not be read"
                        + " before the conversion");
                return;
            }
            Optional<String> sourceAfter = sourceIdentity(packPath);
            if (!sourceAfter.isPresent()) {
                LOGGER.warn("No provenance for `" + artifactName + "`: the source could not be read"
                        + " after the conversion");
                return;
            }
            if (!sourceBefore.get().equals(sourceAfter.get())) {
                LOGGER.warn("No provenance for `" + artifactName + "`: `" + packPath + "` changed"
                        + " while it was being converted, so it cannot be recorded as the source");
                return;
            }
            ConversionRecord.Kind artifactKind = kindOf(artifactName);
            Optional<String> artifactDigest = artifactKind == ConversionRecord.Kind.FILE
                    ? contentDigest.ofFile(artifact)
                    : contentDigest.ofTree(artifact);
            if (!artifactDigest.isPresent()) {
                LOGGER.warn("No provenance for `" + artifactName + "`: the artefact could not be read");
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(artifact, BasicFileAttributes.class);
            ConversionRecord record = new ConversionRecord(
                    Paths.get(packPath).getFileName().toString(), kindOf(packPath), sourceBefore.get(),
                    artifactKind, artifactDigest.get(), attributes.size(),
                    attributes.lastModifiedTime().toMillis(), targetFormat, ApplicationVersion.current());
            if (!provenanceStore.record(artifactName, record)) {
                LOGGER.warn("No provenance for `" + artifactName + "`: the provenance database could"
                        + " not be written. The conversion is unaffected.");
            }
        } catch (IOException | RuntimeException e) {
            // Describing a conversion must never undo one.
            LOGGER.error("Failed to record provenance for a conversion that succeeded", e);
        }
    }

    public boolean addPackFile(String destPath, String uploadedFilePath) {
        try {
            // Copy temporary file to local library
            File src = new File(uploadedFilePath);
            Optional<Path> destination = libraryEntry(destPath);
            if (!destination.isPresent()) {
                // Refused before anything is deleted, moved or created.
                return false;
            }
            File dest = destination.get().toFile();
            if (dest.exists()) {
                boolean deleted = dest.delete();
                // Handle failure
                if (!deleted) {
                    return false;
                }
            }
            FileUtils.moveFile(src, dest);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to add pack to local library", e);
            throw new RuntimeException(e);
        }
    }

    public boolean deletePack(String packPath) {
        File libraryFolder = new File(libraryPath());
        if (!libraryFolder.exists() || !libraryFolder.isDirectory()) {
            return false;
        } else {
            try {
                Optional<Path> entry = libraryEntry(packPath);
                if (!entry.isPresent()) {
                    // The log below used to claim this while only checking that the file existed —
                    // which one outside the folder does too. Now the claim is true.
                    LOGGER.error("Cannot remove pack from library because it is not in the folder");
                    return false;
                }
                File packFile = entry.get().toFile();
                if (packFile.exists()) {
                    FileUtils.forceDelete(packFile);
                    return true;
                } else {
                    LOGGER.error("Cannot remove pack from library because it is not in the folder");
                    return false;
                }
            } catch (IOException e) {
                LOGGER.error("Failed to remove pack from library", e);
                return false;
            }
        }
    }

    public String libraryPath() {
        // Path may be overridden by system property `studio.library`
        return System.getProperty(LOCAL_LIBRARY_PROP, System.getProperty("user.home") + LOCAL_LIBRARY_PATH);
    }

    private String tmpDirPath() {
        // Path may be overridden by system property `studio.tmpdir`
        return System.getProperty(TMP_DIR_PROP, System.getProperty("user.home") + TMP_DIR_PATH);
    }

    private Path createTempFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(Paths.get(tmpDirPath()), prefix, suffix);
    }

    private Path createTempDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(Paths.get(tmpDirPath()), prefix);
    }

    /**
     * The cached parse for a path, if the file there is still the one it was made from.
     *
     * <p>"Still the one" means the same size and the same modification time — one stat, both values,
     * no reading of content. That is a coherence check on fast observable attributes, and its limit
     * is exactly what those attributes cannot see: a replacement of identical size whose modification
     * time has been restored goes unnoticed. Closing that gap would mean reading the file to compare
     * it, which is the work this cache exists to avoid. It is a different question in any case —
     * whether a particular converted pack was produced from a particular source is about the
     * relationship between two files, not about one path at one moment, and nothing here answers it.
     *
     * <p>If the attributes cannot be read at all, the cached entry is not reused and not trusted:
     * whatever {@code readPackFile} makes of the path as it is now becomes the answer.
     */
    private Optional<LibraryPack> readPackFileCached(Path path) {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            LOGGER.debug("Cannot read attributes, parsing without the cache: " + path.toString());
            return readPackFile(path);
        }

        CachedPack cached = cachedPacks.getIfPresent(path);
        if (cached != null && cached.matches(attributes)) {
            return cached.pack;
        }

        Optional<LibraryPack> pack = readPackFile(path);
        cachedPacks.put(path, new CachedPack(pack, attributes));
        return pack;
    }

    private Optional<LibraryPack> readPackFile(Path path) {
        LOGGER.debug("Reading pack file: " + path.toString());
        // Handle all file formats
        if (path.toString().endsWith(".zip")) {
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                LOGGER.debug("Reading archive pack metadata.");
                ArchiveStoryPackReader packReader = new ArchiveStoryPackReader();
                StoryPackMetadata meta = packReader.readMetadata(fis);
                if (meta != null) {
                    return Optional.of(new LibraryPack(path, Files.getLastModifiedTime(path).toMillis() , meta));
                }
                LOGGER.warn("Failed to read metadata for story pack: " + path.toString());
                return Optional.empty();
            } catch (Exception e) {
                LOGGER.error("Failed to read archive-format pack " + path.toString() + " from local library", e);
                return Optional.empty();
            }
        } else if (path.toString().endsWith(".pack")) {
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                LOGGER.debug("Reading raw pack metadata.");
                BinaryStoryPackReader packReader = new BinaryStoryPackReader();
                StoryPackMetadata meta = packReader.readMetadata(fis);
                if (meta != null) {
                    int packSectorSize = (int)Math.ceil((double)path.toFile().length() / 512d);
                    meta.setSectorSize(packSectorSize);
                    return Optional.of(new LibraryPack(path, Files.getLastModifiedTime(path).toMillis() , meta));
                }
                LOGGER.warn("Failed to read metadata for story pack: " + path.toString());
                return Optional.empty();
            } catch (Exception e) {
                LOGGER.error("Failed to read raw format pack " + path.toString() + " from local library", e);
                return Optional.empty();
            }
        } else if (Files.isDirectory(path)) {
            try {
                LOGGER.debug("Reading FS pack metadata.");
                FsStoryPackReader packReader = new FsStoryPackReader();
                StoryPackMetadata meta = packReader.readMetadata(path);
                if (meta != null) {
                    int packSectorSize = (int)Math.ceil((double)path.toFile().length() / 512d);
                    meta.setSectorSize(packSectorSize);
                    return Optional.of(new LibraryPack(path, Files.getLastModifiedTime(path).toMillis() , meta));
                }
                LOGGER.warn("Failed to read metadata for story pack: " + path.toString());
                return Optional.empty();
            } catch (Exception e) {
                LOGGER.error("Failed to read FS format pack " + path.toString() + " from local library", e);
                return Optional.empty();
            }
        }

        // Ignore other files
        return Optional.empty();
    }

    private JsonObject getPackMetadata(LibraryPack pack) {
        JsonObject json = new JsonObject()
                .put("format", pack.getMetadata().getFormat())
                .put("uuid", pack.getMetadata().getUuid())
                .put("version", pack.getMetadata().getVersion())
                .put("path", pack.getPath().getFileName().toString())
                .put("timestamp", pack.getTimestamp())
                .put("nightModeAvailable", pack.getMetadata().isNightModeAvailable());
        Optional.ofNullable(pack.getMetadata().getTitle()).ifPresent(title -> json.put("title", title));
        Optional.ofNullable(pack.getMetadata().getDescription()).ifPresent(desc -> json.put("description", desc));
        Optional.ofNullable(pack.getMetadata().getThumbnail()).ifPresent(thumb -> json.put("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(thumb)));
        Optional.ofNullable(pack.getMetadata().getSectorSize()).ifPresent(size -> json.put("sectorSize", size));
        return databaseMetadataService.getPackMetadata(pack.getMetadata().getUuid())
                .map(metadata -> json
                        .put("title", metadata.getTitle())
                        .put("description", metadata.getDescription())
                        .put("image", metadata.getThumbnail())
                        .put("official", metadata.isOfficial())
                )
                .orElse(json);
    }

}
