/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service.mock;

import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.io.FileUtils;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.reader.binary.BinaryStoryPackReader;
import studio.metadata.DatabaseMetadataService;
import studio.webui.service.IStoryTellerService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MockStoryTellerService implements IStoryTellerService {

    public static final String MOCKED_DEVICE_PATH = "/.studio/device/";
    private static final String PACK_FILE_EXTENSION = ".pack";
    /** Not a pack file, so the listing skips it on its own. */
    private static final String PACK_ORDER_FILE = ".order";
    private static final int BUFFER_SIZE = 1024 * 1024 * 10;

    private final Logger LOGGER = LoggerFactory.getLogger(MockStoryTellerService.class);

    private final EventBus eventBus;

    private final DatabaseMetadataService databaseMetadataService;


    public MockStoryTellerService(EventBus eventBus, DatabaseMetadataService databaseMetadataService) {
        this.eventBus = eventBus;
        this.databaseMetadataService = databaseMetadataService;

        LOGGER.info("Setting up mocked story teller service");

        // Create the mocked device folder if needed
        File libraryFolder = new File(devicePath());
        if (!libraryFolder.exists() || !libraryFolder.isDirectory()) {
            try {
                Files.createDirectories(Paths.get(devicePath()));
            } catch (IOException e) {
                LOGGER.error("Failed to initialize mocked device", e);
                throw new IllegalStateException("Failed to initialize mocked device");
            }
        }
    }

    private String devicePath() {
        return System.getProperty("user.home") + MOCKED_DEVICE_PATH;
    }

    public CompletableFuture<Optional<JsonObject>> deviceInfos() {
        File deviceFolder = new File(devicePath());
        int files = deviceFolder.listFiles().length;
        return CompletableFuture.completedFuture(
                Optional.of(new JsonObject()
                        .put("uuid", "mocked-device")
                        .put("serial", "mocked-serial")
                        .put("firmware", "mocked-version")
                        .put("storage", new JsonObject()
                                .put("size", files)
                                .put("free", 0)
                                .put("taken", files)
                        )
                        .put("error", false)
                )
        );
    }

    public CompletableFuture<JsonArray> packs() {
        // Check that mocked device folder exists
        File deviceFolder = new File(devicePath());
        if (!deviceFolder.exists() || !deviceFolder.isDirectory()) {
            return CompletableFuture.completedFuture(new JsonArray());
        } else {
            // List binary pack files in mocked device folder, in the order last submitted through
            // reorderPacks. The sort is stable, so packs the order does not name keep the order the
            // filesystem gave them.
            List<String> order = packOrder();
            try (Stream<Path> paths = Files.walk(Paths.get(devicePath()))) {
                return CompletableFuture.completedFuture(new JsonArray(
                                paths
                                        .filter(Files::isRegularFile)
                                        .map(path -> this.readBinaryPackFile(path).map(
                                                meta -> this.getPackMetadata(meta, path.getFileName().toString())
                                        ))
                                        .filter(Optional::isPresent)
                                        .map(Optional::get)
                                        .sorted(Comparator.comparingInt(pack -> order.indexOf(pack.getString("uuid"))))
                                        .collect(Collectors.toList())
                        )
                );
            } catch (IOException e) {
                LOGGER.error("Failed to read packs from mocked device", e);
                throw new RuntimeException(e);
            }
        }
    }

    private Optional<StoryPackMetadata> readBinaryPackFile(Path path) {
        LOGGER.debug("Reading pack file: " + path.toString());
        // Handle only binary file format
        if (path.toString().endsWith(".pack")) {
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                LOGGER.debug("Reading binary pack metadata.");
                BinaryStoryPackReader packReader = new BinaryStoryPackReader();
                Optional<StoryPackMetadata> metadata = Optional.of(packReader.readMetadata(fis));
                metadata.map(meta -> {
                    int packSectorSize = (int)Math.ceil((double)path.toFile().length() / 512d);
                    meta.setSectorSize(packSectorSize);
                    return meta;
                });
                return metadata;
            } catch (IOException e) {
                LOGGER.error("Failed to read binary-format pack " + path.toString() + " from mocked device", e);
                return Optional.empty();
            }
        } else if (path.toString().endsWith(".zip")) {
            LOGGER.error("Mocked device should not contain archive-format packs");
            return Optional.empty();
        }

        // Ignore other files
        return Optional.empty();
    }

    public CompletableFuture<Optional<String>> addPack(String uuid, File packFile) {
        // Check that mocked device folder exists
        File deviceFolder = new File(devicePath());
        if (!deviceFolder.exists() || !deviceFolder.isDirectory()) {
            return CompletableFuture.completedFuture(Optional.empty());
        } else {
            String transferId = UUID.randomUUID().toString();
            // Perform transfer asynchronously, and send events on eventbus to monitor progress and end of transfer
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    // Make sure the device does not already contain this pack
                    File destFile = new File(devicePath() + "/" + uuid + ".pack");
                    if (destFile.exists()) {
                        LOGGER.error("Cannot add pack to mocked device because the folder already contains this pack");
                        eventBus.send("storyteller.transfer."+transferId+".done", new JsonObject().put("success", false));
                        return;
                    }
                    try (
                            FileInputStream input = new FileInputStream(packFile);
                            FileOutputStream output = FileUtils.openOutputStream(destFile)
                    ) {
                        long fileSize = packFile.length();
                        final byte[] buffer = new byte[BUFFER_SIZE];
                        long count = 0;
                        int n = 0;
                        while ((n = input.read(buffer)) != -1) {
                            output.write(buffer, 0, n);
                            count += n;
                            // Send events on eventbus to monitor progress
                            double p = count / (double) fileSize;
                            LOGGER.debug("Pack copy progress... " + count + " / " + fileSize + " (" + p + ")");
                            eventBus.send("storyteller.transfer."+transferId+".progress", new JsonObject().put("progress", p));
                        }
                        // Send event on eventbus to signal end of transfer
                        eventBus.send("storyteller.transfer."+transferId+".done", new JsonObject().put("success", true));
                    } catch (IOException e) {
                        LOGGER.error("Failed to add pack to mocked device", e);
                        // Send event on eventbus to signal transfer failure
                        eventBus.send("storyteller.transfer." + transferId + ".done", new JsonObject().put("success", false));
                    }
                }
            }, 1000);
            return CompletableFuture.completedFuture(Optional.of(transferId));
        }
    }

    public CompletableFuture<Boolean> deletePack(String uuid) {
        // Check that mocked device folder exists
        File deviceFolder = new File(devicePath());
        if (!deviceFolder.exists() || !deviceFolder.isDirectory()) {
            return CompletableFuture.completedFuture(false);
        } else {
            try {
                File packFile = new File(devicePath() + "/" + uuid + ".pack");
                if (packFile.exists()) {
                    FileUtils.forceDelete(packFile);
                    return CompletableFuture.completedFuture(true);
                } else {
                    LOGGER.error("Cannot remove pack from mocked device because it is not in the folder");
                    return CompletableFuture.completedFuture(false);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to remove pack from mocked device", e);
                return CompletableFuture.completedFuture(false);
            }
        }
    }

    /**
     * Records the order the packs should be listed in.
     *
     * <p>The mocked device is one file per pack and a folder listing has no order of its own, so the
     * order is written to a file beside them — which is what a real device does too, in its own pack
     * index.
     *
     * <p>The guard is the one {@code FsStoryTellerAsyncDriver.reorderPacks} applies: every submitted
     * UUID must be on the device, and nothing requires the converse, so a partial list is honoured.
     * Copying that rather than improving on it is deliberate: dev mode is the only way to exercise
     * this without hardware, and a mock that behaved better would hide the real device's surprises.
     */
    public CompletableFuture<Boolean> reorderPacks(List<String> uuids) {
        File deviceFolder = new File(devicePath());
        if (!deviceFolder.exists() || !deviceFolder.isDirectory()) {
            return CompletableFuture.completedFuture(false);
        }
        Set<String> onDevice = packUuidsOnDevice();
        if (!onDevice.containsAll(uuids)) {
            LOGGER.error("Cannot reorder packs on mocked device: the list names a pack it does not hold");
            return CompletableFuture.completedFuture(false);
        }
        try {
            Files.write(Paths.get(devicePath(), PACK_ORDER_FILE), uuids, StandardCharsets.UTF_8);
            return CompletableFuture.completedFuture(true);
        } catch (IOException e) {
            LOGGER.error("Failed to write pack order on mocked device", e);
            return CompletableFuture.completedFuture(false);
        }
    }

    private Set<String> packUuidsOnDevice() {
        File[] files = new File(devicePath()).listFiles();
        if (files == null) {
            return Collections.emptySet();
        }
        return Arrays.stream(files)
                .map(File::getName)
                .filter(name -> name.endsWith(PACK_FILE_EXTENSION))
                .map(name -> name.substring(0, name.length() - PACK_FILE_EXTENSION.length()))
                .collect(Collectors.toSet());
    }

    /**
     * The order last submitted, or an empty list when none has been. A pack missing from it sorts by
     * {@code indexOf == -1} and therefore lands at the front, exactly as it does on a real device.
     */
    private List<String> packOrder() {
        Path orderFile = Paths.get(devicePath(), PACK_ORDER_FILE);
        if (!Files.isRegularFile(orderFile)) {
            return Collections.emptyList();
        }
        try {
            return Files.readAllLines(orderFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to read pack order from mocked device", e);
            return Collections.emptyList();
        }
    }

    public CompletableFuture<Optional<String>> extractPack(String uuid, File destFile) {
        // Check that mocked device folder exists
        File deviceFolder = new File(devicePath());
        if (!deviceFolder.exists() || !deviceFolder.isDirectory()) {
            return CompletableFuture.completedFuture(Optional.empty());
        } else {
            String transferId = UUID.randomUUID().toString();
            // Perform transfer asynchronously, and send events on eventbus to monitor progress and end of transfer
            Timer timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    File packFile = new File(devicePath() + "/" + uuid + ".pack");
                    if (packFile.exists()) {
                        // Check that the destination is available
                        if (destFile.exists()) {
                            LOGGER.error("Cannot extract pack from mocked device because the destination file already exists");
                            eventBus.send("storyteller.transfer."+transferId+".done", new JsonObject().put("success", false));
                            return;
                        }
                        try (
                                FileInputStream input = new FileInputStream(packFile);
                                FileOutputStream output = FileUtils.openOutputStream(destFile)
                        ) {
                            long fileSize = packFile.length();
                            final byte[] buffer = new byte[BUFFER_SIZE];
                            long count = 0;
                            int n = 0;
                            while ((n = input.read(buffer)) != -1) {
                                output.write(buffer, 0, n);
                                count += n;
                                // Send events on eventbus to monitor progress
                                double p = count / (double) fileSize;
                                LOGGER.debug("Pack copy progress... " + count + " / " + fileSize + " (" + p + ")");
                                eventBus.send("storyteller.transfer."+transferId+".progress", new JsonObject().put("progress", p));
                            }
                            // Send event on eventbus to signal end of transfer
                            eventBus.send("storyteller.transfer."+transferId+".done", new JsonObject().put("success", true));
                        } catch (IOException e) {
                            LOGGER.error("Failed to extract pack from mocked device", e);
                            // Send event on eventbus to signal transfer failure
                            eventBus.send("storyteller.transfer." + transferId + ".done", new JsonObject().put("success", false));
                        }
                    } else {
                        LOGGER.error("Cannot extract pack from mocked device because it is not in the folder");
                        eventBus.send("storyteller.transfer."+transferId+".done", new JsonObject().put("success", false));
                    }
                }
            }, 1000);
            return CompletableFuture.completedFuture(Optional.of(transferId));
        }
    }

    private JsonObject getPackMetadata(StoryPackMetadata packMetadata, String path) {
        JsonObject json = new JsonObject()
                .put("uuid", packMetadata.getUuid())
                .put("format", packMetadata.getFormat())
                .put("version", packMetadata.getVersion())
                .put("path", path);
        Optional.ofNullable(packMetadata.getTitle()).ifPresent(title -> json.put("title", title));
        Optional.ofNullable(packMetadata.getDescription()).ifPresent(desc -> json.put("description", desc));
        Optional.ofNullable(packMetadata.getThumbnail()).ifPresent(thumb -> json.put("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(thumb)));
        Optional.ofNullable(packMetadata.getSectorSize()).ifPresent(size -> json.put("sectorSize", size));
        return databaseMetadataService.getPackMetadata(packMetadata.getUuid())
                .map(metadata -> json
                        .put("title", metadata.getTitle())
                        .put("description", metadata.getDescription())
                        .put("image", metadata.getThumbnail())
                        .put("official", metadata.isOfficial())
                )
                .orElse(json);
    }

    public CompletableFuture<Void> dump(String outputPath) {
        // Not supported
        return CompletableFuture.completedFuture(null);
    }

}
