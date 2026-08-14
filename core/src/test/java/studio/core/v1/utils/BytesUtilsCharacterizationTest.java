/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterises {@link BytesUtils}, whose {@code reverseEndianness} is applied to the AES key and IV
 * of every V3 device before any pack byte is ciphered. A change in its semantics would produce packs
 * that no device can decrypt, so the exact behaviour — including its rough edges — is pinned here.
 */
class BytesUtilsCharacterizationTest {

    @Test
    @DisplayName("reverses bytes within each 4-byte group, not across the whole array")
    void reversesPerFourByteGroup() {
        byte[] twoGroups = {1, 2, 3, 4, 5, 6, 7, 8};

        byte[] reversed = BytesUtils.reverseEndianness(twoGroups);

        // Whole-array reversal would give {8,7,6,5,4,3,2,1}; the actual result reverses each group.
        assertArrayEquals(new byte[]{4, 3, 2, 1, 8, 7, 6, 5}, reversed);
    }

    @Test
    @DisplayName("is its own inverse on 4-byte-aligned input")
    void isSelfInverseWhenAligned() {
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (0x10 + i);
        }

        assertArrayEquals(key, BytesUtils.reverseEndianness(BytesUtils.reverseEndianness(key)));
    }

    @Test
    @DisplayName("KNOWN GAP: silently drops the trailing bytes of a non 4-byte-aligned array")
    void dropsUnalignedTail() {
        // toIntArray iterates data.length / 4 and discards the remainder, so the output is shorter
        // than the input with no error. Harmless today because it is only ever called on the 16-byte
        // AES key and IV, but it is a trap for any future caller.
        byte[] sixBytes = {1, 2, 3, 4, 5, 6};

        byte[] reversed = BytesUtils.reverseEndianness(sixBytes);

        assertEquals(4, reversed.length, "the last two bytes are dropped without warning");
        assertArrayEquals(new byte[]{4, 3, 2, 1}, reversed);
    }

    @Test
    @DisplayName("toIntArray and toByteArray round-trip for a given endianness")
    void intAndByteConversionsRoundTrip() {
        byte[] source = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x00, 0x11, 0x22, 0x33};

        for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            assertArrayEquals(source,
                    BytesUtils.toByteArray(BytesUtils.toIntArray(source, order), order),
                    "round-trip failed for " + order);
        }
    }
}
