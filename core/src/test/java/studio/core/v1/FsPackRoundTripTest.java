/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.ActionNode;
import studio.core.v1.model.AudioAsset;
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.ImageAsset;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.Transition;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.utils.AudioConversion;
import studio.core.v1.utils.ImageConversion;
import studio.core.v1.writer.fs.FsStoryPackWriter;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An FS pack, written to a folder and read back — the format that actually goes onto the card.
 *
 * <p>The third of the three formats, and the one the other two round-trip tests point at as the
 * only one carrying a pack-level uuid. It turns out to carry it in the folder <em>name</em>, which
 * the writer shortens to the last eight hex digits (the device's own convention, {@code FORMATS.md}
 * §4): read back from the folder it wrote, the pack does not get its uuid either. That is pinned
 * below as a {@code KNOWN GAP}, next to the node uuids the format has no room for at all.
 *
 * <p>The writer refuses anything but a 320×240 4-bit RLE bitmap and an ID3-less mono MP3, so the
 * fixtures are made with the project's own converters from a drawn image and a synthesised tone.
 * That costs an MP3 encode per class, once, and exercises the two converters on the way. The byte
 * layout the tests pin ({@code ni} header, 44-byte nodes, 12-byte {@code ri} entries) is the one
 * verified against a device in {@code FORMATS.md} §5–6.
 */
@DisplayName("An FS pack, written and read back")
class FsPackRoundTripTest {

    private static final String FIRST = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND = "22222222-2222-2222-2222-222222222222";

    private static byte[] imageA, imageB, audioA, audioB;

    @BeforeAll
    static void makeDeviceCompatibleAssets() throws Exception {
        imageA = bitmap(Color.RED);
        imageB = bitmap(Color.BLUE);
        audioA = mp3(440);
        audioB = mp3(880);
        assertFalse(Arrays.equals(imageA, imageB), "fixture: the two images must differ");
        assertFalse(Arrays.equals(audioA, audioB), "fixture: the two sounds must differ");
    }

    // ---------------------------------------------------------------- fixtures

    /** A 320×240 bitmap of one colour with a contrasting stripe, through the project's converter. */
    private static byte[] bitmap(Color colour) throws Exception {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, 320, 240);
        g.setColor(Color.WHITE);
        g.fillRect(40, 100, 240, 40);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return ImageConversion.anyToRLECompressedBitmap(png.toByteArray());
    }

    /** A tenth of a second of a sine wave, as a WAV, through the project's MP3 converter. */
    private static byte[] mp3(double frequencyHz) throws Exception {
        float sampleRate = 22050f;
        int samples = (int) (sampleRate / 10);
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short value = (short) (Math.sin(2 * Math.PI * frequencyHz * i / sampleRate) * 12000);
            pcm[2 * i] = (byte) value;
            pcm[2 * i + 1] = (byte) (value >> 8);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, samples),
                AudioFileFormat.Type.WAVE, wav);
        return AudioConversion.anyToMp3(wav.toByteArray());
    }

    private static StageNode stageNode(String uuid, byte[] image, byte[] audio) {
        return new StageNode(uuid,
                image == null ? null : new ImageAsset("image/bmp", image, uuid + ".bmp"),
                audio == null ? null : new AudioAsset("audio/mp3", audio, uuid + ".mp3"),
                null, null,
                new ControlSettings(true, true, false, true, false),
                null);
    }

    /** The shape of {@link PackFixtures#twoStagePack()}, with assets the FS writer accepts. */
    private static StoryPack twoStagePack(boolean nightMode) {
        StageNode first = stageNode(FIRST, imageA, audioA);
        StageNode second = stageNode(SECOND, imageB, audioB);
        ActionNode action = new ActionNode(new ArrayList<>(Arrays.asList(second)), null);
        first.setOkTransition(new Transition(action, (short) 0));
        first.setHomeTransition(new Transition(action, (short) 0));
        second.setOkTransition(new Transition(action, (short) 0));
        second.setHomeTransition(new Transition(action, (short) 0));
        return new StoryPack(PackFixtures.UUID, false, (short) 3,
                new ArrayList<>(Arrays.asList(first, second)), null, nightMode);
    }

    private static Path write(StoryPack pack, Path folder) throws Exception {
        return new FsStoryPackWriter().write(pack, folder);
    }

    private static StoryPack roundTrip(StoryPack pack, Path folder) throws Exception {
        return new FsStoryPackReader().read(write(pack, folder));
    }

    // ---------------------------------------------------------------- identity

    @Test
    @DisplayName("KNOWN GAP: read back from the folder it wrote, the pack uuid is the 8-digit folder name")
    void packUuidIsTheShortFolderName(@TempDir Path folder) throws Exception {
        Path written = write(twoStagePack(false), folder);
        StoryPack read = new FsStoryPackReader().read(written);

        // The writer names the folder the way the device does — the last eight hex digits of the
        // uuid, upper case (FORMATS.md §4) — and the reader takes the folder name as the uuid. So
        // this is the one format that stores a pack-level identity, and it stores a quarter of it.
        // LibraryService sidesteps this by renaming the folder to `<full uuid>.converted_<millis>`
        // before the reader ever sees it; the driver never reads a pack's uuid from its folder.
        assertEquals("56789ABC", written.getFileName().toString());
        assertEquals("56789ABC", read.getUuid());
        assertNotEquals(PackFixtures.UUID, read.getUuid());
    }

    @Test
    @DisplayName("keeps the uuid when read from a folder named after it, as the library does")
    void packUuidSurvivesWhenTheFolderCarriesIt(@TempDir Path folder) throws Exception {
        Path written = write(twoStagePack(false), folder);
        Path renamed = folder.resolve(PackFixtures.UUID + ".converted_1234567890");
        Files.move(written, renamed);

        StoryPack read = new FsStoryPackReader().read(renamed);

        assertEquals(PackFixtures.UUID, read.getUuid(), "the reader trims from the first dot");
    }

    @Test
    @DisplayName("KNOWN GAP: stage node uuids are not stored — the first node gets the pack's, the rest are invented")
    void stageNodeUuidsAreNotStored(@TempDir Path folder) throws Exception {
        StoryPack read = roundTrip(twoStagePack(false), folder);

        // The 44-byte node has no room for a uuid (FORMATS.md §5). The reader assigns the folder
        // name to node 0 and a fresh random uuid to every other node — the `FIXME node uuids from
        // metadata file` in FsStoryPackReader. Two reads of the same folder therefore disagree on
        // every node but the first.
        assertEquals(read.getUuid(), read.getStageNodes().get(0).getUuid());
        assertNotEquals(SECOND, read.getStageNodes().get(1).getUuid());
        StoryPack readAgain = new FsStoryPackReader().read(folder.resolve("56789ABC"));
        assertNotEquals(read.getStageNodes().get(1).getUuid(), readAgain.getStageNodes().get(1).getUuid());
    }

    // ---------------------------------------------------------------- what survives

    @Test
    @DisplayName("keeps the version, the night mode marker and the stage nodes in order")
    void keepsVersionNightModeAndNodes(@TempDir Path folder) throws Exception {
        StoryPack read = roundTrip(twoStagePack(true), folder);

        assertEquals(3, read.getVersion());
        assertTrue(read.isNightModeAvailable(), "nm marker");
        assertEquals(2, read.getStageNodes().size());

        StoryPack noNight = roundTrip(twoStagePack(false), folder.resolve("other"));
        assertFalse(noNight.isNightModeAvailable());
    }

    @Test
    @DisplayName("keeps asset bytes exactly, and hands each node its own")
    void keepsAssetBytesExactly(@TempDir Path folder) throws Exception {
        StoryPack read = roundTrip(twoStagePack(false), folder);

        assertArrayEquals(imageA, read.getStageNodes().get(0).getImage().getRawData(), "image of node 0");
        assertArrayEquals(imageB, read.getStageNodes().get(1).getImage().getRawData(), "image of node 1");
        assertArrayEquals(audioA, read.getStageNodes().get(0).getAudio().getRawData(), "audio of node 0");
        assertArrayEquals(audioB, read.getStageNodes().get(1).getAudio().getRawData(), "audio of node 1");
    }

    @Test
    @DisplayName("keeps the transitions, the shared action node and the control settings")
    void keepsTransitionsAndControls(@TempDir Path folder) throws Exception {
        StoryPack read = roundTrip(twoStagePack(false), folder);

        StageNode first = read.getStageNodes().get(0);
        assertNotNull(first.getOkTransition(), "ok transition");
        assertNotNull(first.getHomeTransition(), "home transition");
        ActionNode action = first.getOkTransition().getActionNode();
        assertNotNull(action);
        assertEquals(1, action.getOptions().size(), "one option, the second node");
        assertEquals(read.getStageNodes().get(1), action.getOptions().get(0), "the option is node 1");
        assertEquals(0, first.getOkTransition().getOptionIndex());

        ControlSettings controls = first.getControlSettings();
        assertTrue(controls.isWheelEnabled());
        assertTrue(controls.isOkEnabled());
        assertFalse(controls.isHomeEnabled());
        assertTrue(controls.isPauseEnabled());
        assertFalse(controls.isAutoJumpEnabled());
    }

    @Test
    @DisplayName("stores an asset used by several nodes once")
    void deduplicatesSharedAssets(@TempDir Path folder) throws Exception {
        StoryPack pack = twoStagePack(false);
        pack.getStageNodes().get(1).setImage(new ImageAsset("image/bmp", imageA, "same.bmp"));
        pack.getStageNodes().get(1).setAudio(new AudioAsset("audio/mp3", audioA, "same.mp3"));

        Path written = write(pack, folder);
        ByteBuffer ni = ByteBuffer.wrap(Files.readAllBytes(written.resolve("ni"))).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1, ni.getInt(16), "images counted once");
        assertEquals(1, ni.getInt(20), "sounds counted once");
        try (Stream<Path> images = Files.list(written.resolve("rf").resolve("000"));
             Stream<Path> sounds = Files.list(written.resolve("sf").resolve("000"))) {
            assertEquals(1, images.count(), "one image file");
            assertEquals(1, sounds.count(), "one sound file");
        }
        StoryPack read = new FsStoryPackReader().read(written);
        assertArrayEquals(imageA, read.getStageNodes().get(1).getImage().getRawData());
    }

    @Test
    @DisplayName("KNOWN GAP: a node without audio comes back with a silent placeholder, not without audio")
    void missingAudioBecomesAPlaceholder(@TempDir Path folder) throws Exception {
        StoryPack pack = twoStagePack(false);
        pack.getStageNodes().get(1).setAudio(null);

        StoryPack read = roundTrip(pack, folder);

        // The device needs a sound on every node, so the writer substitutes a blank MP3. The reader
        // cannot tell it from a real one, so the absence does not round-trip: the node comes back
        // with audio it never had. Harmless on the device; visible in the editor.
        assertNotNull(read.getStageNodes().get(1).getAudio());
        assertTrue(read.getStageNodes().get(1).getAudio().getRawData().length > 0);
        assertFalse(Arrays.equals(audioB, read.getStageNodes().get(1).getAudio().getRawData()));
    }

    // ---------------------------------------------------------------- the bytes on disk

    @Test
    @DisplayName("writes the ni header and node records the device reads (FORMATS.md §5)")
    void writesTheNiLayoutTheDeviceReads(@TempDir Path folder) throws Exception {
        Path written = write(twoStagePack(false), folder);
        byte[] ni = Files.readAllBytes(written.resolve("ni"));
        ByteBuffer header = ByteBuffer.wrap(ni).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(1, header.getShort(0), "node index format version");
        assertEquals(3, header.getShort(2), "story pack version");
        assertEquals(512, header.getInt(4), "offset of the node list");
        assertEquals(44, header.getInt(8), "node size");
        assertEquals(2, header.getInt(12), "stage nodes");
        assertEquals(2, header.getInt(16), "images");
        assertEquals(2, header.getInt(20), "sounds");
        assertEquals(1, ni[24], "factory flag, always set by STUdio");
        assertEquals(512 + 2 * 44, ni.length, "header + one record per node, nothing else");
        for (int i = 25; i < 512; i++) {
            assertEquals(0, ni[i], "header byte " + i + " is reserved");
        }
        // Node 0: image 0, sound 0, ok -> action at li offset 0 with 1 option, selected 0, same for home.
        assertEquals(0, header.getInt(512));
        assertEquals(0, header.getInt(516));
        assertEquals(0, header.getInt(520));
        assertEquals(1, header.getInt(524));
        assertEquals(0, header.getInt(528));
        assertEquals(0, header.getShort(512 + 42), "padding");
    }

    @Test
    @DisplayName("writes 12-byte ri/si entries, li as node indices, and the .cleartext marker (FORMATS.md §4, §6)")
    void writesTheIndexFilesTheDeviceReads(@TempDir Path folder) throws Exception {
        Path written = write(twoStagePack(false), folder);

        byte[] ri = Files.readAllBytes(written.resolve("ri"));
        assertEquals(24, ri.length, "two images, 12 bytes each");
        assertEquals("000\\00000000", new String(ri, 0, 12, StandardCharsets.US_ASCII));
        assertEquals("000\\00000001", new String(ri, 12, 12, StandardCharsets.US_ASCII));
        assertTrue(Files.exists(written.resolve("rf").resolve("000").resolve("00000000")));
        assertTrue(Files.exists(written.resolve("sf").resolve("000").resolve("00000001")));

        ByteBuffer li = ByteBuffer.wrap(Files.readAllBytes(written.resolve("li"))).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(4, li.capacity(), "one option in the whole pack");
        assertEquals(1, li.getInt(0), "the option is stage node 1");

        assertTrue(Files.exists(written.resolve(".cleartext")), "library folders are clear; the driver ciphers on transfer");
        assertFalse(Files.exists(written.resolve("bt")), "bt is the driver's, written per device");
    }
}
