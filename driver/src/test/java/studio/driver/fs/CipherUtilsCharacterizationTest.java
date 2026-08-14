/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.driver.model.fs.FsDeviceKeyV3;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterises the on-transfer cipher layer, which is the difference between a V2 and a V3 device.
 *
 * <p>Only the first 512 bytes of a file are ciphered; V2 uses XXTEA with a common key, V3 uses
 * AES/CBC with a device-specific key. These tests pin the observable behaviour — in particular the
 * fact that V3 ciphering is <em>not</em> length-preserving, which invalidates any size-based
 * verification or progress calculation.
 */
class CipherUtilsCharacterizationTest {

    private static final int CIPHERED_PREFIX = 512;

    /** Arbitrary but fixed key material; the values carry no meaning beyond being 16 bytes each. */
    private static FsDeviceKeyV3 deviceKey() {
        byte[] aesKey = new byte[16];
        byte[] aesIv = new byte[16];
        byte[] bt = new byte[32];
        for (int i = 0; i < 16; i++) {
            aesKey[i] = (byte) (0x10 + i);
            aesIv[i] = (byte) (0xA0 + i);
        }
        return new FsDeviceKeyV3(aesKey, aesIv, bt);
    }

    private static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    @Nested
    @DisplayName("V3 (AES/CBC, device-specific key)")
    class V3 {

        @Test
        @DisplayName("preserves length when the ciphered prefix is a multiple of the AES block size")
        void preservesLengthWhenBlockAligned() {
            for (int length : new int[]{16, 256, CIPHERED_PREFIX}) {
                byte[] ciphered = CipherUtils.cipherFirstBlockSpecificKeyV3(pattern(length), deviceKey());
                assertEquals(length, ciphered.length, "unexpected growth for a " + length + "-byte input");
            }
        }

        @Test
        @DisplayName("KNOWN GAP: grows a non block-aligned file by the zero-padding it adds")
        void growsUnalignedInput() {
            // AESCBCCipher zero-pads up to the next 16-byte boundary and keeps the padding in the
            // output. Consequence: the file written to the device is LARGER than the library file.
            // Anything comparing source and destination sizes to decide "transfer complete" is wrong,
            // and the free-space pre-check under-estimates what the pack will actually occupy.
            byte[] source = pattern(100);

            byte[] ciphered = CipherUtils.cipherFirstBlockSpecificKeyV3(source, deviceKey());

            assertEquals(112, ciphered.length, "100 bytes should be padded up to 112");
        }

        @Test
        @DisplayName("leaves everything past the first 512 bytes untouched")
        void leavesTailUntouched() {
            byte[] source = pattern(700);

            byte[] ciphered = CipherUtils.cipherFirstBlockSpecificKeyV3(source, deviceKey());

            assertEquals(700, ciphered.length);
            assertArrayEquals(
                    Arrays.copyOfRange(source, CIPHERED_PREFIX, source.length),
                    Arrays.copyOfRange(ciphered, CIPHERED_PREFIX, ciphered.length),
                    "bytes beyond the ciphered prefix must be copied verbatim");
            assertFalse(
                    Arrays.equals(
                            Arrays.copyOfRange(source, 0, CIPHERED_PREFIX),
                            Arrays.copyOfRange(ciphered, 0, CIPHERED_PREFIX)),
                    "the first 512 bytes must actually be ciphered");
        }

        @Test
        @DisplayName("round-trips exactly when the input is block-aligned")
        void roundTripsAlignedInput() {
            byte[] source = pattern(600);

            byte[] restored = CipherUtils.decipherFirstBlockSpecificKeyV3(
                    CipherUtils.cipherFirstBlockSpecificKeyV3(source, deviceKey()), deviceKey());

            assertArrayEquals(source, restored);
        }

        @Test
        @DisplayName("KNOWN GAP: an unaligned file does not round-trip — it comes back padded")
        void doesNotRoundTripUnalignedInput() {
            // Upload then download of the same small file is not the identity: the extra padding
            // bytes introduced on the way up survive on the way down. A second cycle is stable,
            // because the file is block-aligned by then. Any future "verify by re-reading and
            // comparing to the library copy" check has to account for this.
            byte[] source = pattern(100);

            byte[] restored = CipherUtils.decipherFirstBlockSpecificKeyV3(
                    CipherUtils.cipherFirstBlockSpecificKeyV3(source, deviceKey()), deviceKey());

            assertEquals(112, restored.length);
            assertArrayEquals(source, Arrays.copyOfRange(restored, 0, 100), "original bytes survive");
            assertArrayEquals(new byte[12], Arrays.copyOfRange(restored, 100, 112), "padding is zero bytes");

            byte[] secondCycle = CipherUtils.decipherFirstBlockSpecificKeyV3(
                    CipherUtils.cipherFirstBlockSpecificKeyV3(restored, deviceKey()), deviceKey());
            assertArrayEquals(restored, secondCycle, "a second cycle is stable");
        }

        @Test
        @DisplayName("output depends on the device key — packs are not portable between devices")
        void isDeviceSpecific() {
            byte[] source = pattern(64);
            FsDeviceKeyV3 otherKey = deviceKey();
            otherKey.getAesKey()[0] = (byte) 0xFF;

            assertNotEquals(
                    Arrays.toString(CipherUtils.cipherFirstBlockSpecificKeyV3(source, deviceKey())),
                    Arrays.toString(CipherUtils.cipherFirstBlockSpecificKeyV3(source, otherKey)));
        }
    }

    @Nested
    @DisplayName("V2 (XXTEA, common key)")
    class V2 {

        @Test
        @DisplayName("round-trips and preserves length, including for unaligned input")
        void roundTripsAndPreservesLength() {
            // Contrast with V3: the XXTEA path never changes the file size.
            for (int length : new int[]{100, CIPHERED_PREFIX, 700}) {
                byte[] source = pattern(length);
                byte[] ciphered = CipherUtils.cipherFirstBlockCommonKey(source);

                assertEquals(length, ciphered.length, "V2 must preserve length for " + length + " bytes");
                assertArrayEquals(source, CipherUtils.decipherFirstBlockCommonKey(ciphered));
            }
        }
    }

    @Nested
    @DisplayName("file selection during a transfer")
    class FileSelection {

        private Path file(String name) {
            return Paths.get("pack", name);
        }

        @Test
        @DisplayName("only .cleartext is excluded from the copy")
        void excludesOnlyCleartextMarker() {
            assertFalse(CipherUtils.shouldBeCopied(file(FsStoryPackReader.CLEARTEXT_FILENAME)));

            for (String name : new String[]{"ni", "li", "ri", "si", "nm", "bt", "00000000"}) {
                assertTrue(CipherUtils.shouldBeCopied(file(name)), name + " should be copied");
            }
        }

        @Test
        @DisplayName("ni, nm and .cleartext stay in clear; every other file is ciphered")
        void cipherWhitelistIsByFilenameOnly() {
            for (String name : new String[]{"ni", "nm", FsStoryPackReader.CLEARTEXT_FILENAME}) {
                assertFalse(CipherUtils.shouldBeCiphered(file(name)), name + " must stay in clear");
            }
            for (String name : new String[]{"li", "ri", "si", "bt", "00000000"}) {
                assertTrue(CipherUtils.shouldBeCiphered(file(name)), name + " must be ciphered");
            }
        }

        @Test
        @DisplayName("KNOWN GAP: selection is filename-only, so an asset named 'ni' escapes ciphering")
        void selectionIgnoresLocation() {
            // Decided on getFileName() alone, with no regard for the location inside the pack.
            // An image or sound asset that happens to be named "ni" or "nm" would be copied in clear.
            // Asset files are named "00000000", "00000001"... so this is not reachable today —
            // but nothing in the code prevents it.
            assertFalse(CipherUtils.shouldBeCiphered(Paths.get("pack", "rf", "000", "ni")));
        }
    }
}
