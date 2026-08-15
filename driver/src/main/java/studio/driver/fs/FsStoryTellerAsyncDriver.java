/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.apache.commons.codec.binary.Hex;
import org.usb4java.Device;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.driver.DeviceVersion;
import studio.driver.LibUsbDetectionHelper;
import studio.driver.model.fs.FsDeviceInfos;
import studio.driver.model.fs.FsDeviceKeyV3;
import studio.driver.model.fs.FsStoryPackInfos;
import studio.driver.StoryTellerException;
import studio.driver.event.DeviceHotplugEventListener;
import studio.driver.event.TransferProgressListener;
import studio.driver.model.TransferStatus;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class FsStoryTellerAsyncDriver {

    private static final Logger LOGGER = Logger.getLogger(FsStoryTellerAsyncDriver.class.getName());

    private static final String DEVICE_METADATA_FILENAME = ".md";
    private static final String PACK_INDEX_FILENAME = ".pi";
    /** {@code .pi} is a bare concatenation of big-endian UUIDs; a valid one is a multiple of this. */
    private static final int PACK_INDEX_RECORD_SIZE = 16;
    private static final String CONTENT_FOLDER = ".content";
    private static final String NODE_INDEX_FILENAME = "ni";
    private static final String NIGHT_MODE_FILENAME = "nm";

    // Volatile: written by the libusb detection thread (hotplug callback or active polling), read by
    // the Vert.x threads serving HTTP requests.
    private volatile Device device = null;
    private volatile String partitionMountPoint = null;
    /**
     * The device whose partition is currently being waited for. Set before the search starts and
     * cleared on unplug, which is what lets an unplug cancel a search already in flight.
     */
    private volatile Device awaitingPartitionFor = null;
    private List<DeviceHotplugEventListener> listeners = new ArrayList<>();

    private final DevicePartitionLocator partitionLocator;
    private final PackIndexWriter packIndexWriter;


    public FsStoryTellerAsyncDriver() {
        this(DevicePartitionLocator.forDeviceMetadataFile(DEVICE_METADATA_FILENAME));
    }

    FsStoryTellerAsyncDriver(DevicePartitionLocator partitionLocator) {
        this(partitionLocator, new TemporaryFilePackIndexWriter());
    }

    FsStoryTellerAsyncDriver(DevicePartitionLocator partitionLocator, PackIndexWriter packIndexWriter) {
        this.partitionLocator = partitionLocator;
        this.packIndexWriter = packIndexWriter;
        // Initialize libusb, handle and propagate hotplug events
        LOGGER.fine("Registering hotplug listener");
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, new DeviceHotplugEventListener() {
                    @Override
                    public void onDevicePlugged(Device device) {
                        FsStoryTellerAsyncDriver.this.handleDevicePlugged(device);
                    }

                    @Override
                    public void onDeviceUnplugged(Device device) {
                        FsStoryTellerAsyncDriver.this.handleDeviceUnplugged(device);
                    }
                }
        );
    }

    /**
     * Resolves the mounted partition, then publishes the device.
     *
     * <p>Nothing is allowed to escape: this runs inside a {@code CompletableFuture} whose exceptions
     * are only logged, so throwing here used to mean "no device, no message, and no retry until the
     * next plug event" — the device stayed invisible to STUdio even though the OS had mounted it.
     */
    void handleDevicePlugged(Device device) {
        LOGGER.info("Device plugged; waiting for its partition to be mounted");
        this.awaitingPartitionFor = device;
        try {
            Optional<String> mountPoint =
                    partitionLocator.awaitPartition(() -> this.awaitingPartitionFor == device);

            if (this.awaitingPartitionFor != device) {
                // Unplugged while we were waiting. handleDeviceUnplugged already cleared the state.
                LOGGER.info("Device was unplugged before its partition became available");
                return;
            }
            if (!mountPoint.isPresent()) {
                // Already logged by the locator. Deliberately not thrown: the caller only logs.
                this.awaitingPartitionFor = null;
                return;
            }

            this.partitionMountPoint = mountPoint.get();
            this.device = device;
            this.awaitingPartitionFor = null;
            LOGGER.info("Device ready on " + this.partitionMountPoint);
            this.listeners.forEach(listener -> listener.onDevicePlugged(device));
        } catch (Exception e) {
            this.awaitingPartitionFor = null;
            LOGGER.log(Level.SEVERE, "Failed to handle device plug event", e);
        }
    }

    void handleDeviceUnplugged(Device device) {
        LOGGER.info("Device unplugged");
        // Cancels any partition search in flight and prevents a stale mount point from surviving.
        this.awaitingPartitionFor = null;
        this.device = null;
        this.partitionMountPoint = null;
        this.listeners.forEach(listener -> listener.onDeviceUnplugged(device));
    }

    /** Visible for tests: the mount point currently considered valid, if any. */
    String getPartitionMountPoint() {
        return partitionMountPoint;
    }


    public void registerDeviceListener(DeviceHotplugEventListener listener) {
        this.listeners.add(listener);
        if (this.device != null) {
            listener.onDevicePlugged(this.device);
        }
    }


    public CompletableFuture<FsDeviceInfos> getDeviceInfos() {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(new StoryTellerException("No device plugged"));
        }
        FsDeviceInfos infos = new FsDeviceInfos();
        String mdFile = this.partitionMountPoint + File.separator + DEVICE_METADATA_FILENAME;
        LOGGER.finest("Reading device infos from file: " + mdFile);
        // try-with-resources, because the stream must be released on every path out of this method,
        // not just the nominal one. The explicit close() this replaces was skipped by the early
        // return for an unsupported metadata version and by every exception raised while parsing, so
        // a device with an unreadable `.md` leaked one handle per call — and getDeviceInfos runs on
        // every /infos request and at the start of every transfer. On Windows the retained handle
        // keeps `.md` locked. Parsing, version dispatch and error mapping below are unchanged.
        try (FileInputStream deviceMetadataFis = new FileInputStream(mdFile)) {
            // MD file format version
            short mdVersion = readLittleEndianShort(deviceMetadataFis);
            LOGGER.finest("Device metadata format version: " + mdVersion);
            if (mdVersion >= 1 && mdVersion <= 3) {
                this.parseDeviceInfosMeta1to3(infos, deviceMetadataFis);
            } else if (mdVersion >= 6 && mdVersion <= 7) {
                this.parseDeviceInfosMeta6to7(infos, mdVersion, deviceMetadataFis);
            } else {
                return CompletableFuture.failedFuture(new StoryTellerException("Unsupported device metadata format version: " + mdVersion));
            }

            // SD card size and used space
            File mdFd = new File(mdFile);
            long sdCardTotalSpace = mdFd.getTotalSpace();
            long sdCardUsedSpace = mdFd.getTotalSpace() - mdFd.getFreeSpace();
            infos.setSdCardSizeInBytes(sdCardTotalSpace);
            infos.setUsedSpaceInBytes(sdCardUsedSpace);
            LOGGER.fine("SD card size: " + sdCardTotalSpace);
            LOGGER.fine("SD card used space: " + sdCardUsedSpace);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to read device metadata on partition", e));
        }

        return CompletableFuture.completedFuture(infos);
    }

    private void parseDeviceInfosMeta1to3(FsDeviceInfos infos, FileInputStream deviceMetadataFis) throws IOException {
        // Firmware version
        deviceMetadataFis.skip(4);
        short major = readLittleEndianShort(deviceMetadataFis);
        short minor = readLittleEndianShort(deviceMetadataFis);
        infos.setFirmwareMajor(major);
        infos.setFirmwareMinor(minor);
        LOGGER.fine("Firmware version: " + major + "." + minor);

        // Serial number
        String serialNumber = null;
        long sn = readBigEndianLong(deviceMetadataFis);
        if (sn != 0L && sn != -1L && sn != -4294967296L) {
            serialNumber = String.format("%014d", sn);
            LOGGER.fine("Serial Number: " + serialNumber);
        } else {
            LOGGER.warning("No serial number in SPI");
        }
        infos.setSerialNumber(serialNumber);

        // UUID
        deviceMetadataFis.skip(238);
        byte[] uuid = deviceMetadataFis.readNBytes(256);
        infos.setUuid(uuid);
        LOGGER.fine("UUID: " + Hex.encodeHexString(uuid));
    }

    private void parseDeviceInfosMeta6to7(FsDeviceInfos infos, short mdVersion, FileInputStream deviceMetadataFis) throws IOException {
        // Firmware version
        short major = readAsciiToShort(deviceMetadataFis, 1);
        deviceMetadataFis.skip(1);
        short minor = readAsciiToShort(deviceMetadataFis, 1);
        infos.setFirmwareMajor(major);
        infos.setFirmwareMinor(minor);
        LOGGER.fine("Firmware version: " + major + "." + minor);

        // Serial number
        deviceMetadataFis.skip(21);
        byte[] snBytes = deviceMetadataFis.readNBytes(24);
        String serialNumber = new String(snBytes);
        LOGGER.info("Serial Number: " + serialNumber);
        infos.setSerialNumber(serialNumber);

        byte[] aesKey, aesIv, btFile;
        if (mdVersion == 6) {
            // Construct AES key and IV from serial number
            aesKey = new byte[16];
            System.arraycopy(snBytes, 0, aesKey, 0 , 16);
            aesIv = new byte[16];
            System.arraycopy(snBytes, 16, aesIv, 0 , 8);
            System.arraycopy(snBytes, 0, aesIv, 8 , 8);

            // BT file content
            deviceMetadataFis.skip(14);
            btFile = deviceMetadataFis.readNBytes(32);
        } else {    // v7
            // Construct AES key and IV
            deviceMetadataFis.skip(14);
            aesKey = deviceMetadataFis.readNBytes(16);
            aesIv = deviceMetadataFis.readNBytes(16);

            // BT file content from serial number
            btFile = new byte[32];
            System.arraycopy(snBytes, 0, btFile, 0 , 24);
            System.arraycopy(snBytes, 0, btFile, 24 , 8);
        }

        infos.setDeviceKeyV3(new FsDeviceKeyV3(aesKey, aesIv, btFile));

        // Dummy UUID
        byte[] uuid = new byte[64];
        System.arraycopy(aesKey, 0, uuid, 0 , 16);
        System.arraycopy(aesIv, 0, uuid, 16 , 16);
        System.arraycopy(btFile, 0, uuid, 32 , 32);
        infos.setUuid(uuid);
        LOGGER.fine("UUID: " + Hex.encodeHexString(uuid));
    }

    private short readLittleEndianShort(FileInputStream fis) throws IOException {
        byte[] buffer = new byte[2];
        fis.read(buffer);
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        return bb.getShort();
    }

    private long readBigEndianLong(FileInputStream fis) throws IOException {
        byte[] buffer = new byte[8];
        fis.read(buffer);
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.order(ByteOrder.BIG_ENDIAN);
        return bb.getLong();
    }

    private short readAsciiToShort(FileInputStream fis, int numberBytes) throws IOException {
        return Short.parseShort(new String(fis.readNBytes(numberBytes), StandardCharsets.UTF_8));
    }

    private long readAsciiToLong(FileInputStream fis, int numberBytes) throws IOException {
        return Long.parseLong(new String(fis.readNBytes(numberBytes), StandardCharsets.UTF_8));
    }


    public CompletableFuture<List<FsStoryPackInfos>> getPacksList() {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(new StoryTellerException("No device plugged"));
        }

        return readPackIndex()
                .thenApply(packUUIDs -> {
                    try {
                        LOGGER.fine("Number of packs in index: " + packUUIDs.size());
                        List<FsStoryPackInfos> packs = new ArrayList<>();
                        for (UUID packUUID : packUUIDs) {
                            FsStoryPackInfos packInfos = new FsStoryPackInfos();
                            packInfos.setUuid(packUUID);
                            LOGGER.fine("Pack UUID: " + packUUID.toString());

                            // Compute .content folder (last 4 bytes of UUID)
                            String folderName = computePackFolderName(packUUID.toString());
                            String packFolderPath = this.partitionMountPoint + File.separator + CONTENT_FOLDER + File.separator + folderName;
                            packInfos.setFolderName(folderName);

                            // Open 'ni' file
                            File packFolder = new File(packFolderPath);
                            File nodeIndex = new File(packFolder, NODE_INDEX_FILENAME);
                            if (!nodeIndex.exists()) {
                                // An index entry with nothing behind it is a LOCAL inconsistency, not
                                // a corrupt index: the other packs are intact and must stay listable.
                                // Opening the missing file used to throw and fail the whole listing,
                                // which made one stale entry enough to hide every pack on the device.
                                // Nothing is repaired here — the entry stays in .pi, and whatever is
                                // or is not on the card is left exactly as found.
                                LOGGER.warning("Pack " + packUUID + " is listed in the index but has no readable"
                                        + " content on the device; leaving it out of the list");
                                continue;
                            }
                            // try-with-resources: the version read below throws on a truncated 'ni',
                            // and the explicit closes this replaces were skipped when it did.
                            short version;
                            try (FileInputStream niFis = new FileInputStream(nodeIndex);
                                 DataInputStream niDis = new DataInputStream(niFis)) {
                                ByteBuffer bb = ByteBuffer.wrap(niDis.readNBytes(512)).order(ByteOrder.LITTLE_ENDIAN);
                                version = bb.getShort(2);
                            }
                            packInfos.setVersion(version);
                            LOGGER.fine("Pack version: " + version);

                            // Night mode is available if file 'nm' exists
                            packInfos.setNightModeAvailable(new File(packFolder, NIGHT_MODE_FILENAME).exists());

                            // Compute folder size
                            packInfos.setSizeInBytes((int) FileUtils.getFolderSize(packFolderPath));

                            packs.add(packInfos);
                        }
                        return packs;
                    } catch (Exception e) {
                        throw new StoryTellerException("Failed to read pack metadata on device partition", e);
                    }
                });
    }

    private CompletableFuture<List<UUID>> readPackIndex() {
        return CompletableFuture.supplyAsync(() -> {
            String piFile = this.partitionMountPoint + File.separator + PACK_INDEX_FILENAME;
            LOGGER.finest("Reading packs index from file: " + piFile);

            byte[] index;
            try {
                // Read the index in one go. readAllBytes owns and closes its own channel on every
                // path — the previous loop closed its stream only when the read completed normally —
                // and having the whole file in hand is what makes the framing check below exact.
                // The index is 16 bytes per pack, so this stays in the kilobytes.
                index = Files.readAllBytes(Paths.get(piFile));
            } catch (Exception e) {
                throw new StoryTellerException("Failed to read pack index on device partition", e);
            }

            // The index is the only authority on what the device holds, and it cannot be rebuilt
            // from the card. A file that is not a whole number of records is structurally invalid:
            // the previous loop read a partial record into a reused buffer and turned the leftover
            // bytes into a pack UUID that was never written. Report it; do not repair it.
            if (index.length % PACK_INDEX_RECORD_SIZE != 0) {
                throw new StoryTellerException("Invalid pack index on device partition: " + index.length
                        + " bytes is not a whole number of " + PACK_INDEX_RECORD_SIZE + "-byte records");
            }

            List<UUID> packUUIDs = new ArrayList<>();
            ByteBuffer bb = ByteBuffer.wrap(index);
            while (bb.hasRemaining()) {
                long high = bb.getLong();
                long low = bb.getLong();
                packUUIDs.add(new UUID(high, low));
            }
            return packUUIDs;
        });
    }


    public CompletableFuture<Boolean> reorderPacks(List<String> uuids) {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(new StoryTellerException("No device plugged"));
        }

        return readPackIndex()
                .thenCompose(packUUIDs -> {
                    try {
                        boolean allUUIDsAreOnDevice = uuids.stream().allMatch(uuid -> packUUIDs.stream().anyMatch(p -> p.equals(UUID.fromString(uuid))));
                        if (allUUIDsAreOnDevice) {
                            // Reorder list according to uuids list
                            packUUIDs.sort(Comparator.comparingInt(p -> uuids.indexOf(p.toString())));
                            // Write pack index
                            return writePackIndex(packUUIDs);
                        } else {
                            throw new StoryTellerException("Packs on device do not match UUIDs");
                        }
                    } catch (Exception e) {
                        throw new StoryTellerException("Failed to read pack metadata on device partition", e);
                    }
                });
    }

    public CompletableFuture<Boolean> deletePack(String uuid) {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(new StoryTellerException("No device plugged"));
        }

        return readPackIndex()
                .thenCompose(packUUIDs -> {
                    try {
                        // Look for UUID in packs index
                        Optional<UUID> matched = packUUIDs.stream().filter(p -> p.equals(UUID.fromString(uuid))).findFirst();
                        if (matched.isPresent()) {
                            LOGGER.fine("Found pack with uuid: " + uuid);
                            // Remove from index
                            packUUIDs.remove(matched.get());
                            // Write pack index
                            return writePackIndex(packUUIDs)
                                    .thenCompose(ok -> {
                                        // Generate folder name
                                        String folderName = this.partitionMountPoint + File.separator + CONTENT_FOLDER + File.separator + computePackFolderName(uuid);
                                        LOGGER.fine("Removing pack folder: " + folderName);
                                        try {
                                            org.apache.commons.io.FileUtils.deleteDirectory(new File(folderName));
                                            return CompletableFuture.completedFuture(ok);
                                        } catch (IOException e) {
                                            return CompletableFuture.failedFuture(new StoryTellerException("Failed to delete pack folder on device partition", e));
                                        }
                                    });
                        } else {
                            throw new StoryTellerException("Pack not found");
                        }
                    } catch (Exception e) {
                        throw new StoryTellerException("Failed to read pack metadata on device partition", e);
                    }
                });
    }

    /**
     * Hands the index off to the {@link PackIndexWriter}, and keeps the async and error contract the
     * callers already rely on: a completed future carrying {@code true}, or a failed one carrying a
     * {@link StoryTellerException} wrapping the original cause. How the file is actually replaced is
     * no longer this class's business.
     */
    private CompletableFuture<Boolean> writePackIndex(List<UUID> packUUIDs) {
        try {
            packIndexWriter.write(this.partitionMountPoint + File.separator + PACK_INDEX_FILENAME, packUUIDs);
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to write pack index on device partition", e));
        }
    }


    public CompletableFuture<TransferStatus> downloadPack(String uuid, String outputPath, TransferProgressListener listener) {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(new StoryTellerException("No device plugged"));
        }

        return readPackIndex()
                .thenCompose(packUUIDs -> getDeviceInfos()
                        .thenCompose(deviceInfos -> CompletableFuture.supplyAsync(() -> {
                    // Look for UUID in packs index
                    Optional<UUID> matched = packUUIDs.stream().filter(p -> p.equals(UUID.fromString(uuid))).findFirst();
                    if (matched.isPresent()) {
                        LOGGER.fine("Found pack with uuid: " + uuid);

                        // Generate folder name
                        String sourceFolder = this.partitionMountPoint + File.separator + CONTENT_FOLDER + File.separator + computePackFolderName(uuid);
                        LOGGER.finest("Downloading pack folder: " + sourceFolder);

                        if (Files.exists(Paths.get(sourceFolder))) {
                            try {
                                // Create destination folder
                                File destFolder = new File(outputPath + File.separator + uuid);
                                destFolder.mkdirs();
                                // Copy folder with progress tracking
                                return copyPackFolder(sourceFolder, destFolder, deviceInfos, false, listener);
                            } catch (IOException e) {
                                throw new StoryTellerException("Failed to copy pack from device", e);
                            }
                        } else {
                            throw new StoryTellerException("Pack folder not found");
                        }
                    } else {
                        throw new StoryTellerException("Pack not found");
                    }
                })));
    }

    public CompletableFuture<TransferStatus> uploadPack(String uuid, String inputPath, TransferProgressListener listener) {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(new StoryTellerException("No device plugged"));
        }

        try {
            // Check free space. The estimate is in bytes the transfer is known to add: the source
            // sizes it used to sum are neither what lands on the device nor all of it.
            long currentIndexBytes = new File(this.partitionMountPoint + File.separator + PACK_INDEX_FILENAME).length();
            long requiredBytes;
            try {
                requiredBytes = PackTransferSizeEstimator.additionalBytesForUpload(Paths.get(inputPath), currentIndexBytes);
            } catch (ArithmeticException overflow) {
                // An estimate too large to represent is refused rather than wrapped into a small
                // number that would read as "plenty of room".
                throw new StoryTellerException("Not enough free space on the device", overflow);
            }
            LOGGER.finest("Pack transfer needs at least " + requiredBytes + " additional bytes");
            String mdFile = this.partitionMountPoint + File.separator + DEVICE_METADATA_FILENAME;
            File mdFd = new File(mdFile);
            if (!hasEnoughFreeSpace(mdFd.getFreeSpace(), requiredBytes)) {
                throw new StoryTellerException("Not enough free space on the device");
            }

            // Generate folder name
            String folderName = this.partitionMountPoint + File.separator + CONTENT_FOLDER + File.separator + computePackFolderName(uuid);
            LOGGER.fine("Uploading pack to folder: " + folderName);

            // Create destination folder
            File destFolder = new File(folderName);
            destFolder.mkdirs();
            // Copy folder with progress tracking
            return getDeviceInfos().thenCompose(deviceInfos ->
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return copyPackFolder(inputPath, destFolder, deviceInfos, true, new TransferProgressListener() {
                                @Override
                                public void onProgress(TransferStatus status) {
                                    if (listener != null) {
                                        listener.onProgress(status);
                                    }
                                }

                                @Override
                                public void onComplete(TransferStatus status) {
                                    // Not calling listener because the pack must be added to the index
                                }
                            });
                        } catch (IOException e) {
                            throw new StoryTellerException("Failed to copy pack from device", e);
                        }
                    })).thenCompose(status -> {
                        // Finally, add pack UUID to index
                        return readPackIndex()
                                .thenCompose(packUUIDs -> {
                                    try {
                                        // Add UUID in packs index
                                        packUUIDs.add(UUID.fromString(uuid));
                                        // Write pack index
                                        return writePackIndex(packUUIDs)
                                                .thenApply(ok -> {
                                                    if (listener != null) {
                                                        listener.onComplete(status);
                                                    }
                                                    return status;
                                                });
                                    } catch (Exception e) {
                                        throw new StoryTellerException("Failed to write pack metadata on device partition", e);
                                    }
                                });
                    });
        } catch (IOException e) {
            throw new StoryTellerException("Failed to copy pack to device", e);
        }
    }

    private TransferStatus copyPackFolder(String sourceFolder, File destFolder, FsDeviceInfos deviceInfos, boolean isUpload, TransferProgressListener listener) throws IOException {
        // Keep track of transferred bytes and elapsed time
        final long startTime = System.currentTimeMillis();
        AtomicInteger transferred = new AtomicInteger(0);
        int folderSize = (int) FileUtils.getFolderSize(sourceFolder);
        LOGGER.finest("Pack folder size: " + folderSize);

        // Fail for unsupported firmware versions
        if (deviceInfos.getFirmwareMajor() != 2 && deviceInfos.getFirmwareMajor() != 3) {
            throw new StoryTellerException("Failed to copy pack folder: unsupported firmware version " + deviceInfos.getFirmwareMajor());
        }

        // Assets are cleartext if file '.cleartext' exists
        boolean isCleartext = (new FsStoryPackReader()).isCleartext(Paths.get(sourceFolder), isUpload);
        
        // Always add .cleartext file when downloading
        if (!isUpload) {
            // Indicate that files are cleartext
            new File(destFolder, FsStoryPackReader.CLEARTEXT_FILENAME).createNewFile();
        }

        // Copy folders and files
        // The stream owns an open directory handle per level; when the lambda below throws, the
        // traversal is abandoned mid-way and only try-with-resources still closes them. Leaving them
        // open makes the source pack undeletable on Windows, and on FAT32 its directory entry
        // survives in the parent even after a successful delete.
        try (Stream<Path> sourcePaths = Files.walk(Paths.get(sourceFolder))) {
            sourcePaths.forEach(s -> {
                try {
                    Path d = destFolder.toPath().resolve(Paths.get(sourceFolder).relativize(s));
                    if (Files.isDirectory(s)) {
                        if (!Files.exists(d)) {
                            LOGGER.finer("Creating directory " + d.toString());
                            Files.createDirectory(d);
                        }
                    } else {
                        // DO NOT COPY .cleartext file
                        if (!CipherUtils.shouldBeCopied(s)) {
                            LOGGER.finer("NOT copying file " + s.toString());
                            return;
                        }

                        int fileSize = (int) FileUtils.getFileSize(s.toAbsolutePath().toString());
                        LOGGER.finer("Copying file " + s.toString() + " to " + d.toString() + " (" + fileSize + " bytes)");

                        if (CipherUtils.shouldBeCiphered(s)) {
                            if (deviceInfos.getFirmwareMajor() == 2) {
                                if (isUpload) {
                                    if (isCleartext) {
                                        byte[] ciphered = CipherUtils.cipherFirstBlockCommonKey(Files.readAllBytes(s));
                                        Files.write(d, ciphered);
                                    } else {
                                        Files.copy(s, d);
                                    }
                                } else {    // Download
                                    byte[] deciphered = CipherUtils.decipherFirstBlockCommonKey(Files.readAllBytes(s));
                                    Files.write(d, deciphered);
                                }
                            } else {    // V3
                                if (isUpload) {
                                    byte[] data = Files.readAllBytes(s);
                                    if (!isCleartext) {
                                        data = CipherUtils.decipherFirstBlockCommonKey(data);
                                    }
                                    byte[] ciphered = CipherUtils.cipherFirstBlockSpecificKeyV3(data, deviceInfos.getDeviceKeyV3());
                                    Files.write(d, ciphered);
                                } else {    // Download
                                    byte[] deciphered = CipherUtils.decipherFirstBlockSpecificKeyV3(Files.readAllBytes(s), deviceInfos.getDeviceKeyV3());
                                    Files.write(d, deciphered);
                                }
                            }
                        } else {
                            Files.copy(s, d);
                        }

                        // Compute progress and speed
                        int xferred = transferred.addAndGet(fileSize);
                        long elapsed = System.currentTimeMillis() - startTime;
                        double speed = ((double) xferred) / ((double) elapsed / 1000.0);
                        LOGGER.finer("Transferred " + xferred + " bytes in " + elapsed + " ms");
                        LOGGER.finer("Average speed = " + speed + " bytes/sec");
                        TransferStatus status = new TransferStatus(xferred == folderSize, xferred, folderSize, speed);

                        // Call (optional) listener with transfer status
                        if (listener != null) {
                            CompletableFuture.runAsync(() -> listener.onProgress(status));
                            if (status.isDone()) {
                                CompletableFuture.runAsync(() -> listener.onComplete(status));
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new StoryTellerException("Failed to copy pack folder", e);
                }
            });
        }
        // When transfer is complete, generate device-specific boot file
        LOGGER.fine("Generating device-specific boot file");
        try {
            if (deviceInfos.getFirmwareMajor() == 2) {
                CipherUtils.addBootFileV2(destFolder.toPath(), deviceInfos.getUuid());
            } else {
                CipherUtils.addBootFileV3(destFolder.toPath(), deviceInfos.getDeviceKeyV3());
            }
        } catch (IOException e) {
            throw new StoryTellerException("Failed to generate device-specific boot file", e);
        }
        return new TransferStatus(transferred.get() == folderSize, transferred.get(), folderSize, 0.0);
    }

    /**
     * The preflight decision, kept apart from the filesystem so it can be exercised directly.
     *
     * <p>Both operands are {@code long} and are compared rather than subtracted: a difference beyond
     * the int range — or beyond the long range — is exactly the case the old {@code int} estimate got
     * wrong, by wrapping negative and passing the check unconditionally.
     */
    static boolean hasEnoughFreeSpace(long freeSpace, long requiredBytes) {
        return freeSpace >= requiredBytes;
    }

    public String computePackFolderName(String uuid) {
        String uuidStr = uuid.replaceAll("-", "");
        return uuidStr.substring(uuidStr.length() - 8).toUpperCase();
    }
}
