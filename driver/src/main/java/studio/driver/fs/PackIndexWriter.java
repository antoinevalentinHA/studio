/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Installs a new pack index on a device partition.
 *
 * <p>The driver decides <em>what</em> the index should contain and <em>when</em> to write it —
 * upload appends, delete removes, reorder sorts. This decides <em>how</em> the file gets replaced,
 * which is the part with the integrity properties: whether a temporary file is involved, in what
 * order it is installed, what survives a failure, and what is left behind.
 *
 * <p>The split exists so that those properties can be tested. {@code writePackIndex} used to build
 * its paths inline with {@code Paths.get(String)}, so nothing could be substituted and there was no
 * point of observation between writing the temporary file and finishing the replacement. That gap —
 * W3-bis — is what this interface opens.
 *
 * <p><strong>It does not close it.</strong> The only implementation is
 * {@link TemporaryFilePackIndexWriter}, which does exactly what the driver did before and is no
 * safer for it: no atomic move, no flush to the card, and a temporary file left behind by a failed
 * replacement. Extracting the responsibility is not hardening it.
 */
public interface PackIndexWriter {

    /**
     * Replaces the index at {@code packIndexFilePath} with {@code packUUIDs}.
     *
     * @param packIndexFilePath absolute path of the index file on the device partition. The driver
     *                          owns the partition layout, so it passes the location rather than
     *                          letting the writer work it out.
     * @param packUUIDs         the packs, in the order they must appear on the device — the writer
     *                          neither sorts nor deduplicates them.
     * @throws IOException if the index could not be installed. What state the partition is left in
     *                     depends on the implementation and is characterised, not guaranteed.
     */
    void write(String packIndexFilePath, List<UUID> packUUIDs) throws IOException;
}
