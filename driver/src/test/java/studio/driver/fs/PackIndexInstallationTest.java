/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the new index is put in place — invariants I1 and I8.
 *
 * <h2>I1 — the installation must not go through the target</h2>
 *
 * <p>{@code Files.copy(REPLACE_EXISTING)} removes the target and then writes it, so there is an
 * instant during every single index rewrite where {@code .pi} is absent, empty or partial. That
 * window is opened deliberately, by us, on the one file that records what the device holds and that
 * <strong>cannot be rebuilt from the card</strong>. The replacement must instead be installed by a
 * single move of a file that is already complete somewhere else.
 *
 * <p><strong>No fallback.</strong> If the atomic move is not supported, the operation fails. Falling
 * back to the copy would reinstate exactly the behaviour being removed, silently, on whichever
 * filesystem happens not to support it.
 *
 * <h2>I8 — the temporary must be synchronised before it is installed</h2>
 *
 * <p>Closing a stream hands the bytes to the operating system. It does not ask the operating system
 * to hand them to the device. Installing an index whose content is still only in a cache would make
 * the move atomic with respect to the directory and meaningless with respect to the data.
 *
 * <h2>What none of this proves</h2>
 *
 * <p>An atomic move is atomic <em>as far as the filesystem is concerned</em>. It is not a proof of
 * crash-safety: FAT32 has no journal, and what a power cut does to a directory entry mid-update is
 * outside anything this repository can establish. {@code force} is a <strong>request</strong> that
 * the operating system push the file to the storage device; it is not evidence that the bytes
 * reached the NAND, since the card's controller and its translation layer are free to acknowledge
 * early. These tests pin what STUdio asks for, never what the hardware delivers.
 */
class PackIndexInstallationTest {

    @TempDir
    Path partition;

    private static final UUID PACK_A = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PACK_B = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID PACK_C = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private Path index() {
        return partition.resolve(".pi");
    }

    private Path temporary() {
        return partition.resolve(".pi.new");
    }

    private static byte[] encode(UUID... uuids) {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        return buffer.array();
    }

    private void writeIndexFile(UUID... uuids) throws IOException {
        Files.write(index(), encode(uuids));
    }

    private void write(TemporaryFilePackIndexWriter writer, UUID... uuids) throws IOException {
        writer.write(index().toString(), List.of(uuids));
    }

    /** Records the order in which the steps run, and runs each of them for real. */
    private static final class RecordingWriter extends TemporaryFilePackIndexWriter {
        private final List<String> steps = new ArrayList<>();

        @Override
        void createTemporary(Path temporary) throws IOException {
            steps.add("create");
            super.createTemporary(temporary);
        }

        @Override
        void writeIndexTo(Path temporary, List<UUID> packUUIDs) throws IOException {
            steps.add("write");
            super.writeIndexTo(temporary, packUUIDs);
        }

        @Override
        void syncTemporary(Path temporary) throws IOException {
            steps.add("sync");
            super.syncTemporary(temporary);
        }

        @Override
        void installIndex(Path temporary, Path index) throws IOException {
            steps.add("install");
            super.installIndex(temporary, index);
        }
    }

    // ------------------------------------------------------------------ I1

    @Nested
    @DisplayName("I1 — the index is installed by a move, not written through")
    class I1MoveNotCopy {

        @Test
        @DisplayName("installing consumes the temporary, which is what a move does and a copy does not")
        void installingConsumesTheTemporary() throws IOException {
            // The observable difference, and the reason it matters: a copy reads the source and writes
            // the target, so the target passes through an incomplete state. A move re-points the
            // directory entry at a file that was already complete, and the source ceases to exist.
            writeIndexFile(PACK_A);
            Files.write(temporary(), encode(PACK_B, PACK_C));

            new TemporaryFilePackIndexWriter().installIndex(temporary(), index());

            assertArrayEquals(encode(PACK_B, PACK_C), Files.readAllBytes(index()),
                    "the target carries the replacement");
            assertFalse(Files.exists(temporary()),
                    "and the source is gone: a copy would have left it behind");
        }

        @Test
        @DisplayName("installing over an absent index works too")
        void installingWithNoPreviousIndexWorks() throws IOException {
            Files.write(temporary(), encode(PACK_A));

            new TemporaryFilePackIndexWriter().installIndex(temporary(), index());

            assertArrayEquals(encode(PACK_A), Files.readAllBytes(index()));
            assertFalse(Files.exists(temporary()));
        }

        @Test
        @DisplayName("a successful write leaves the new index and no temporary")
        void aSuccessfulWriteInstallsTheIndex() throws IOException {
            writeIndexFile(PACK_A);

            write(new TemporaryFilePackIndexWriter(), PACK_C, PACK_A, PACK_B);

            assertArrayEquals(encode(PACK_C, PACK_A, PACK_B), Files.readAllBytes(index()),
                    "content and order are unchanged by how the file is installed");
            assertFalse(Files.exists(temporary()));
        }
    }

    // ------------------------------------------------------------------ I1: no fallback

    @Nested
    @DisplayName("I1 — an unsupported atomic move is refused, never worked around")
    class I1NoFallback {

        private TemporaryFilePackIndexWriter refusingAtomicMove() {
            return new TemporaryFilePackIndexWriter() {
                @Override
                void installIndex(Path temporary, Path index) throws IOException {
                    throw new AtomicMoveNotSupportedException(temporary.toString(), index.toString(),
                            "this filesystem cannot do that");
                }
            };
        }

        @Test
        @DisplayName("the previous index survives, byte for byte")
        void thePreviousIndexIsUntouched() throws IOException {
            writeIndexFile(PACK_A, PACK_B);
            byte[] before = Files.readAllBytes(index());

            assertThrows(IOException.class, () -> write(refusingAtomicMove(), PACK_C));

            assertArrayEquals(before, Files.readAllBytes(index()),
                    "refusing must leave the device exactly as it was found");
        }

        @Test
        @DisplayName("our temporary is cleaned up, as it is after any controlled failure")
        void theTemporaryIsCleanedUp() throws IOException {
            writeIndexFile(PACK_A);

            assertThrows(IOException.class, () -> write(refusingAtomicMove(), PACK_B));

            assertFalse(Files.exists(temporary()));
        }

        @Test
        @DisplayName("the refusal says so, and keeps the cause")
        void theRefusalIsExplicit() throws IOException {
            writeIndexFile(PACK_A);

            IOException raised = assertThrows(IOException.class, () -> write(refusingAtomicMove(), PACK_B));

            assertTrue(raised.getCause() instanceof AtomicMoveNotSupportedException,
                    "the filesystem's own answer must be preserved, got: " + raised.getCause());
            assertTrue(raised.getMessage().toLowerCase().contains("atomic"),
                    "the message must name what is missing, got: " + raised.getMessage());
        }
    }

    // ------------------------------------------------------------------ I8

    @Nested
    @DisplayName("I8 — the temporary is synchronised before it is installed")
    class I8SyncBeforeInstall {

        @Test
        @DisplayName("the steps run in order: create, write, sync, install")
        void syncHappensBeforeInstall() throws IOException {
            // Installing an index whose bytes are still only in a cache would make the move atomic
            // with respect to the directory and meaningless with respect to the data.
            writeIndexFile(PACK_A);
            RecordingWriter writer = new RecordingWriter();

            write(writer, PACK_A, PACK_B);

            assertEquals(List.of("create", "write", "sync", "install"), writer.steps);
        }

        @Test
        @DisplayName("the temporary is complete on disk by the time it is synchronised")
        void theTemporaryIsCompleteWhenSynchronised() throws IOException {
            // What the sync is asked to push: the whole index, not a prefix of it.
            writeIndexFile(PACK_A);
            List<byte[]> observed = new ArrayList<>();
            TemporaryFilePackIndexWriter writer = new TemporaryFilePackIndexWriter() {
                @Override
                void syncTemporary(Path temporary) throws IOException {
                    observed.add(Files.readAllBytes(temporary));
                    super.syncTemporary(temporary);
                }
            };

            write(writer, PACK_A, PACK_B, PACK_C);

            assertEquals(1, observed.size(), "the sync must happen exactly once");
            assertArrayEquals(encode(PACK_A, PACK_B, PACK_C), observed.get(0));
        }

        @Test
        @DisplayName("a sync that fails stops the operation before anything is installed")
        void aFailedSyncDoesNotInstallAnything() throws IOException {
            writeIndexFile(PACK_A);
            byte[] before = Files.readAllBytes(index());
            IOException planned = new IOException("sync refused");
            TemporaryFilePackIndexWriter writer = new TemporaryFilePackIndexWriter() {
                @Override
                void syncTemporary(Path temporary) throws IOException {
                    throw planned;
                }
            };

            IOException raised = assertThrows(IOException.class, () -> write(writer, PACK_B));

            assertEquals(planned, raised);
            assertArrayEquals(before, Files.readAllBytes(index()), "the index must not have moved");
            assertFalse(Files.exists(temporary()), "and our temporary is cleaned up");
        }
    }
}
