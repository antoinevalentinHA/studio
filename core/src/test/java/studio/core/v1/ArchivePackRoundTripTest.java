/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What survives a write and a read back in the archive format.
 *
 * <p>The archive format is a zip with a description of the story beside its assets, so it can record
 * things the sector-based raw format cannot. This is where the two differ, and the differences are
 * worth pinning: a conversion between them is what the library offers a user, and it is lossy in one
 * direction and not the other.
 */
@DisplayName("An archive pack, written and read back")
class ArchivePackRoundTripTest {

    private static StoryPack roundTrip(StoryPack pack) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ArchiveStoryPackWriter().write(pack, out);
        return new ArchiveStoryPackReader().read(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    @DisplayName("KNOWN GAP: not the pack's own uuid — no pack-level uuid is stored at all")
    void packUuidIsReplacedByTheFirstNodes() throws IOException {
        StoryPack read = roundTrip(PackFixtures.twoStagePack());

        // The same substitution as the raw format, and for a more basic reason: the archive writer
        // never writes a pack-level uuid. It writes one uuid per stage node, and the reader builds
        // the pack from `nodes.get(0).getUuid()`.
        //
        // So of the three formats only FS carries a pack uuid of its own. A pack read from an FS
        // folder, written to archive or raw and read back, comes out identified by its first node.
        // LibraryService names converted artifacts `<uuid>.converted_<millis>` from exactly this
        // value, so the name follows the node rather than the pack.
        assertEquals("11111111-1111-1111-1111-111111111111", read.getUuid(),
                "the first stage node's uuid");
        assertNotEquals(PackFixtures.UUID, read.getUuid(), "not the pack's own uuid");
    }

    @Test
    @DisplayName("keeps the stage nodes and their uuids")
    void keepsStageNodes() throws IOException {
        StoryPack original = PackFixtures.twoStagePack();
        StoryPack read = roundTrip(original);

        assertEquals(original.getStageNodes().size(), read.getStageNodes().size(), "node count");
        for (int i = 0; i < original.getStageNodes().size(); i++) {
            assertEquals(original.getStageNodes().get(i).getUuid(),
                    read.getStageNodes().get(i).getUuid(), "uuid of node " + i);
        }
    }

    @Test
    @DisplayName("keeps asset bytes exactly, with no sector padding")
    void keepsAssetBytesExactly() throws IOException {
        // Deliberately not a multiple of the sector size. The raw format would pad this to 1024
        // bytes; a zip entry has a length of its own, so the archive format has no reason to.
        int oddSize = Constants.SECTOR_SIZE + 1;
        StoryPack original = PackFixtures.packWithAssetSizes(oddSize, oddSize);

        StoryPack read = roundTrip(original);

        for (int i = 0; i < original.getStageNodes().size(); i++) {
            StageNode before = original.getStageNodes().get(i);
            StageNode after = read.getStageNodes().get(i);
            assertArrayEquals(before.getImage().getRawData(), after.getImage().getRawData(),
                    "image bytes of node " + i);
            assertEquals(oddSize, after.getImage().getRawData().length,
                    "image length of node " + i + " is not rounded up");
        }
    }

    @Test
    @DisplayName("keeps the transitions and the shared action node")
    void keepsTransitions() throws IOException {
        StoryPack read = roundTrip(PackFixtures.twoStagePack());

        StageNode first = read.getStageNodes().get(0);
        assertNotNull(first.getOkTransition(), "ok transition");
        assertNotNull(first.getOkTransition().getActionNode(), "action node");
        assertEquals(1, first.getOkTransition().getActionNode().getOptions().size(),
                "action node options");
    }

    @Test
    @DisplayName("keeps an option that points at the first stage node, unlike the raw format")
    void keepsOptionOnTheFirstNode() throws IOException {
        StoryPack read = roundTrip(PackFixtures.packWithOptionOnFirstNode());

        // The counterpart of BinaryPackRoundTripTest#optionOnTheFirstNodeTruncatesTheList. Options
        // are named here rather than addressed by sector, so nothing collides with a terminator and
        // the list comes back whole. It is the same story in both formats; only one of them can
        // express it.
        assertEquals(2, read.getStageNodes().get(0).getOkTransition()
                        .getActionNode().getOptions().size(),
                "both options survive the archive format");
    }
}
