/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service.mock;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.metadata.DatabaseMetadataService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reordering packs on the mocked device.
 *
 * <p>The mocked device is what dev mode runs against, and it answered {@code false} to every
 * reorder: the UI could drive the whole drag and drop and was then told the operation had failed.
 * Nothing about the browser side could be exercised end to end without a real Lunii plugged in.
 *
 * <p>The mock stores one file per pack and a folder listing has no order, so ordering has to be
 * stored. It is kept in the device folder itself, which is where a real device keeps its own pack
 * index too.
 *
 * <p>These are specifications, and two of them deliberately copy defects of the real driver rather
 * than improve on them. {@code FsStoryTellerAsyncDriver.reorderPacks} accepts a partial list and
 * sorts every pack the caller left out to the front — that is what
 * {@code PackIndexReorderValidationTest} pins at the other end — and it refuses a list naming a pack
 * the device does not hold. A mock that quietly behaved better would hide, in the only mode that can
 * be run without hardware, exactly the surprises the real device has.
 */
class MockDeviceReorderTest {

    @TempDir
    Path root;

    private final Map<String, String> previousProperties = new HashMap<>();

    private MockStoryTellerService service;
    private Path deviceFolder;

    @BeforeEach
    void setUp() throws IOException {
        // The mocked device folder hangs off user.home, so the home is moved into @TempDir for the
        // duration. Nothing in the real ~/.studio is read, written or listed.
        setProperty("user.home", root.toString());
        Files.createDirectories(root.resolve("db"));
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP,
                root.resolve("db").resolve("unofficial.json").toString());
        setProperty(DatabaseMetadataService.OFFICIAL_DB_PROP,
                root.resolve("db").resolve("official.json").toString());

        deviceFolder = root.resolve(".studio").resolve("device");
        // The constructor creates the folder; the event bus is untouched by anything tested here.
        service = new MockStoryTellerService(null, new DatabaseMetadataService(true));
    }

    @AfterEach
    void tearDown() {
        previousProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    @DisplayName("a reorder is accepted and the pack list comes back in the submitted order")
    void reorderIsHonoured() throws Exception {
        String first = writePack("First");
        String second = writePack("Second");
        String third = writePack("Third");

        assertTrue(service.reorderPacks(List.of(third, first, second)).get(),
                "the mocked device should accept a reorder naming exactly the packs it holds");

        assertEquals(List.of(third, first, second), listedUuids());
    }

    @Test
    @DisplayName("the order survives a second listing, so dev mode shows what was dropped")
    void orderIsPersisted() throws Exception {
        String first = writePack("First");
        String second = writePack("Second");

        service.reorderPacks(List.of(second, first)).get();
        listedUuids();

        assertEquals(List.of(second, first), listedUuids());
    }

    @Test
    @DisplayName("a pack left out of the list sorts to the front, as it does on a real device")
    void packsLeftOutSortToTheFront() throws Exception {
        String first = writePack("First");
        String second = writePack("Second");
        String omitted = writePack("Omitted");

        assertTrue(service.reorderPacks(List.of(second, first)).get());

        assertEquals(List.of(omitted, second, first), listedUuids());
    }

    @Test
    @DisplayName("a list naming a pack the device does not hold is refused, and changes nothing")
    void unknownPackIsRefused() throws Exception {
        String first = writePack("First");
        String second = writePack("Second");
        List<String> before = listedUuids();

        assertFalse(service.reorderPacks(List.of(second, first, UUID.randomUUID().toString())).get(),
                "the guard on the real driver refuses a list naming a pack that is not on the device");

        assertEquals(before, listedUuids());
    }

    private List<String> listedUuids() throws Exception {
        JsonArray packs = service.packs().get();
        List<String> uuids = new ArrayList<>();
        for (int i = 0; i < packs.size(); i++) {
            uuids.add(((JsonObject) packs.getValue(i)).getString("uuid"));
        }
        return uuids;
    }

    /**
     * Writes the smallest file {@code BinaryStoryPackReader.readMetadata} will read: sector 1 holds
     * the version and the enriched title, and the first sixteen bytes of sector 2 hold the UUID.
     * Nothing beyond that is read when the device is only being listed.
     */
    private String writePack(String title) throws IOException {
        UUID uuid = UUID.randomUUID();
        ByteBuffer sector1 = ByteBuffer.allocate(512);
        sector1.putShort((short) 1);    // stage node count
        sector1.put((byte) 0);          // factory disabled
        sector1.putShort((short) 1);    // version
        sector1.position(64);           // past the enriched metadata alignment padding
        byte[] titleBytes = title.getBytes(StandardCharsets.UTF_16BE);
        sector1.put(titleBytes);

        ByteBuffer sector2 = ByteBuffer.allocate(512);
        sector2.putLong(uuid.getMostSignificantBits());
        sector2.putLong(uuid.getLeastSignificantBits());

        Files.createDirectories(deviceFolder);
        Path packFile = deviceFolder.resolve(uuid + ".pack");
        try (java.io.OutputStream out = Files.newOutputStream(packFile)) {
            out.write(sector1.array());
            out.write(sector2.array());
        }
        return uuid.toString();
    }

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}
