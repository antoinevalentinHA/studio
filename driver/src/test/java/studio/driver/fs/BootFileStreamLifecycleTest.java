/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.reader.fs.FsStoryPackReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@code addBootFileV2} produces, now that its two streams are closed by try-with-resources.
 *
 * <p>Before this change the {@code ri} input stream and the {@code bt} output stream were closed by
 * hand after the read and the write. Any exception raised in between — an I/O error while reading
 * {@code ri}, a disk-full error while writing {@code bt} — skipped the {@code close()} and leaked the
 * handle. On Windows a leaked handle keeps the file locked, so the very pack folder the transfer was
 * building could then neither be finished nor removed. Same family of defect as the directory
 * handles fixed in {@link FilesWalkResourceLeakTest}, on a {@code FileInputStream} and a
 * {@code FileOutputStream}.
 *
 * <h2>Why the failure path is not exercised here</h2>
 *
 * <p>There is no input that makes the method fail <em>between</em> opening a stream and closing it.
 * A missing or unreadable {@code ri} fails in the {@code FileInputStream} constructor, before any
 * handle exists; a short or empty {@code ri} is read and ciphered without error (the cipher trims to
 * whatever is there); and the {@code bt} write of at most 64 bytes has no reachable failure short of
 * a full disk. The leak was therefore only reachable through genuine I/O errors, which cannot be
 * injected from a unit test without a seam the production code does not need. The closing is a
 * correctness fix by inspection, like the {@code getFolderSize} closing pinned by
 * {@link FilesWalkResourceLeakTest#measuringFolderSizeReleasesTheTree()}.
 *
 * <p>What this class does pin is the nominal contract, which the rewrite must not have altered:
 * the shape of {@code bt}, its dependence on the device UUID, and the fact that both files are
 * released — and thus deletable — once the method has returned.
 */
class BootFileStreamLifecycleTest {

    private static final int BOOT_BLOCK = 64;

    /**
     * A device UUID as the driver reads it from the {@code .md} file: 16 ciphered bytes, from which
     * the device-specific boot key is derived. The values carry no meaning beyond being distinct.
     */
    private static byte[] deviceUuid(int seed) {
        byte[] uuid = new byte[16];
        for (int i = 0; i < uuid.length; i++) {
            uuid[i] = (byte) (seed + i * 7);
        }
        return uuid;
    }

    private static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    private static Path packWithImageIndex(Path folder, byte[] ri) throws IOException {
        Files.write(folder.resolve(FsStoryPackReader.IMAGE_INDEX_FILENAME), ri);
        return folder;
    }

    @Test
    @DisplayName("writes a 64-byte bt derived from the first block of ri and the device UUID")
    void writesBootFileFromImageIndexPrefix(@TempDir Path folder) throws IOException {
        byte[] ri = pattern(200);
        packWithImageIndex(folder, ri);

        CipherUtils.addBootFileV2(folder, deviceUuid(0x10));

        byte[] bt = Files.readAllBytes(folder.resolve("bt"));
        assertEquals(BOOT_BLOCK, bt.length, "bt is exactly the first ciphered block of ri");
        assertFalse(Arrays.equals(Arrays.copyOf(ri, BOOT_BLOCK), bt),
                "bt is ciphered with the device-specific key, not a plain copy of ri");
        assertArrayEquals(ri, Files.readAllBytes(folder.resolve(FsStoryPackReader.IMAGE_INDEX_FILENAME)),
                "ri is read, never rewritten");
    }

    @Test
    @DisplayName("bt depends on the device UUID, so a pack is bound to one device")
    void bootFileDependsOnDeviceUuid(@TempDir Path first, @TempDir Path second) throws IOException {
        byte[] ri = pattern(200);
        packWithImageIndex(first, ri);
        packWithImageIndex(second, ri);

        CipherUtils.addBootFileV2(first, deviceUuid(0x10));
        CipherUtils.addBootFileV2(second, deviceUuid(0x20));

        assertFalse(Arrays.equals(Files.readAllBytes(first.resolve("bt")), Files.readAllBytes(second.resolve("bt"))),
                "two devices must not get the same bt for the same ri");
    }

    @Test
    @DisplayName("is deterministic and overwrites a previous bt rather than appending to it")
    void isDeterministicAndOverwrites(@TempDir Path folder) throws IOException {
        packWithImageIndex(folder, pattern(200));

        CipherUtils.addBootFileV2(folder, deviceUuid(0x10));
        byte[] once = Files.readAllBytes(folder.resolve("bt"));
        CipherUtils.addBootFileV2(folder, deviceUuid(0x10));
        byte[] twice = Files.readAllBytes(folder.resolve("bt"));

        assertArrayEquals(once, twice);
        assertEquals(BOOT_BLOCK, twice.length, "a second run replaces bt, it does not grow it");
    }

    @Test
    @DisplayName("a short ri is ciphered as-is, which is the only degenerate input the method accepts")
    void shortImageIndexIsHandledWithoutError(@TempDir Path folder) throws IOException {
        // Documents the reason the failure path cannot be reached from outside: a truncated ri does
        // not make the read throw, it just yields a shorter bt.
        packWithImageIndex(folder, pattern(10));

        CipherUtils.addBootFileV2(folder, deviceUuid(0x10));

        assertEquals(10, Files.readAllBytes(folder.resolve("bt")).length);
    }

    @Test
    @DisplayName("both files are released once the method has returned")
    void releasesBothFilesOnReturn(@TempDir Path folder) throws IOException {
        // On Windows this is the observable consequence of a closed handle: the file can be deleted.
        // On other platforms the delete succeeds regardless, so this is the nominal control case that
        // the FAT32 classes rely on, kept here so the rewrite cannot regress it on the platform where
        // it matters.
        packWithImageIndex(folder, pattern(200));

        CipherUtils.addBootFileV2(folder, deviceUuid(0x10));

        assertTrue(Files.deleteIfExists(folder.resolve("bt")), "bt must be deletable");
        assertTrue(Files.deleteIfExists(folder.resolve(FsStoryPackReader.IMAGE_INDEX_FILENAME)), "ri must be deletable");
    }
}
