/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.driver.StoryTellerException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterises how the pack index ({@code .pi}) is parsed.
 *
 * <p>{@code .pi} is a bare concatenation of 16-byte big-endian UUIDs with no header, no length and
 * no checksum. It is the only record of what the device contains, and it cannot be rebuilt from the
 * card: a {@code .content} folder is named after the last 8 hex characters of its UUID only.
 *
 * <p>The tests below pin what the current reader does with a well-formed index, and — more usefully
 * — what it does with a malformed one. Nothing here asserts that the malformed cases are handled
 * correctly; they are not.
 */
class PackIndexCharacterizationTest {

    @TempDir
    Path partition;

    private static final UUID FIRST = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SECOND = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID THIRD = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private static byte[] encode(UUID... uuids) {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        return buffer.array();
    }

    @SuppressWarnings("unchecked")
    private List<UUID> readIndex(byte[] content) throws Throwable {
        Files.write(partition.resolve(".pi"), content);
        FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);
        return ((java.util.concurrent.CompletableFuture<List<UUID>>)
                DriverTestSupport.invokePrivate(driver, "readPackIndex")).join();
    }

    // ---------------------------------------------------------------- well-formed

    @Test
    @DisplayName("an empty index yields no packs")
    void readsEmptyIndex() throws Throwable {
        assertEquals(List.of(), readIndex(new byte[0]));
    }

    @Test
    @DisplayName("reads UUIDs big-endian, in file order")
    void readsWellFormedIndex() throws Throwable {
        assertEquals(List.of(FIRST), readIndex(encode(FIRST)));
        assertEquals(List.of(FIRST, SECOND, THIRD), readIndex(encode(FIRST, SECOND, THIRD)));
    }

    @Test
    @DisplayName("order is significant — it is the order shown on the device")
    void preservesOrder() throws Throwable {
        assertEquals(List.of(THIRD, FIRST, SECOND), readIndex(encode(THIRD, FIRST, SECOND)));
    }

    @Test
    @DisplayName("a missing index fails rather than reporting an empty device")
    void failsWhenIndexIsAbsent() {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);

        Throwable raised = assertThrows(Throwable.class,
                () -> ((java.util.concurrent.CompletableFuture<?>)
                        DriverTestSupport.invokePrivate(driver, "readPackIndex")).join());

        assertTrue(hasCauseOfType(raised, StoryTellerException.class),
                "expected a StoryTellerException somewhere in " + raised);
    }

    private static boolean hasCauseOfType(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- malformed

    @Test
    @DisplayName("KNOWN GAP: a 15-byte index yields one fabricated UUID instead of an error")
    void truncatedIndexFabricatesAPack() throws Throwable {
        // The reader loops on `while (fis.read(buffer16) > 0)` and never checks how many bytes came
        // back. A short final read leaves the tail of the reusable buffer untouched, so a truncated
        // index is silently turned into a pack UUID that was never written.
        byte[] fifteenBytes = Arrays.copyOf(encode(FIRST), 15);

        List<UUID> packs = readIndex(fifteenBytes);

        assertEquals(1, packs.size(), "a truncated index should not produce a pack, but it does");
        assertNotEquals(FIRST, packs.get(0), "and the pack it produces is not the one that was written");
        // The 16th byte is whatever the freshly allocated buffer held, i.e. zero.
        ByteBuffer expected = ByteBuffer.wrap(Arrays.copyOf(fifteenBytes, 16));
        assertEquals(new UUID(expected.getLong(), expected.getLong()), packs.get(0));
    }

    @Test
    @DisplayName("KNOWN GAP: a trailing partial entry is completed with stale bytes of the previous one")
    void partialTrailingEntryReusesPreviousBytes() throws Throwable {
        // Worse than the case above: the 16-byte buffer is reused across iterations, so a partial
        // final read mixes fresh bytes with leftovers from the pack read just before.
        byte[] onePackPlusFourBytes = Arrays.copyOf(encode(FIRST, SECOND), 20);

        List<UUID> packs = readIndex(onePackPlusFourBytes);

        assertEquals(2, packs.size());
        assertEquals(FIRST, packs.get(0));

        byte[] stale = encode(FIRST);
        byte[] mixed = new byte[16];
        System.arraycopy(onePackPlusFourBytes, 16, mixed, 0, 4);   // 4 fresh bytes
        System.arraycopy(stale, 4, mixed, 4, 12);                  // 12 bytes left over from FIRST
        ByteBuffer expected = ByteBuffer.wrap(mixed);
        assertEquals(new UUID(expected.getLong(), expected.getLong()), packs.get(1));
    }

    @Test
    @DisplayName("KNOWN GAP: no size validation — a malformed index is never reported as such")
    void sizeIsNeverValidated() throws Throwable {
        // A valid .pi has a size that is a multiple of 16. Nothing checks it, at read or at write.
        // This is the cheapest possible integrity signal and it is currently unused.
        for (int size : new int[]{1, 15, 17, 31, 33}) {
            byte[] content = Arrays.copyOf(encode(FIRST, SECOND, THIRD), size);

            List<UUID> packs = readIndex(content);

            assertEquals((size + 15) / 16, packs.size(),
                    "a " + size + "-byte index is accepted and rounded up to " + ((size + 15) / 16) + " packs");
        }
    }

    @Test
    @DisplayName("duplicate entries are accepted and returned twice")
    void acceptsDuplicates() throws Throwable {
        // Relevant to a retried upload: nothing prevents the same UUID appearing twice in the index.
        List<UUID> packs = readIndex(encode(FIRST, FIRST));

        assertEquals(List.of(FIRST, FIRST), packs);
    }

    @Test
    @DisplayName("the index carries no link back to the content folder beyond the last 8 hex chars")
    void indexIsTheOnlyRecordOfTheFullUuid() throws IOException {
        // Documents why losing .pi is unrecoverable from the card alone: the folder name keeps 8 of
        // the 32 hex characters, so the full UUID cannot be reconstructed from the device.
        FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);

        String folder = driver.computePackFolderName(FIRST.toString());

        assertEquals("55555555", folder);
        assertTrue(FIRST.toString().replace("-", "").toUpperCase().endsWith(folder));
        assertEquals(8, folder.length(), "24 of the 32 hex characters are not stored anywhere on the device");
    }
}
