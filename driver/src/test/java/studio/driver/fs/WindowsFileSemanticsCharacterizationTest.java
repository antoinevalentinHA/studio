/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterises the Windows filesystem semantics that the pack index rewrite depends on.
 *
 * <p>On a Lunii, {@code .pi} carries the DOS {@code hidden} attribute. The driver works around that
 * by writing {@code .pi.new} and then {@code Files.copy(..., REPLACE_EXISTING)}. These tests measure
 * what each candidate primitive actually does, so that any future integrity work starts from
 * observed facts rather than from assumptions about Windows.
 *
 * <p><strong>Scope:</strong> these run on the runner's temporary directory, i.e. NTFS. FAT32 — what
 * a Lunii actually uses — is covered separately and is not exercised in CI; see
 * {@link Fat32AtomicMoveCharacterizationTest} and TESTING.md. Nothing here touches a device.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsFileSemanticsCharacterizationTest {

    @TempDir
    Path dir;

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

    private boolean isHidden(Path path) throws IOException {
        return (Boolean) Files.getAttribute(path, "dos:hidden");
    }

    private String contentOf(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- W1

    @Test
    @DisplayName("W1: legacy FileOutputStream cannot open a hidden file — this is why .pi.new exists")
    void legacyFileOutputStreamIsRefusedOnHiddenTarget() throws IOException {
        Path hidden = hiddenFile("w1a");

        FileNotFoundException raised =
                assertThrows(FileNotFoundException.class, () -> new FileOutputStream(hidden.toFile()).close());

        assertTrue(raised.getMessage().contains(hidden.toString()));
    }

    @Test
    @DisplayName("W1: NIO can open the very same hidden file for writing, truncating included")
    void nioCanOpenHiddenTargetForWriting() throws IOException {
        // Measured, and worth recording: the constraint that motivated the temporary-file dance is a
        // limitation of java.io, not of Windows. NIO opens the hidden file directly, whether it
        // truncates (default CREATE + TRUNCATE_EXISTING) or opens for plain WRITE.
        Path defaultOptions = hiddenFile("w1b");
        try (OutputStream out = Files.newOutputStream(defaultOptions)) {
            out.write("NEW".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("NEW", contentOf(defaultOptions));
        assertTrue(isHidden(defaultOptions), "opening in place preserves the attribute");

        Path writeOnly = hiddenFile("w1c");
        assertDoesNotThrow(() -> Files.newOutputStream(writeOnly, StandardOpenOption.WRITE).close());

        Path viaFilesWrite = hiddenFile("w1d");
        assertDoesNotThrow(() -> Files.write(viaFilesWrite, "NEW".getBytes(StandardCharsets.UTF_8)));
        assertTrue(isHidden(viaFilesWrite));
    }

    @Test
    @DisplayName("W1: a hidden file can be deleted, unlike a read-only one")
    void hiddenFileCanBeDeleted() throws IOException {
        Path hidden = hiddenFile("w1e");

        assertDoesNotThrow(() -> Files.delete(hidden));
        assertFalse(Files.exists(hidden));
    }

    // ---------------------------------------------------------------- W2

    @Test
    @DisplayName("W2: Files.copy(REPLACE_EXISTING) succeeds but STRIPS the hidden attribute")
    void copyReplaceExistingLosesHiddenAttribute() throws IOException {
        // This is exactly what FsStoryTellerAsyncDriver.writePackIndex does today. The copy works,
        // the content is correct — and .pi comes out of it no longer hidden, because the attribute
        // is inherited from the (normal) source file rather than kept from the target.
        // Whether the firmware cares is unknown and untestable here; the attribute change is a fact.
        Path source = plainFile("w2src", "NEW");
        Path target = hiddenFile("w2dst");
        assertTrue(isHidden(target), "precondition: the target starts hidden");

        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

        assertEquals("NEW", contentOf(target));
        assertFalse(isHidden(target), "the hidden attribute is lost by the copy");
    }

    // ---------------------------------------------------------------- W3

    @Test
    @DisplayName("W3: ATOMIC_MOVE is supported on the runner's filesystem (control case)")
    void atomicMoveIsSupportedOnPlainTarget() throws IOException {
        Path source = plainFile("w3asrc", "NEW");
        Path target = plainFile("w3adst", "OLD");

        assertDoesNotThrow(() -> Files.move(source, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));

        assertEquals("NEW", contentOf(target));
        assertFalse(Files.exists(source));
    }

    @Test
    @DisplayName("W3: ATOMIC_MOVE onto a hidden target succeeds — but also strips the attribute")
    void atomicMoveOntoHiddenTargetSucceedsAndStripsAttribute() throws IOException {
        // The failure mode that could have blocked an atomic rewrite of .pi does not occur here:
        // no AccessDeniedException, no AtomicMoveNotSupportedException. The attribute behaves the
        // same way as with copy — it follows the source file.
        Path source = plainFile("w3bsrc", "NEW");
        Path target = hiddenFile("w3bdst");

        assertDoesNotThrow(() -> Files.move(source, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));

        assertEquals("NEW", contentOf(target));
        assertFalse(isHidden(target));
    }

    @Test
    @DisplayName("W3: the attribute follows the source, so marking the temp file preserves it")
    void hiddenAttributeIsInheritedFromTheSource() throws IOException {
        // Directly useful for any future integrity work: setting `hidden` on the replacement file
        // *before* the move is enough to keep .pi hidden, with no post-move fix-up step and no
        // window during which .pi exists with the wrong attributes.
        Path source = plainFile("w3esrc", "NEW");
        Files.setAttribute(source, "dos:hidden", true);
        Path target = hiddenFile("w3edst");

        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        assertEquals("NEW", contentOf(target));
        assertTrue(isHidden(target), "the replacement keeps the attribute it was given");
    }

    @Test
    @DisplayName("W3: clearing then restoring the attribute around the move also works")
    void clearingAndRestoringTheAttributeWorks() throws IOException {
        // The alternative mitigation, kept measured rather than assumed. Slightly worse than the
        // one above because it leaves a window where .pi is visible.
        Path source = plainFile("w3dsrc", "NEW");
        Path target = hiddenFile("w3ddst");

        Files.setAttribute(target, "dos:hidden", false);
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.setAttribute(target, "dos:hidden", true);

        assertEquals("NEW", contentOf(target));
        assertTrue(isHidden(target));
    }
}
