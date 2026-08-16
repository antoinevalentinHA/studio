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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the index writer owes the partition regarding its temporary file — invariant I2.
 *
 * <p>Two properties, and they pull in opposite directions, which is why they are stated together:
 *
 * <ol>
 *   <li><strong>A temporary this operation created is this operation's to remove.</strong> Whether
 *       the write succeeds or fails, {@code .pi.new} must not be left on the device. It used to
 *       survive every failure after its own creation, holding an index that was never installed.</li>
 *   <li><strong>A temporary that was already there is not ours at all.</strong> It must be neither
 *       truncated nor deleted. It could be the residue of an interrupted operation, of an older
 *       STUdio, of another tool, or of the firmware; the origin is not knowable from here, and
 *       destroying it could destroy the only copy of an index. The write is refused instead.</li>
 * </ol>
 *
 * <p>Ownership is decided once, at creation, and only for the duration of the call: the file is
 * created with {@code CREATE_NEW}, so either this operation made it or it was already there. There
 * is no persisted marker, no timestamp heuristic, no attempt to recognise the content, and no
 * sweep at start-up — every one of those would eventually delete something that mattered.
 *
 * <p><strong>None of this makes the write safe.</strong> The index is still installed with a
 * non-atomic {@code Files.copy(REPLACE_EXISTING)}, nothing is flushed to the card, and a power loss
 * mid-copy is not covered by anything here. Removing a stray temporary is housekeeping, not
 * durability.
 */
class PackIndexTemporaryFileTest {

    @TempDir
    Path partition;

    private static final UUID PACK_A = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PACK_B = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    private static final byte[] SENTINEL = "not ours - do not touch".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

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

    private void writeIndex(UUID... uuids) throws IOException {
        Files.write(index(), encode(uuids));
    }

    private void write(TemporaryFilePackIndexWriter writer, UUID... uuids) throws IOException {
        writer.write(index().toString(), List.of(uuids));
    }

    /** Everything sitting at the partition root, so a stray file cannot go unnoticed. */
    private List<String> rootFiles() throws IOException {
        try (Stream<Path> paths = Files.list(partition)) {
            return paths.map(p -> p.getFileName().toString()).sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        }
    }

    /**
     * A writer that fails at one chosen step and runs the real code everywhere else.
     *
     * <p>The point of overriding a single step rather than the whole {@code write} is that the code
     * under test still runs: a fake that replaced the operation wholesale would short-circuit exactly
     * the ownership and cleanup logic these tests are about.
     */
    private static final class FailingAt extends TemporaryFilePackIndexWriter {
        private final String step;
        private final IOException failure;

        private FailingAt(String step, IOException failure) {
            this.step = step;
            this.failure = failure;
        }

        @Override
        void writeIndexTo(Path temporary, List<UUID> packUUIDs) throws IOException {
            super.writeIndexTo(temporary, packUUIDs);
            if ("write".equals(step)) {
                throw failure;
            }
        }

        @Override
        void installIndex(Path temporary, Path index) throws IOException {
            if ("install".equals(step)) {
                throw failure;
            }
            super.installIndex(temporary, index);
        }

        @Override
        void deleteTemporary(Path temporary) throws IOException {
            if ("delete".equals(step)) {
                throw failure;
            }
            super.deleteTemporary(temporary);
        }
    }

    // ------------------------------------------------------------------ R1

    @Nested
    @DisplayName("R1 — a failure after the temporary was created removes it again")
    class R1FailureRemovesOurTemporary {

        @Test
        @DisplayName("an installation that fails leaves no temporary behind")
        void aFailedInstallationRemovesTheTemporary() throws IOException {
            writeIndex(PACK_A, PACK_B);
            byte[] before = Files.readAllBytes(index());
            IOException planned = new IOException("installation refused");

            IOException raised = assertThrows(IOException.class,
                    () -> write(new FailingAt("install", planned), PACK_A));

            assertEquals(planned, raised, "the failure must reach the caller unchanged");
            assertFalse(Files.exists(temporary()),
                    "the temporary this operation created must not survive its failure");
            assertArrayEquals(before, Files.readAllBytes(index()),
                    "an installation that never happened must not have touched the index");
            assertEquals(List.of(".pi"), rootFiles(), "nothing else may be left at the root");
        }

        @Test
        @DisplayName("a write that fails part-way leaves no temporary behind either")
        void aFailedWriteRemovesTheTemporary() throws IOException {
            writeIndex(PACK_A);
            IOException planned = new IOException("disk gave up mid-write");

            IOException raised = assertThrows(IOException.class,
                    () -> write(new FailingAt("write", planned), PACK_A, PACK_B));

            assertEquals(planned, raised);
            assertFalse(Files.exists(temporary()), "a half-written temporary is still ours to remove");
            assertEquals(List.of(".pi"), rootFiles());
        }

        @Test
        @DisplayName("a cleanup that also fails does not hide why the write failed")
        void aFailingCleanupDoesNotMaskThePrimaryFailure() throws IOException {
            // Both steps refuse. The caller needs the reason the index was not installed; the cleanup
            // problem is secondary and travels as a suppressed exception rather than replacing it.
            writeIndex(PACK_A);
            IOException planned = new IOException("installation refused");
            TemporaryFilePackIndexWriter writer = new TemporaryFilePackIndexWriter() {
                @Override
                void installIndex(Path temporary, Path index) throws IOException {
                    throw planned;
                }

                @Override
                void deleteTemporary(Path temporary) throws IOException {
                    throw new IOException("cleanup refused too");
                }
            };

            IOException raised = assertThrows(IOException.class, () -> write(writer, PACK_B));

            assertEquals(planned, raised, "the primary failure must be the one that surfaces");
            assertEquals(1, raised.getSuppressed().length, "the cleanup failure must not be lost");
            assertEquals("cleanup refused too", raised.getSuppressed()[0].getMessage());
        }
    }

    // ------------------------------------------------------------------ R2

    @Nested
    @DisplayName("R2 — the nominal path is unchanged")
    class R2NominalPath {

        @Test
        @DisplayName("a successful write installs the index and removes the temporary")
        void aSuccessfulWriteLeavesOnlyTheIndex() throws IOException {
            writeIndex(PACK_A);

            write(new TemporaryFilePackIndexWriter(), PACK_A, PACK_B);

            assertArrayEquals(encode(PACK_A, PACK_B), Files.readAllBytes(index()));
            assertFalse(Files.exists(temporary()));
            assertEquals(List.of(".pi"), rootFiles());
        }

        @Test
        @DisplayName("a write onto a partition with no index yet still works")
        void anAbsentIndexIsCreated() throws IOException {
            write(new TemporaryFilePackIndexWriter(), PACK_A);

            assertArrayEquals(encode(PACK_A), Files.readAllBytes(index()));
            assertEquals(List.of(".pi"), rootFiles());
        }

        @Test
        @DisplayName("two writes in a row work: the first leaves nothing to block the second")
        void consecutiveWritesSucceed() throws IOException {
            TemporaryFilePackIndexWriter writer = new TemporaryFilePackIndexWriter();

            write(writer, PACK_A);
            write(writer, PACK_A, PACK_B);

            assertArrayEquals(encode(PACK_A, PACK_B), Files.readAllBytes(index()));
            assertEquals(List.of(".pi"), rootFiles());
        }
    }

    // ------------------------------------------------------------------ R3

    @Nested
    @DisplayName("R3 — a temporary that was already there is left strictly alone")
    class R3PreexistingTemporary {

        @Test
        @DisplayName("the write is refused, and the pre-existing temporary is untouched")
        void aPreexistingTemporaryIsNeitherOverwrittenNorRemoved() throws IOException {
            writeIndex(PACK_A);
            byte[] indexBefore = Files.readAllBytes(index());
            Files.write(temporary(), SENTINEL);

            IOException raised = assertThrows(IOException.class,
                    () -> write(new TemporaryFilePackIndexWriter(), PACK_A, PACK_B));

            assertArrayEquals(SENTINEL, Files.readAllBytes(temporary()),
                    "a temporary we did not create is not ours to truncate");
            assertTrue(Files.exists(temporary()), "nor ours to delete");
            assertArrayEquals(indexBefore, Files.readAllBytes(index()),
                    "and the index must not have been touched either");
            assertEquals(List.of(".pi", ".pi.new"), rootFiles(), "no third file may appear");
            assertTrue(raised.getMessage().contains(".pi.new"),
                    "the refusal must name what is in the way, got: " + raised.getMessage());
        }

        @Test
        @DisplayName("an empty pre-existing temporary is refused just the same")
        void anEmptyPreexistingTemporaryIsAlsoRefused() throws IOException {
            // Emptiness is not evidence of anything. Deciding it is disposable would be exactly the
            // heuristic this invariant refuses to make.
            writeIndex(PACK_A);
            Files.createFile(temporary());

            assertThrows(IOException.class, () -> write(new TemporaryFilePackIndexWriter(), PACK_B));

            assertTrue(Files.exists(temporary()));
            assertArrayEquals(encode(PACK_A), Files.readAllBytes(index()));
        }

        @Test
        @DisplayName("the refusal carries the filesystem cause rather than inventing one")
        void theRefusalKeepsTheFilesystemCause() throws IOException {
            writeIndex(PACK_A);
            Files.write(temporary(), SENTINEL);

            IOException raised = assertThrows(IOException.class,
                    () -> write(new TemporaryFilePackIndexWriter(), PACK_B));

            assertTrue(raised.getCause() instanceof java.nio.file.FileAlreadyExistsException,
                    "expected the create to be the cause, got: " + raised.getCause());
        }
    }

    // ------------------------------------------------------------------ R4

    @Nested
    @DisplayName("R4 — a successful installation leaves nothing to clean up")
    class R4NoCleanupAfterSuccessfulInstall {

        @Test
        @DisplayName("the temporary is consumed by the installation, so no cleanup runs after it")
        void aSuccessfulInstallationNeedsNoCleanup() throws IOException {
            // This used to be a genuine question: an operation whose effect was already committed
            // reported failure when only its cleanup had failed. The question has no subject any
            // more — the index is installed by moving the temporary onto it, which consumes the
            // source, so there is no post-installation step left that could fail.
            //
            // The writer below refuses every cleanup. A successful write must therefore not call it
            // at all: reintroducing a cleanup after a successful installation would fire this test.
            writeIndex(PACK_A);
            List<Path> cleanupCalls = new ArrayList<>();
            TemporaryFilePackIndexWriter writer = new TemporaryFilePackIndexWriter() {
                @Override
                void deleteTemporary(Path temporary) throws IOException {
                    cleanupCalls.add(temporary);
                    throw new IOException("nothing should need cleaning up after a successful install");
                }
            };

            write(writer, PACK_A, PACK_B);

            assertEquals(List.of(), cleanupCalls,
                    "deleteTemporary must not be called once the index is installed");
            assertArrayEquals(encode(PACK_A, PACK_B), Files.readAllBytes(index()),
                    "and the write is a success: the new index is in place");
            assertFalse(Files.exists(temporary()), "the installation consumed the temporary");
        }
    }
}
