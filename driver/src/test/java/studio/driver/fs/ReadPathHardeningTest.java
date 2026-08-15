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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import studio.driver.StoryTellerException;
import studio.driver.model.fs.FsStoryPackInfos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the read path must guarantee, as opposed to what it happens to do.
 *
 * <p>These are <strong>specification</strong> tests, not characterization: every assertion here
 * states a property the driver is required to hold. Two properties, both established as broken by
 * the C6d audit:
 *
 * <ol>
 *   <li><strong>Framing.</strong> {@code .pi} is a bare concatenation of 16-byte records. A file
 *       whose length is not a whole number of records is structurally invalid, and the reader must
 *       say so rather than invent a pack out of a partial record. {@code .pi} is the only authority
 *       on what the device contains and it cannot be rebuilt from the card, so a fabricated UUID is
 *       not a cosmetic defect: it is an entry that points nowhere and that nothing can undo.</li>
 *   <li><strong>Local degradation.</strong> An index entry whose {@code .content} folder is missing
 *       is a <em>local</em> inconsistency. It must not make the whole device unreadable, which is
 *       what happens today: {@code getPacksList} opens {@code ni} for every entry in turn and one
 *       {@code FileNotFoundException} fails the entire listing.</li>
 * </ol>
 *
 * <p>The distinction between the two is deliberate and is the point of this class. A malformed
 * {@code .pi} is a failure of the index itself, so it fails globally. A valid index entry with no
 * content behind it is a failure of one pack, so it degrades locally. The two must not be
 * conflated.
 *
 * <p>Nothing here writes to the partition beyond building its own fixture, and nothing asserts any
 * repair: the driver is expected to tolerate these states, never to fix them.
 */
class ReadPathHardeningTest {

    @TempDir
    Path partition;

    private static final UUID PRESENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ORPHAN = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID SECOND_PRESENT = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    private static byte[] encode(UUID... uuids) {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        return buffer.array();
    }

    private void writeIndex(byte[] content) throws IOException {
        Files.write(partition.resolve(".pi"), content);
    }

    @SuppressWarnings("unchecked")
    private List<UUID> readPackIndex(FsStoryTellerAsyncDriver driver) throws Throwable {
        return ((CompletableFuture<List<UUID>>)
                DriverTestSupport.invokePrivate(driver, "readPackIndex")).join();
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
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

    // ------------------------------------------------------------------ R1: framing

    @Nested
    @DisplayName("R1 — a .pi that is not a whole number of 16-byte records is rejected")
    class R1IndexFraming {

        /**
         * The residues that matter are the boundaries: one stray byte, and one byte short of a full
         * record. Both are read today, and both fabricate a UUID — the first from an almost entirely
         * zeroed buffer, the second from fifteen real bytes plus one that was never written.
         */
        @ParameterizedTest(name = "{0} trailing byte(s) after a complete record")
        @ValueSource(ints = {1, 15})
        @DisplayName("a trailing partial record makes the whole index invalid")
        void aTrailingPartialRecordIsRejected(int residue) throws IOException {
            byte[] complete = encode(PRESENT);
            byte[] truncated = Arrays.copyOf(encode(PRESENT, ORPHAN), complete.length + residue);
            writeIndex(truncated);
            FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);

            Throwable raised = assertThrows(Throwable.class, () -> readPackIndex(driver));

            assertTrue(hasCause(raised, StoryTellerException.class),
                    "a structurally invalid index must be reported, got: " + raised);
        }

        @Test
        @DisplayName("a lone partial record does not become a pack")
        void aLonePartialRecordIsNotAPack() throws IOException {
            // The pathological case: fifteen bytes produce one pack today, and it is not the pack
            // those fifteen bytes came from.
            writeIndex(Arrays.copyOf(encode(PRESENT), 15));
            FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);

            Throwable raised = assertThrows(Throwable.class, () -> readPackIndex(driver));

            assertTrue(hasCause(raised, StoryTellerException.class),
                    "15 bytes are not a pack, got: " + raised);
        }

        @Test
        @DisplayName("a well-formed index is still read exactly as before")
        void wellFormedIndexesAreUnaffected() throws Throwable {
            // The guard must not cost anything on the nominal path. This is the control: were the
            // framing check too strict, this is the test that would catch it.
            FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);

            writeIndex(new byte[0]);
            assertEquals(List.of(), readPackIndex(driver), "an empty index is empty, not invalid");

            writeIndex(encode(PRESENT, ORPHAN, SECOND_PRESENT));
            assertEquals(List.of(PRESENT, ORPHAN, SECOND_PRESENT), readPackIndex(driver),
                    "order and big-endian decoding are unchanged");
        }

        @Test
        @DisplayName("the invalid index is left exactly as it was found")
        void anInvalidIndexIsNotRepaired() throws IOException {
            // Rejecting is not repairing. Nothing may truncate, pad or rewrite .pi to make it parse.
            byte[] truncated = Arrays.copyOf(encode(PRESENT, ORPHAN), 20);
            writeIndex(truncated);
            FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);

            assertThrows(Throwable.class, () -> readPackIndex(driver));

            assertArrayEquals(truncated, Files.readAllBytes(partition.resolve(".pi")),
                    "the driver must not touch a malformed index");
        }

        private void assertArrayEquals(byte[] expected, byte[] actual, String message) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, message);
        }
    }

    // ------------------------------------------------------------------ R2: orphan entries

    @Nested
    @DisplayName("R2 — an index entry with no content does not hide the packs that do exist")
    class R2OrphanIndexEntry {

        /**
         * Builds a materialisable pack: a {@code .content} folder named after the UUID, holding a
         * {@code ni} long enough for the version to be read out of it.
         */
        private void materialise(FsStoryTellerAsyncDriver driver, UUID uuid) throws IOException {
            Path folder = partition.resolve(".content").resolve(driver.computePackFolderName(uuid.toString()));
            Files.createDirectories(folder);
            byte[] nodeIndex = new byte[512];
            ByteBuffer.wrap(nodeIndex).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(2, (short) 3);
            Files.write(folder.resolve("ni"), nodeIndex);
        }

        private List<UUID> listedUuids(FsStoryTellerAsyncDriver driver) {
            return driver.getPacksList().join().stream()
                    .map(FsStoryPackInfos::getUuid)
                    .collect(Collectors.toList());
        }

        @Test
        @DisplayName("an entry whose .content folder is absent is skipped, the others are listed")
        void aMissingContentFolderDoesNotFailTheListing() throws IOException {
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
            materialise(driver, PRESENT);
            materialise(driver, SECOND_PRESENT);
            // ORPHAN sits in the index between them, with nothing behind it.
            writeIndex(encode(PRESENT, ORPHAN, SECOND_PRESENT));

            List<UUID> listed = listedUuids(driver);

            assertEquals(List.of(PRESENT, SECOND_PRESENT), listed,
                    "the valid packs must survive an orphan entry, in order");
            assertFalse(listed.contains(ORPHAN), "the orphan entry must not be invented");
        }

        @Test
        @DisplayName("an entry whose folder exists but has no 'ni' is skipped too")
        void aMissingNodeIndexDoesNotFailTheListing() throws IOException {
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
            materialise(driver, PRESENT);
            // A folder with no `ni`: the shape a copy interrupted early leaves behind.
            Files.createDirectories(partition.resolve(".content")
                    .resolve(driver.computePackFolderName(ORPHAN.toString())));
            writeIndex(encode(PRESENT, ORPHAN));

            assertEquals(List.of(PRESENT), listedUuids(driver));
        }

        @Test
        @DisplayName("skipping an entry changes nothing on the partition")
        void anOrphanEntryIsNotRepaired() throws IOException {
            // The driver tolerates the inconsistency; it must never resolve it. Neither the index nor
            // the empty folder may be touched.
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
            materialise(driver, PRESENT);
            byte[] index = encode(PRESENT, ORPHAN);
            writeIndex(index);

            listedUuids(driver);

            assertArrayEquals(index, Files.readAllBytes(partition.resolve(".pi")),
                    "the index must not be rewritten to drop the orphan entry");
            assertFalse(Files.exists(partition.resolve(".content")
                            .resolve(driver.computePackFolderName(ORPHAN.toString()))),
                    "nothing may be created to satisfy the orphan entry");
        }

        @Test
        @DisplayName("a pack whose 'ni' is present but unreadable still fails the listing")
        void aCorruptNodeIndexIsNotSwallowed() throws IOException {
            // The tolerance is deliberately narrow. A `ni` that exists but cannot be parsed is a real
            // parsing defect of an existing pack, not an orphan entry, and it must keep surfacing.
            // Turning this into a silent skip would hide genuine corruption.
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
            materialise(driver, PRESENT);
            Path folder = partition.resolve(".content").resolve(driver.computePackFolderName(ORPHAN.toString()));
            Files.createDirectories(folder);
            Files.write(folder.resolve("ni"), new byte[]{0x01});   // too short to hold a version
            writeIndex(encode(PRESENT, ORPHAN));

            assertThrows(Throwable.class, () -> driver.getPacksList().join(),
                    "an unparseable pack must not be silently skipped");
        }

        @Test
        @DisplayName("a fully consistent device is listed exactly as before")
        void aConsistentDeviceIsUnaffected() throws IOException {
            // Control: the tolerance must not change the nominal listing.
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
            materialise(driver, PRESENT);
            materialise(driver, SECOND_PRESENT);
            writeIndex(encode(PRESENT, SECOND_PRESENT));

            assertEquals(List.of(PRESENT, SECOND_PRESENT), listedUuids(driver));
        }

        private void assertArrayEquals(byte[] expected, byte[] actual, String message) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, message);
        }
    }
}
