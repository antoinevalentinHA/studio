/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.core.v1.writer.fs.FsStoryPackWriter;

import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Characterises how a pack UUID is mapped to its {@code .content/<NAME>} folder on the device.
 *
 * <p>This mapping is the sole link between the pack index ({@code .pi}) and the pack payload. Any
 * change to it silently orphans every pack already on a device, so the current behaviour is pinned
 * here before any integrity work begins.
 */
class PackFolderNamingCharacterizationTest {

    private final FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(Paths.get("."));

    @Test
    @DisplayName("keeps the last 8 hex characters of the dash-stripped UUID, upper-cased")
    void keepsLastEightCharactersUpperCased() {
        assertEquals("56789ABC", driver.computePackFolderName("12345678-1234-1234-1234-123456789abc"));
        assertEquals("0000000F", driver.computePackFolderName("00000000-0000-0000-0000-00000000000f"));
    }

    @Test
    @DisplayName("is case-insensitive on input and always upper-cases its output")
    void normalisesCase() {
        UUID uuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeffff1234");
        String fromLower = driver.computePackFolderName(uuid.toString().toLowerCase());
        String fromUpper = driver.computePackFolderName(uuid.toString().toUpperCase());

        assertEquals("FFFF1234", fromLower);
        assertEquals(fromLower, fromUpper);
    }

    @Test
    @DisplayName("KNOWN GAP: the mapping is not injective — distinct UUIDs can share one folder")
    void isNotInjective() {
        // Only the last 8 hex characters survive, so 24 of the 32 hex characters are discarded.
        String first = "11111111-1111-1111-1111-1111cafebabe";
        String second = "22222222-2222-2222-2222-2222cafebabe";

        assertEquals(driver.computePackFolderName(first), driver.computePackFolderName(second));

        // Consequence to keep in mind for any future integrity work: uploading `second` onto a
        // device already holding `first` targets the very same directory. The "pack already on the
        // device" guard in StoryTellerService only consults the index, so it would not catch this.
        // Nothing here asserts that the collision is handled — it is not; this test documents it.
    }

    @Test
    @DisplayName("KNOWN GAP: no validation — a too-short identifier throws a raw index exception")
    void rejectsShortInputWithUnhelpfulException() {
        // Neither UUID shape nor length is validated, so the failure surfaces as a low-level
        // StringIndexOutOfBoundsException rather than a domain error.
        assertThrows(StringIndexOutOfBoundsException.class, () -> driver.computePackFolderName("abc"));
    }

    @Test
    @DisplayName("driver and core writer implement the same mapping twice, and must agree")
    void driverAgreesWithCoreWriter() throws Exception {
        // FsStoryPackWriter.transformUuid duplicates FsStoryTellerAsyncDriver.computePackFolderName.
        // The device path uses the former to name the folder it writes, the latter to find it again.
        // They are not shared code, so this test is the only thing tying them together.
        Method transformUuid = FsStoryPackWriter.class.getDeclaredMethod("transformUuid", UUID.class);
        transformUuid.setAccessible(true);

        for (String raw : new String[]{
                "01234567-89ab-cdef-0123-456789abcdef",
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
                "00000000-0000-0000-0000-000000000000"}) {
            UUID uuid = UUID.fromString(raw);
            assertEquals(
                    transformUuid.invoke(null, uuid),
                    driver.computePackFolderName(uuid.toString()),
                    "core writer and device driver disagree for " + raw);
        }
    }
}
