/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@link FsStoryTellerAsyncDriver#reorderPacks(List)} accepts, and what it does with what it
 * accepts.
 *
 * <p>The order of {@code .pi} is the order the Lunii plays packs in, and this method is the only
 * thing that rewrites it. Its guard checks that every submitted UUID is on the device. It does not
 * check the converse, so a caller may submit a <em>subset</em> of the index and have the request
 * honoured. Every UUID the caller left out then sorts by {@code indexOf == -1} and lands at the
 * front of the device.
 *
 * <p>That is not hypothetical from the UI's side: {@code getPacksList()} drops any index entry whose
 * {@code .content} folder has no {@code ni} file, so the list the browser holds — and sends back —
 * can legitimately be shorter than the index it is reordering.
 *
 * <p>These tests record the behaviour as it stands today. They are characterization, not approval:
 * the assertions below describe a defect.
 */
class PackIndexReorderValidationTest {

    @TempDir
    Path partition;

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** Records the index it is handed, and installs nothing. */
    private static final class RecordingPackIndexWriter implements PackIndexWriter {
        private final List<List<UUID>> indexes = new ArrayList<>();

        @Override
        public void write(String packIndexFilePath, List<UUID> packUUIDs) {
            indexes.add(new ArrayList<>(packUUIDs));
        }
    }

    private static byte[] encode(UUID... uuids) {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        return buffer.array();
    }

    private RecordingPackIndexWriter reorderWith(byte[] index, List<String> submitted) throws IOException {
        Files.write(partition.resolve(".pi"), index);
        RecordingPackIndexWriter writer = new RecordingPackIndexWriter();
        DriverTestSupport.pluggedDriverMountedOn(partition, writer).reorderPacks(submitted).join();
        return writer;
    }

    @Test
    @DisplayName("a partial list is accepted, and the packs left out are moved to the front")
    void aPartialListIsAcceptedAndRelocatesTheOmittedPacks() throws IOException {
        // The device holds three packs. The caller submits two of them, in their existing relative
        // order — the request means "change nothing". A is not mentioned at all.
        RecordingPackIndexWriter writer = reorderWith(encode(A, B, C), List.of(B.toString(), C.toString()));

        assertEquals(1, writer.indexes.size(), "the request was accepted and an index was written");
        assertEquals(List.of(A, B, C), writer.indexes.get(0),
                "A, which the caller never mentioned, was placed first");
    }

    @Test
    @DisplayName("a pack absent from the submitted list overtakes the packs that were listed")
    void anOmittedPackOvertakesTheListedOnes() throws IOException {
        // Same device, but now the caller asks for an order that puts the packs it knows about
        // ahead of where C sits. C is invisible to it, so it goes to the head instead.
        RecordingPackIndexWriter writer = reorderWith(encode(A, B, C), List.of(B.toString(), A.toString()));

        assertEquals(List.of(C, B, A), writer.indexes.get(0),
                "C was never submitted, yet it ends up first on the device");
    }

    @Test
    @DisplayName("a submitted list containing the same pack twice is accepted")
    void duplicatesAreNotRejected() throws IOException {
        RecordingPackIndexWriter writer = reorderWith(encode(A, B),
                List.of(B.toString(), B.toString(), A.toString()));

        assertEquals(1, writer.indexes.size(), "the duplicate was not rejected");
        assertEquals(List.of(B, A), writer.indexes.get(0));
    }

    @Test
    @DisplayName("an empty list is accepted and rewrites the index")
    void anEmptyListIsAccepted() throws IOException {
        // allMatch over an empty stream is vacuously true, so the guard lets this through.
        RecordingPackIndexWriter writer = reorderWith(encode(A, B), List.of());

        assertEquals(1, writer.indexes.size(), "an empty reorder request still triggers a write");
    }
}
