/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The block cipher the device transfer path is built on.
 *
 * <p>{@code CipherUtils} in the driver calls {@link XXTEACipher#btea} with a positive block count to
 * encipher and the negative of it to decipher, and every ciphered transfer to a device goes through
 * it. Nothing exercised it, which meant a change to the rounds, the delta constant or the endianness
 * of the key would have been caught only by a device refusing a pack — if at all, and long after.
 *
 * <p>These are <strong>specifications</strong>. The round trip is the property the transfer path
 * depends on; the fixed vector exists so that a change in the algorithm itself is visible as a
 * failing test rather than as packs that no longer play.
 */
@DisplayName("The XXTEA cipher")
class XXTEACipherTest {

    private static int[] commonKey() {
        return BytesUtils.toIntArray(XXTEACipher.COMMON_KEY, ByteOrder.BIG_ENDIAN);
    }

    private static int[] block(int size) {
        int[] data = new int[size];
        for (int i = 0; i < size; i++) {
            data[i] = 0x01020304 + i * 0x11111111;
        }
        return data;
    }

    @Test
    @DisplayName("deciphers what it ciphered")
    void roundTrip() {
        int[] plain = block(8);
        int[] working = Arrays.copyOf(plain, plain.length);

        int[] ciphered = XXTEACipher.btea(working, working.length, commonKey());
        int[] deciphered = XXTEACipher.btea(Arrays.copyOf(ciphered, ciphered.length),
                -ciphered.length, commonKey());

        assertArrayEquals(plain, deciphered, "decipher(cipher(x)) should be x");
    }

    @Test
    @DisplayName("actually changes the data, so the round trip is not trivially true")
    void cipheringChangesTheBlock() {
        int[] plain = block(8);

        int[] ciphered = XXTEACipher.btea(Arrays.copyOf(plain, plain.length), plain.length, commonKey());

        assertFalse(Arrays.equals(plain, ciphered), "ciphered data should differ from the plaintext");
    }

    @Test
    @DisplayName("round-trips the smallest block the algorithm accepts")
    void roundTripTwoWords() {
        // XXTEA is defined for at least two words; the driver's callers can pass small blocks when
        // a file is short, so the boundary is worth holding.
        int[] plain = block(2);

        int[] ciphered = XXTEACipher.btea(Arrays.copyOf(plain, plain.length), plain.length, commonKey());
        int[] deciphered = XXTEACipher.btea(Arrays.copyOf(ciphered, ciphered.length),
                -ciphered.length, commonKey());

        assertArrayEquals(plain, deciphered);
    }

    @Test
    @DisplayName("leaves a block of one word untouched — KNOWN GAP")
    void singleWordIsNotCiphered() {
        int[] plain = block(1);

        int[] result = XXTEACipher.btea(Arrays.copyOf(plain, plain.length), plain.length, commonKey());

        // XXTEA needs two words to mix anything, and this implementation returns a one-word block
        // unchanged rather than refusing it. A caller handing it a short tail gets plaintext back
        // and no indication that nothing happened. Recorded, not relied upon.
        assertArrayEquals(plain, result, "a single word passes through in clear");
    }

    @Test
    @DisplayName("produces the same ciphertext as it always has, for a fixed input and the common key")
    void fixedVector() {
        int[] ciphered = XXTEACipher.btea(new int[]{0x01020304, 0x05060708}, 2, commonKey());

        // Pinned from the current implementation rather than from an external specification: the
        // point is not that this value is canonical, it is that it stops changing silently. If a
        // refactor of the rounds or the key endianness alters it, this fails and the change is
        // deliberate rather than discovered on a device.
        assertEquals(2, ciphered.length);
        assertEquals(0x15189889, ciphered[0], "first word");
        assertEquals(0x9ba9310e, ciphered[1], "second word");
    }
}
