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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        return Optional.of(new File(libraryPath() + packPath));
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
                FileInputStream fis = new FileInputStream(libraryPath() + packPath);
                StoryPack storyPack = packReader.read(fis);
                fis.close();

                // Uncompress pack assets
                StoryPack uncompressedPack = storyPack;
                if (PackAssetsCompression.hasCompressedAssets(storyPack)) {
                    LOGGER.info("Uncompressing pack assets");
                    uncompressedPack = PackAssetsCompression.withUncompressedAssets(storyPack);
                }

                LOGGER.info("Writing raw format pack");
                BinaryStoryPackWriter packWriter = new BinaryStoryPackWriter();
                FileOutputStream fos = new FileOutputStream(tmp);
                packWriter.write(uncompressedPack, fos, allowEnriched);
                fos.close();

                String destinationFileName = storyPack.getUuid() + ".converted_" + System.currentTimeMillis() + ".pack";
                Path destinationPath = Paths.get(libraryPath() + destinationFileName);
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
                StoryPack storyPack = packReader.read(Paths.get(libraryPath() + packPath));

                // Uncompress pack assets
                StoryPack uncompressedPack = storyPack;
                if (PackAssetsCompression.hasCompressedAssets(storyPack)) {
                    LOGGER.info("Uncompressing pack assets");
                    uncompressedPack = PackAssetsCompression.withUncompressedAssets(storyPack);
                }

                LOGGER.info("Writing raw format pack");
                BinaryStoryPackWriter packWriter = new BinaryStoryPackWriter();
                FileOutputStream fos = new FileOutputStream(tmp);
                packWriter.write(uncompressedPack, fos, allowEnriched);
                fos.close();

                String destinationFileName = storyPack.getUuid() + ".converted_" + System.currentTimeMillis() + ".pack";
                Path destinationPath = Paths.get(libraryPath() + destinationFileName);
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
                FileInputStream fis = new FileInputStream(libraryPath() + packPath);
                StoryPack storyPack = packReader.read(fis);
                fis.close();

                // Compress pack assets
                LOGGER.info("Compressing pack assets");
                StoryPack compressedPack = PackAssetsCompression.withCompressedAssets(storyPack);

                LOGGER.info("Writing archive format pack");
                ArchiveStoryPackWriter packWriter = new ArchiveStoryPackWriter();
                FileOutputStream fos = new FileOutputStream(tmp);
                packWriter.write(compressedPack, fos);
                fos.close();

                String destinationFileName = compressedPack.getUuid() + ".converted_" + System.currentTimeMillis() + ".zip";
                Path destinationPath = Paths.get(libraryPath() + destinationFileName);
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
                StoryPack storyPack = packReader.read(Paths.get(libraryPath() + packPath));

                // No need to compress pack assets

                LOGGER.info("Writing archive format pack");
                ArchiveStoryPackWriter packWriter = new ArchiveStoryPackWriter();
                FileOutputStream fos = new FileOutputStream(tmp);
                packWriter.write(storyPack, fos);
                fos.close();

                String destinationFileName = storyPack.getUuid() + ".converted_" + System.currentTimeMillis() + ".zip";
                Path destinationPath = Paths.get(libraryPath() + destinationFileName);
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
                FileInputStream fis = new FileInputStream(libraryPath() + packPath);
                StoryPack storyPack = packReader.read(fis);
                fis.close();

                // Prepare assets (RLE-encoded BMP, audio must already be MP3)
                LOGGER.info("Converting assets if necessary");
                StoryPack packWithPreparedAssets = PackAssetsCompression.withPreparedAssetsFirmware2dot4(storyPack);

                LOGGER.info("Writing FS format pack");
                FsStoryPackWriter writer = new FsStoryPackWriter();
                Path folderPath = writer.write(packWithPreparedAssets, tmp);

                String destinationFolder = packWithPreparedAssets.getUuid() + ".converted_" + System.currentTimeMillis();
                Path destinationPath = Paths.get(libraryPath() + destinationFolder);
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
                FileInputStream fis = new FileInputStream(libraryPath() + packPath);
                StoryPack storyPack = packReader.read(fis);
                fis.close();

                // Prepare assets (RLE-encoded BMP, audio must already be MP3)
                LOGGER.info("Converting assets if necessary");
                StoryPack packWithPreparedAssets = PackAssetsCompression.withPreparedAssetsFirmware2dot4(storyPack);

                LOGGER.info("Writing FS format pack");
                FsStoryPackWriter writer = new FsStoryPackWriter();
                Path folderPath = writer.write(packWithPreparedAssets, tmp);

                String destinationFolder = packWithPreparedAssets.getUuid() + ".converted_" + System.currentTimeMillis();
                Path destinationPath = Paths.get(libraryPath() + destinationFolder);
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
     * The content identity of a library artefact used as a conversion source.
     *
     * <p>Package-private and overridable so a test can make the source change between the two
     * observations that bracket a conversion, deterministically and without timing.
     */
    Optional<String> sourceIdentity(String packPath) {
        Path source = Paths.get(libraryPath() + packPath);
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
            File dest = new File(libraryPath() + destPath);
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
                File packFile = new File(libraryPath() + packPath);
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
