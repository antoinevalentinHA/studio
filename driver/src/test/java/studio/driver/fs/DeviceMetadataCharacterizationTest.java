/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import studio.driver.StoryTellerException;
import studio.driver.model.fs.FsDeviceInfos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterises the parsing of the device metadata file ({@code .md}) — the single input that
 * decides whether a plugged device is treated as V2 or V3, and which key material is used to cipher
 * everything written to it.
 *
 * <p>Fixtures are synthesised here rather than captured from a real device: no device data is needed
 * to exercise the parser, and none is committed to the repository.
 */
class DeviceMetadataCharacterizationTest {

    /**
     * Managed by hand rather than with {@code @TempDir}: the code under test leaks the {@code .md}
     * file handle on its failure paths (see {@link #leaksTheMetadataHandleOnFailurePaths()}), and on
     * Windows an open handle makes the file undeletable. {@code @TempDir} turns that into a spurious
     * test error at cleanup time; best-effort cleanup keeps the leak visible where it belongs — in a
     * test that asserts it — instead of as noise on unrelated cases.
     */
    private Path partition;

    @BeforeEach
    void createPartitionDirectory() throws IOException {
        partition = Files.createTempDirectory("studio-md-");
    }

    @AfterEach
    void removePartitionDirectory() {
        try (Stream<Path> paths = Files.walk(partition)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Expected on Windows whenever the test exercised a failure path: the driver
                    // still holds the handle. Leaving the file behind is preferable to failing.
                }
            });
        } catch (IOException ignored) {
            // Nothing useful to do; the OS temp directory is disposable.
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** Layout read by parseDeviceInfosMeta1to3: version, 4 skipped, major, minor, serial, 238 skipped, 256-byte uuid. */
    private byte[] metadataV1to3(int version, int major, int minor, long serial) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndianShort(out, version);
        out.write(new byte[4], 0, 4);
        writeLittleEndianShort(out, major);
        writeLittleEndianShort(out, minor);
        for (int i = 7; i >= 0; i--) {
            out.write((int) (serial >> (8 * i)) & 0xFF);
        }
        out.write(new byte[238], 0, 238);
        byte[] uuid = new byte[256];
        java.util.Arrays.fill(uuid, (byte) 0x5A);
        out.write(uuid, 0, uuid.length);
        return out.toByteArray();
    }

    /**
     * Layout read by parseDeviceInfosMeta6to7: version, 1 ASCII major digit, 1 skipped, 1 ASCII minor
     * digit, 21 skipped, 24-byte serial, 14 skipped, then 32 bytes of `bt` (v6) or key+iv (v7).
     */
    private byte[] metadataV6to7(int version, char major, char minor, String serial24) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLittleEndianShort(out, version);
        out.write(major);
        out.write('.');
        out.write(minor);
        out.write(new byte[21], 0, 21);
        byte[] serial = new byte[24];
        byte[] raw = serial24.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, serial, 0, Math.min(raw.length, 24));
        out.write(serial, 0, 24);
        out.write(new byte[14], 0, 14);
        byte[] keyMaterial = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyMaterial[i] = (byte) (0xC0 + i);
        }
        out.write(keyMaterial, 0, 32);
        return out.toByteArray();
    }

    private void writeLittleEndianShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private FsStoryTellerAsyncDriver driverWith(byte[] metadata) throws IOException {
        Files.write(partition.resolve(".md"), metadata);
        return DriverTestSupport.pluggedDriverMountedOn(partition);
    }

    private FsDeviceInfos readInfos(byte[] metadata) throws Exception {
        return driverWith(metadata).getDeviceInfos().get();
    }

    // ---------------------------------------------------------------- accepted versions

    @Test
    @DisplayName("versions 1 to 3 are read as a V2 device with a binary firmware version")
    void readsLegacyMetadata() throws Exception {
        for (int version : new int[]{1, 2, 3}) {
            FsDeviceInfos infos = readInfos(metadataV1to3(version, 2, 22, 12345678901234L));

            assertEquals(2, infos.getFirmwareMajor(), "version " + version);
            assertEquals(22, infos.getFirmwareMinor(), "version " + version);
            assertEquals("12345678901234", infos.getSerialNumber());
            assertEquals(256, infos.getUuid().length);
            assertNull(infos.getDeviceKeyV3(), "no V3 key material for a legacy device");
        }
    }

    @Test
    @DisplayName("versions 6 and 7 are read as a V3 device carrying AES key material")
    void readsV3Metadata() throws Exception {
        for (int version : new int[]{6, 7}) {
            FsDeviceInfos infos = readInfos(metadataV6to7(version, '3', '3', "SN0123456789ABCDEF012345"));

            assertEquals(3, infos.getFirmwareMajor(), "version " + version);
            assertEquals(3, infos.getFirmwareMinor(), "version " + version);
            assertEquals("SN0123456789ABCDEF012345", infos.getSerialNumber());
            assertEquals(16, infos.getDeviceKeyV3().getAesKey().length);
            assertEquals(16, infos.getDeviceKeyV3().getAesIv().length);
            assertEquals(32, infos.getDeviceKeyV3().getBt().length);
            // The V3 "uuid" is synthetic: key + iv + bt concatenated, not a device identifier.
            assertEquals(64, infos.getUuid().length);
        }
    }

    @Test
    @DisplayName("v6 and v7 derive key material differently from the same bytes")
    void v6AndV7DeriveDifferentKeys() throws Exception {
        String serial = "SN0123456789ABCDEF012345";

        FsDeviceInfos v6 = readInfos(metadataV6to7(6, '3', '2', serial));
        FsDeviceInfos v7 = readInfos(metadataV6to7(7, '3', '2', serial));

        // v6 builds the key from the serial number; v7 reads it from the file. Confusing the two
        // would silently produce a pack the device cannot decrypt.
        assertEquals("SN0123456789ABCD", new String(v6.getDeviceKeyV3().getAesKey(), StandardCharsets.US_ASCII));
        assertEquals((byte) 0xC0, v7.getDeviceKeyV3().getAesKey()[0]);
    }

    // ---------------------------------------------------------------- rejected versions

    @Test
    @DisplayName("versions 4 and 5 fall in the gap between the two parsers and are rejected")
    void rejectsUnknownVersions() throws IOException {
        for (int version : new int[]{0, 4, 5, 8, 99}) {
            CompletableFuture<FsDeviceInfos> future = driverWith(metadataV1to3(version, 2, 22, 1L)).getDeviceInfos();

            assertTrue(future.isCompletedExceptionally(), "version " + version + " should be rejected");
            Throwable cause = assertThrows(ExecutionException.class, future::get).getCause();
            assertInstanceOf(StoryTellerException.class, cause);
            assertTrue(cause.getMessage().contains("Unsupported device metadata format version"),
                    "unexpected message: " + cause.getMessage());
        }
    }

    @Test
    @DisplayName("a missing .md fails, which is how an unmounted partition surfaces")
    void failsWhenMetadataFileIsAbsent() {
        CompletableFuture<FsDeviceInfos> future =
                DriverTestSupport.pluggedDriverMountedOn(partition).getDeviceInfos();

        assertTrue(future.isCompletedExceptionally());
        assertInstanceOf(StoryTellerException.class,
                assertThrows(ExecutionException.class, future::get).getCause());
    }

    // ---------------------------------------------------------------- truncation

    @Test
    @DisplayName("KNOWN GAP: a truncated legacy .md is accepted and reports firmware 0.0")
    void truncatedLegacyMetadataIsSilentlyAccepted() throws Exception {
        // readLittleEndianShort / readBigEndianLong call FileInputStream.read(byte[]) and ignore the
        // returned count. At EOF the buffer keeps its zeros, so parsing "succeeds" on garbage:
        // a .md holding nothing but its version yields firmware 0.0, no serial and an empty UUID —
        // and the device is then treated as plugged and usable.
        byte[] versionOnly = new byte[]{0x01, 0x00};

        FsDeviceInfos infos = readInfos(versionOnly);

        assertEquals(0, infos.getFirmwareMajor());
        assertEquals(0, infos.getFirmwareMinor());
        assertNull(infos.getSerialNumber());
        assertEquals(0, infos.getUuid().length);
    }

    @Test
    @DisplayName("a truncated V3 .md does fail — the two parsers behave inconsistently")
    void truncatedV3MetadataFails() throws IOException {
        // Contrast with the legacy path above. Here the ASCII firmware digits are parsed with
        // Short.parseShort, which throws on an empty string, so truncation is caught by accident
        // rather than by design. The inconsistency is the point of this test.
        byte[] versionOnly = new byte[]{0x07, 0x00};

        CompletableFuture<FsDeviceInfos> future = driverWith(versionOnly).getDeviceInfos();

        assertTrue(future.isCompletedExceptionally());
        assertInstanceOf(StoryTellerException.class,
                assertThrows(ExecutionException.class, future::get).getCause());
    }

    // ---------------------------------------------------------------- resource handling

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("KNOWN GAP: the .md handle is leaked whenever parsing fails")
    void leaksTheMetadataHandleOnFailurePaths() throws IOException {
        // getDeviceInfos opens a FileInputStream and closes it only on the nominal path: the
        // `return failedFuture(...)` for an unsupported version, and every exception caught by the
        // surrounding try/catch, both skip the close(). The stream is only released whenever the GC
        // gets around to it.
        //
        // Windows makes the leak observable: an open handle blocks deletion. Linux would not
        // complain, which is precisely why this is pinned on Windows.
        //
        // getDeviceInfos runs on every /infos request and at the start of every upload and download,
        // so a device stuck on an unreadable .md leaks one handle per attempt.
        Path metadata = partition.resolve(".md");
        Files.write(metadata, new byte[]{0x63, 0x00});   // version 99: unsupported

        CompletableFuture<FsDeviceInfos> future =
                DriverTestSupport.pluggedDriverMountedOn(partition).getDeviceInfos();
        assertTrue(future.isCompletedExceptionally(), "precondition: parsing must have failed");

        assertThrows(IOException.class, () -> Files.delete(metadata),
                "the file should still be locked by the leaked stream");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("the handle IS released on the nominal path, confirming the leak is failure-specific")
    void releasesTheMetadataHandleOnTheNominalPath() throws Exception {
        // Control case for the test above: on success, close() is reached and the file is deletable.
        Path metadata = partition.resolve(".md");
        Files.write(metadata, metadataV6to7(7, '3', '3', "SN0123456789ABCDEF012345"));

        DriverTestSupport.pluggedDriverMountedOn(partition).getDeviceInfos().get();

        Files.delete(metadata);
        assertTrue(Files.notExists(metadata));
    }

    @Test
    @DisplayName("KNOWN GAP: firmware minor is a single ASCII digit, so 3.10 is unrepresentable")
    void firmwareMinorIsASingleDigit() throws Exception {
        // Only one byte is read for the major and one for the minor. A future firmware numbered
        // x.10 or above cannot be parsed correctly, and there is no allow-list of tested firmwares
        // anywhere in the transfer path.
        FsDeviceInfos infos = readInfos(metadataV6to7(7, '3', '9', "SN0123456789ABCDEF012345"));

        assertEquals(3, infos.getFirmwareMajor());
        assertEquals(9, infos.getFirmwareMinor());
    }
}
