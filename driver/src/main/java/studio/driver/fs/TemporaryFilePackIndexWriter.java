/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The index-replacement strategy the driver has always used: write {@code .pi.new}, copy it over
 * {@code .pi}, delete the temporary file.
 *
 * <p>This is a straight extraction of {@code FsStoryTellerAsyncDriver.writePackIndex}. Same
 * primitives, same order, same file names, same failure modes — deliberately, so that the move
 * changes nothing observable. What it does is characterised by {@code WritePathCharacterizationTest}
 * and, on FAT32, by {@code Fat32WritePathCharacterizationTest}, including the parts that are plainly
 * undesirable:
 *
 * <ul>
 *   <li>the copy is <strong>not atomic</strong>: {@code REPLACE_EXISTING} removes the target before
 *       writing it, so there is an instant where {@code .pi} is absent or partial;</li>
 *   <li>a failed replacement <strong>leaves {@code .pi.new} behind</strong>, holding the index that
 *       was meant to be installed; nothing ever cleans it up;</li>
 *   <li>an existing {@code .pi.new} is <strong>overwritten without being looked at</strong>;</li>
 *   <li>the hidden attribute of {@code .pi} is <strong>lost</strong>, on NTFS and on FAT32 alike,
 *       because the attribute is inherited from the copy source;</li>
 *   <li>nothing is flushed to the card before the operation reports success.</li>
 * </ul>
 *
 * <p>The class name states the strategy rather than claiming a property, which leaves room for an
 * implementation that installs the index differently once the invariants have been decided.
 */
class TemporaryFilePackIndexWriter implements PackIndexWriter {

    private static final Logger LOGGER = Logger.getLogger(TemporaryFilePackIndexWriter.class.getName());

    @Override
    public void write(String packIndexFilePath, List<UUID> packUUIDs) throws IOException {
        // Because the hidden file cannot be modified on windows, we need to write to a temporary file
        String newPiFile = packIndexFilePath + ".new";
        LOGGER.finest("Writing pack index to temporary file: " + newPiFile);

        try (
                FileOutputStream packIndexFos = new FileOutputStream(newPiFile);
                DataOutputStream packIndexDos = new DataOutputStream(packIndexFos);
        ) {
            for (UUID packUUID : packUUIDs) {
                packIndexDos.writeLong(packUUID.getMostSignificantBits());
                packIndexDos.writeLong(packUUID.getLeastSignificantBits());
            }
        }

        // Then replace file
        LOGGER.finest("Replacing pack index file");
        Files.copy(Paths.get(newPiFile), Paths.get(packIndexFilePath), StandardCopyOption.REPLACE_EXISTING);
        LOGGER.finest("Deleting temporary pack index file");
        Files.delete(Paths.get(newPiFile));
    }
}
