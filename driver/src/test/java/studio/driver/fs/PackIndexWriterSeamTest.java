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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the index writer is a seam: the driver delegates to whichever {@link PackIndexWriter} it was
 * given, and does not write {@code .pi} itself.
 *
 * <p>This is structural, and deliberately nothing more. It exists so the extraction is not merely
 * assumed to have worked, and so that the lots that come next have somewhere to hook a writer that
 * fails at a chosen point. <strong>It asserts no integrity property</strong> — not atomicity, not
 * cleanup, not durability, not what survives an interruption. None of those exist yet.
 *
 * <p>What the current writer actually does is characterised, unchanged, by
 * {@code WritePathCharacterizationTest} and {@code Fat32WritePathCharacterizationTest}.
 */
class PackIndexWriterSeamTest {

    @TempDir
    Path partition;

    private static final UUID FIRST = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SECOND = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    /** Records what it was asked to install, and installs nothing. */
    private static final class RecordingPackIndexWriter implements PackIndexWriter {
        private final List<String> paths = new ArrayList<>();
        private final List<List<UUID>> indexes = new ArrayList<>();

        @Override
        public void write(String packIndexFilePath, List<UUID> packUUIDs) {
            paths.add(packIndexFilePath);
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

    @Test
    @DisplayName("the driver delegates the index write to the writer it was given")
    void anInjectedWriterIsUsed() throws IOException {
        byte[] original = encode(FIRST, SECOND);
        Files.write(partition.resolve(".pi"), original);
        RecordingPackIndexWriter writer = new RecordingPackIndexWriter();
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition, writer);

        // reorderPacks is an existing driver path that ends in an index write, and it needs nothing
        // on the partition beyond the index itself.
        driver.reorderPacks(List.of(SECOND.toString(), FIRST.toString())).join();

        assertEquals(1, writer.indexes.size(), "the writer should have been asked exactly once");
        assertEquals(List.of(SECOND, FIRST), writer.indexes.get(0),
                "the driver decides the order and passes it through");
        assertTrue(writer.paths.get(0).endsWith(".pi"),
                "the driver passes the index location, got: " + writer.paths.get(0));

        // And the driver wrote nothing itself: this writer installed nothing, so the file on disk is
        // untouched. Before the extraction the bytes would have been replaced.
        assertArrayEquals(original, Files.readAllBytes(partition.resolve(".pi")),
                "the driver must not write .pi behind the writer's back");
    }

    @Test
    @DisplayName("a writer that fails surfaces as the driver's existing index-write failure")
    void aFailingWriterSurfacesAsAStoryTellerException() throws IOException {
        // The point is only that the failure travels: the driver keeps the error contract its callers
        // already depend on, wrapping whatever the writer threw.
        Files.write(partition.resolve(".pi"), encode(FIRST));
        IOException raised = new IOException("writer refused");
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition,
                (path, uuids) -> {
                    throw raised;
                });

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> driver.reorderPacks(List.of(FIRST.toString())).join());

        Throwable cause = thrown.getCause();
        assertTrue(cause instanceof StoryTellerException, "got: " + cause);
        assertEquals(raised, cause.getCause(), "the writer's own failure must be the cause");
    }
}
