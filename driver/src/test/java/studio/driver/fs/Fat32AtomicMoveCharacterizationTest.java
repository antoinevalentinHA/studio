/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same measurements as {@link WindowsFileSemanticsCharacterizationTest}, but on a FAT32 volume —
 * the filesystem a Lunii actually uses, and the one whose behaviour is still unknown.
 *
 * <p><strong>Opt-in, and deliberately not run in CI.</strong> Creating and mounting a FAT32 volume on
 * a GitHub-hosted runner means driving {@code diskpart} to build and attach a VHD, which is slow,
 * needs elevation, and fails in ways unrelated to this project. Rather than make every pull request
 * depend on that, the test activates only when pointed at an existing FAT32 volume:
 *
 * <pre>
 *   mvn -pl driver test -Dstudio.test.fat32.root=E:\
 * </pre>
 *
 * <p>Use a spare, disposable FAT32-formatted USB stick or a manually created VHD. <strong>Never
 * point this at a Lunii.</strong> The test writes and deletes files under a temporary sub-directory
 * of the given root; it never touches {@code .md}, {@code .pi} or {@code .content}, but a device is
 * still the wrong place to run an experiment.
 *
 * <p>See TESTING.md for the manual VHD procedure and for what remains uncovered.
 */
@EnabledIfSystemProperty(named = "studio.test.fat32.root", matches = ".+",
        disabledReason = "set -Dstudio.test.fat32.root=<drive> to run the FAT32 characterization")
class Fat32AtomicMoveCharacterizationTest {

    private Path dir;

    @BeforeEach
    void createWorkingDirectoryOnTheTargetVolume() throws IOException {
        Path root = Paths.get(System.getProperty("studio.test.fat32.root"));

        String type = Files.getFileStore(root).type().toUpperCase(Locale.ROOT);
        Assumptions.assumeTrue(type.contains("FAT"),
                () -> "studio.test.fat32.root points at a " + type + " volume, not a FAT one");

        // Guard against being aimed at a story teller by mistake.
        Assumptions.assumeFalse(Files.exists(root.resolve(".md")) || Files.exists(root.resolve(".pi")),
                "refusing to run: the target volume looks like a story teller device");

        dir = Files.createTempDirectory(root, "studio-fat32-");
    }

    private Path plainFile(String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private Path hiddenFile(String name) throws IOException {
        Path path = plainFile(name, "OLD");
        Files.setAttribute(path, "dos:hidden", true);
        return path;
    }

    @Test
    @DisplayName("W4: does FAT32 support ATOMIC_MOVE at all?")
    void atomicMoveOnPlainTarget() throws IOException {
        Path source = plainFile("src", "NEW");
        Path target = plainFile("dst", "OLD");

        assertDoesNotThrow(() -> Files.move(source, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));

        assertEquals("NEW", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(source));
    }

    @Test
    @DisplayName("W4: does ATOMIC_MOVE onto a hidden target behave as it does on NTFS?")
    void atomicMoveOntoHiddenTarget() throws IOException {
        Path source = plainFile("src2", "NEW");
        Files.setAttribute(source, "dos:hidden", true);
        Path target = hiddenFile("dst2");

        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        assertEquals("NEW", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertTrue((Boolean) Files.getAttribute(target, "dos:hidden"),
                "as on NTFS, the attribute should follow the source file");
    }

    @Test
    @DisplayName("W4: does Files.copy strip the hidden attribute on FAT32 too?")
    void copyReplaceExistingOnHiddenTarget() throws IOException {
        Path source = plainFile("src3", "NEW");
        Path target = hiddenFile("dst3");

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

        assertFalse((Boolean) Files.getAttribute(target, "dos:hidden"),
                "as on NTFS, the copy should strip the attribute");
    }
}
