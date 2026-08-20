/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What identifies a pack artefact, and what deliberately does not.
 *
 * <p>These are specifications. They fix the canonical form of a tree digest, because that form is
 * about to be written into a file that later versions will read: a change to it is a change to every
 * record ever filed, and a test is the only thing that will notice.
 *
 * <p>The negative cases carry as much weight as the positive ones. A timestamp must not change a
 * digest, or copying a library would invalidate every proof in it. A path separator must not change
 * one, or a pack would hash differently on Windows and on Linux. And an artefact caught in motion
 * must yield no digest at all, because a proof taken from a torn read is worse than no proof.
 */
class ContentDigestTest {

    private final ContentDigest digest = new ContentDigest();

    @TempDir
    Path root;

    @Nested
    @DisplayName("a single file")
    class Files_ {

        @Test
        @DisplayName("the same bytes give the same digest, and it is 64 lowercase hex characters")
        void sameBytesSameDigest() throws IOException {
            Path first = write(root.resolve("a.bin"), "identical");
            Path second = write(root.resolve("b.bin"), "identical");

            String one = require(digest.ofFile(first));
            assertEquals(one, require(digest.ofFile(second)));
            assertTrue(one.matches("[0-9a-f]{64}"), "expected 64 lowercase hex characters, got " + one);
        }

        @Test
        @DisplayName("one byte of difference gives a different digest")
        void oneByteChangesTheDigest() throws IOException {
            Path first = write(root.resolve("a.bin"), "content");
            Path second = write(root.resolve("b.bin"), "contenu");

            assertNotEquals(require(digest.ofFile(first)), require(digest.ofFile(second)));
        }

        @Test
        @DisplayName("a modification time is not content, so changing it changes nothing")
        void timestampDoesNotChangeTheDigest() throws IOException {
            Path file = write(root.resolve("a.bin"), "content");
            String before = require(digest.ofFile(file));

            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_000_000L));

            assertEquals(before, require(digest.ofFile(file)));
        }

        @Test
        @DisplayName("a file changed while it is being read yields no digest")
        void unstableFileYieldsNothing() throws IOException {
            Path file = write(root.resolve("a.bin"), "content");

            // The seam exists for this: mutate the artefact between the two observations, without
            // timing, without a race, and without touching production code.
            ContentDigest mutating = new ContentDigest() {
                @Override
                void updateWithContent(MessageDigest target, Path path) throws IOException {
                    super.updateWithContent(target, path);
                    Files.write(path, "changed underneath".getBytes(StandardCharsets.UTF_8));
                }
            };

            assertFalse(mutating.ofFile(file).isPresent(), "a torn read must not produce a proof");
        }

        @Test
        @DisplayName("a missing file, or a directory, yields no digest")
        void nonFileYieldsNothing() throws IOException {
            Files.createDirectory(root.resolve("folder"));

            assertFalse(digest.ofFile(root.resolve("missing.bin")).isPresent());
            assertFalse(digest.ofFile(root.resolve("folder")).isPresent());
        }
    }

    @Nested
    @DisplayName("a directory tree")
    class Trees {

        @Test
        @DisplayName("the order files were created in does not affect the digest")
        void creationOrderIsIrrelevant() throws IOException {
            Path first = Files.createDirectory(root.resolve("first"));
            write(first.resolve("a.txt"), "A");
            write(first.resolve("b.txt"), "B");
            write(first.resolve("c.txt"), "C");

            Path second = Files.createDirectory(root.resolve("second"));
            write(second.resolve("c.txt"), "C");
            write(second.resolve("a.txt"), "A");
            write(second.resolve("b.txt"), "B");

            assertEquals(require(digest.ofTree(first)), require(digest.ofTree(second)));
        }

        @Test
        @DisplayName("different content gives a different digest")
        void contentChangesTheDigest() throws IOException {
            Path first = Files.createDirectory(root.resolve("first"));
            write(first.resolve("a.txt"), "A");
            Path second = Files.createDirectory(root.resolve("second"));
            write(second.resolve("a.txt"), "different");

            assertNotEquals(require(digest.ofTree(first)), require(digest.ofTree(second)));
        }

        @Test
        @DisplayName("the same bytes under a different name give a different digest")
        void pathsArePartOfTheIdentity() throws IOException {
            Path first = Files.createDirectory(root.resolve("first"));
            write(first.resolve("a.txt"), "same bytes");
            Path second = Files.createDirectory(root.resolve("second"));
            write(second.resolve("b.txt"), "same bytes");

            assertNotEquals(require(digest.ofTree(first)), require(digest.ofTree(second)));
        }

        @Test
        @DisplayName("the canonical form is exactly the specified one, with '/' whatever the platform")
        void canonicalFormIsPinned() throws Exception {
            // The pack format nests assets under rf/ and sf/, so this is the shape that matters. The
            // expectation is rebuilt here from the specification rather than copied from a previous
            // run: joining with File.separator instead of '/' fails this on Windows, and any other
            // change to the canonical form fails it everywhere — which is the point, since the form
            // is about to be written into records that later versions will read.
            Path tree = Files.createDirectory(root.resolve("pack"));
            write(tree.resolve("ni"), "index");
            Files.createDirectory(tree.resolve("rf"));
            write(tree.resolve("rf").resolve("000"), "image");

            MessageDigest expected = MessageDigest.getInstance("SHA-256");
            expected.update(ContentDigest.TREE_PREFIX.getBytes(StandardCharsets.UTF_8));
            // Ordered by the UTF-8 bytes of the relative path: "ni" (0x6e...) then "rf/000" (0x72...)
            feed(expected, "ni", "index");
            feed(expected, "rf/000", "image");

            assertEquals(hex(expected.digest()), require(digest.ofTree(tree)));
        }

        @Test
        @DisplayName("timestamps are not content here either")
        void timestampsDoNotChangeTheDigest() throws IOException {
            Path tree = Files.createDirectory(root.resolve("pack"));
            Path file = write(tree.resolve("a.txt"), "A");
            String before = require(digest.ofTree(tree));

            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_000_000L));

            assertEquals(before, require(digest.ofTree(tree)));
        }

        @Test
        @DisplayName("an empty directory contributes nothing")
        void emptyDirectoriesAreIgnored() throws IOException {
            Path first = Files.createDirectory(root.resolve("first"));
            write(first.resolve("a.txt"), "A");
            Path second = Files.createDirectory(root.resolve("second"));
            write(second.resolve("a.txt"), "A");
            Files.createDirectory(second.resolve("empty"));

            assertEquals(require(digest.ofTree(first)), require(digest.ofTree(second)));
        }

        @Test
        @DisplayName("one extra file changes the digest, with no exclusion list")
        void anyExtraFileChangesTheDigest() throws IOException {
            Path first = Files.createDirectory(root.resolve("first"));
            write(first.resolve("a.txt"), "A");
            Path second = Files.createDirectory(root.resolve("second"));
            write(second.resolve("a.txt"), "A");
            // The sort of file an operating system leaves lying around. It counts: refusing to
            // define what "really" belongs to a pack avoids a second heuristic.
            write(second.resolve("Thumbs.db"), "junk");

            assertNotEquals(require(digest.ofTree(first)), require(digest.ofTree(second)));
        }

        @Test
        @DisplayName("a tree changed while it is being read yields no digest")
        void unstableTreeYieldsNothing() throws IOException {
            Path tree = Files.createDirectory(root.resolve("pack"));
            write(tree.resolve("a.txt"), "A");

            ContentDigest mutating = new ContentDigest() {
                @Override
                void updateWithContent(MessageDigest target, Path path) throws IOException {
                    super.updateWithContent(target, path);
                    Files.write(path, "changed underneath".getBytes(StandardCharsets.UTF_8));
                }
            };

            assertFalse(mutating.ofTree(tree).isPresent(), "a torn read must not produce a proof");
        }

        @Test
        @DisplayName("a file, or something missing, is not a tree")
        void nonTreeYieldsNothing() throws IOException {
            write(root.resolve("a.txt"), "A");

            assertFalse(digest.ofTree(root.resolve("a.txt")).isPresent());
            assertFalse(digest.ofTree(root.resolve("missing")).isPresent());
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("an NTFS junction makes the whole tree unprovable")
        void junctionIsRefused() throws Exception {
            // The case that made isOther() necessary. A junction reports isSymbolicLink() == false,
            // measured on this platform, so a check written against links alone would walk straight
            // into it — Files.walk does, even without following links, which is why the scan here is
            // a hand-written recursion. Creating one needs no privilege, unlike a symbolic link.
            Path tree = Files.createDirectory(root.resolve("pack"));
            write(tree.resolve("a.txt"), "A");
            Path target = Files.createDirectory(root.resolve("target"));
            write(target.resolve("inside.txt"), "elsewhere");

            Path junction = tree.resolve("link");
            Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    junction.toString(), target.toString()).redirectErrorStream(true).start();
            assumeTrue(mklink.waitFor() == 0, "could not create a junction on this machine");

            try {
                assertFalse(digest.ofTree(tree).isPresent(),
                        "a junction is not a plain directory and must make the tree unprovable");
            } finally {
                // Removed explicitly: the temporary directory's cleanup does not recognise a
                // junction as a link either, and would delete the target's content through it.
                Files.delete(junction);
            }
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("a symbolic link makes the whole tree unprovable")
        void symbolicLinkIsRefused() throws IOException {
            Path tree = Files.createDirectory(root.resolve("pack"));
            write(tree.resolve("a.txt"), "A");
            Path target = write(root.resolve("outside.txt"), "outside");
            Files.createSymbolicLink(tree.resolve("link.txt"), target);

            assertFalse(digest.ofTree(tree).isPresent(),
                    "following a link would make the identity depend on something outside the tree");
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Path write(Path path, String content) throws IOException {
        return Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String require(Optional<String> digest) {
        assertTrue(digest.isPresent(), "expected a digest");
        return digest.get();
    }

    /** One entry of the canonical form: path in UTF-8, a NUL, the length big-endian, the content. */
    private static void feed(MessageDigest target, String canonicalPath, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        target.update(canonicalPath.getBytes(StandardCharsets.UTF_8));
        target.update((byte) 0);
        target.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        target.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
