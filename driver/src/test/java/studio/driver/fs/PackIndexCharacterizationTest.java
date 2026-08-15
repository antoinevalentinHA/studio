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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterises how the pack index ({@code .pi}) is parsed.
 *
 * <p>{@code .pi} is a bare concatenation of 16-byte big-endian UUIDs with no header, no length and
 * no checksum. It is the only record of what the device contains, and it cannot be rebuilt from the
 * card: a {@code .content} folder is named after the last 8 hex characters of its UUID only.
 *
 * <p>The tests below pin what the reader does with a well-formed index, and — more usefully — what
 * it does with a malformed one. The malformed cases used to record that a partial record was
 * silently turned into a pack; they now require it to be rejected, which is what the reader does
 * since the read path was hardened.
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
    @DisplayName("a 15-byte index is rejected rather than turned into a pack")
    void rejectsATruncatedIndex() {
        // The reader used to loop on `while (fis.read(buffer16) > 0)` without checking how many bytes
        // came back. A short final read left the tail of the reusable buffer untouched, so a
        // truncated index became a pack UUID that was never written — an entry pointing nowhere, in
        // the one file that cannot be rebuilt from the card.
        byte[] fifteenBytes = Arrays.copyOf(encode(FIRST), 15);

        Throwable raised = assertThrows(Throwable.class, () -> readIndex(fifteenBytes));

        assertTrue(hasCauseOfType(raised, StoryTellerException.class),
                "a truncated index must be reported, got: " + raised);
    }

    @Test
    @DisplayName("a trailing partial entry invalidates the whole index")
    void rejectsAPartialTrailingEntry() {
        // Worse than the case above: the 16-byte buffer was reused across iterations, so a partial
        // final read mixed fresh bytes with leftovers from the pack read just before. The complete
        // entry that precedes it does not redeem the file — the index is invalid as a whole.
        byte[] onePackPlusFourBytes = Arrays.copyOf(encode(FIRST, SECOND), 20);

        Throwable raised = assertThrows(Throwable.class, () -> readIndex(onePackPlusFourBytes));

        assertTrue(hasCauseOfType(raised, StoryTellerException.class),
                "a partial trailing entry must be reported, got: " + raised);
    }

    @Test
    @DisplayName("every size that is not a multiple of 16 is rejected")
    void rejectsEverySizeThatIsNotAMultipleOfSixteen() {
        // A valid .pi has a size that is a multiple of 16. It is the cheapest integrity signal the
        // format offers, and the only one: there is no header, no length and no checksum.
        for (int size : new int[]{1, 15, 17, 31, 33}) {
            byte[] content = Arrays.copyOf(encode(FIRST, SECOND, THIRD), size);

            Throwable raised = assertThrows(Throwable.class, () -> readIndex(content),
                    "a " + size + "-byte index must not be accepted");

            assertTrue(hasCauseOfType(raised, StoryTellerException.class),
                    "a " + size + "-byte index must be reported as invalid, got: " + raised);
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
