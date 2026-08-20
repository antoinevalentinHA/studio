/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a pack artefact contains, reduced to a digest that can be compared later.
 *
 * <p>This exists so that "is this converted pack the one that was produced from this source" can be
 * answered by comparing content rather than by comparing names, UUIDs or modification times. None of
 * those three is an identity: a name is a label, a UUID is shared by every version of a pack, and a
 * modification time travels with the bytes when a file is copied. Only the bytes identify the bytes.
 *
 * <p><strong>Stability.</strong> A digest offered as proof must not have been taken from an artefact
 * that was being written at the time. Every result here is therefore bracketed: the observable state
 * is recorded, the content is read, the state is recorded again, and the digest is returned only if
 * the two observations agree. They agree on size and last-modified time — which is not proof that
 * nothing happened, and is not claimed to be. It catches an artefact visibly in motion; a change
 * that leaves both attributes identical, or a change followed by an exact revert, is invisible to
 * it. The identity itself rests on SHA-256; this bracket only refuses to hash a torn state.
 *
 * <p>An unreadable artefact, an unstable one, or a tree containing something that is neither a plain
 * file nor a plain directory all yield an empty result. Empty means "no proof could be established",
 * never "the artefacts differ" — the caller must not turn one into the other.
 */
class ContentDigest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentDigest.class);

    /**
     * Domain separator, so a tree digest can never coincide with the digest of a file that happens
     * to hold the same bytes, and so a later change to the canonical form is a different prefix
     * rather than a silent reinterpretation of old records.
     */
    static final String TREE_PREFIX = "studio-tree-v1\n";

    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * The digest of a regular file's bytes, or empty if it could not be read over a stable window.
     */
    Optional<String> ofFile(Path file) {
        try {
            BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) {
                LOGGER.debug("Not a regular file, no digest: " + file);
                return Optional.empty();
            }
            MessageDigest digest = newDigest();
            updateWithContent(digest, file);
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!stable(before, after)) {
                LOGGER.warn("File changed while being read, no digest: " + file);
                return Optional.empty();
            }
            return Optional.of(hex(digest.digest()));
        } catch (IOException e) {
            LOGGER.warn("Failed to read file for digest: " + file, e);
            return Optional.empty();
        }
    }

    /**
     * The digest of a directory tree, canonical across platforms, or empty if it could not be read
     * over a stable window or holds an entry this refuses to interpret.
     *
     * <p>The canonical form is: the prefix above, then every regular file in the tree ordered by the
     * UTF-8 bytes of its path relative to the root, each contributing its relative path in UTF-8, a
     * {@code 0x00} separator, its length as eight big-endian bytes, and its content. Path separators
     * are always {@code /}, so the same tree hashes identically on Windows and on Linux. Empty
     * directories, timestamps and permissions contribute nothing: none of them is content, and
     * permissions in particular do not survive a copy between platforms.
     *
     * <p>Every regular file counts, with no exclusion list. Dropping a stray file into a pack folder
     * therefore changes its digest — which is correct, since it is no longer the same directory, and
     * which avoids introducing a second heuristic about what "really" belongs to a pack.
     */
    Optional<String> ofTree(Path root) {
        Optional<List<TreeEntry>> before = scan(root);
        if (!before.isPresent()) {
            return Optional.empty();
        }
        try {
            MessageDigest digest = newDigest();
            digest.update(TREE_PREFIX.getBytes(StandardCharsets.UTF_8));
            for (TreeEntry entry : before.get()) {
                digest.update(entry.relativePath.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(entry.size).array());
                updateWithContent(digest, root.resolve(entry.systemRelativePath));
            }
            Optional<List<TreeEntry>> after = scan(root);
            if (!after.isPresent() || !after.get().equals(before.get())) {
                LOGGER.warn("Tree changed while being read, no digest: " + root);
                return Optional.empty();
            }
            return Optional.of(hex(digest.digest()));
        } catch (IOException e) {
            LOGGER.warn("Failed to read tree for digest: " + root, e);
            return Optional.empty();
        }
    }

    /**
     * Streams a file's content into a digest.
     *
     * <p>Package-private and overridable so a test can make an artefact change mid-read and observe
     * that the stability bracket refuses the result. Following the seam already used by the driver's
     * index writer rather than reaching for reflection.
     */
    void updateWithContent(MessageDigest digest, Path file) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    /**
     * The manifest of a tree: every regular file, ordered canonically, with the attributes the
     * stability check compares. Empty if the root is not a plain directory, if any entry is neither
     * a plain file nor a plain directory, or if the tree could not be listed.
     *
     * <p>Deliberately a hand-written recursion rather than {@code Files.walk}: a walk descends into
     * an NTFS junction even without following links — measured — which would count a subtree twice
     * and could loop. Here every entry is examined before it is entered.
     */
    private Optional<List<TreeEntry>> scan(Path root) {
        try {
            BasicFileAttributes rootAttributes = Files.readAttributes(root, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!isPlainDirectory(rootAttributes)) {
                LOGGER.debug("Not a plain directory, no digest: " + root);
                return Optional.empty();
            }
            List<TreeEntry> entries = new ArrayList<>();
            if (!collect(root, root, entries)) {
                return Optional.empty();
            }
            entries.sort((a, b) -> Arrays.compareUnsigned(a.pathBytes, b.pathBytes));
            return Optional.of(entries);
        } catch (IOException e) {
            LOGGER.warn("Failed to list tree for digest: " + root, e);
            return Optional.empty();
        }
    }

    private boolean collect(Path root, Path directory, List<TreeEntry> entries) throws IOException {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    // A symbolic link, an NTFS junction or any other reparse point, a socket, a
                    // device. Recording where it points would make the identity depend on something
                    // outside the tree, and following it could leave the tree or loop. Refusing is
                    // the only answer that stays honest.
                    LOGGER.warn("Refusing to digest a tree containing a link or special entry: " + child);
                    return false;
                }
                if (attributes.isDirectory()) {
                    if (!collect(root, child, entries)) {
                        return false;
                    }
                } else if (attributes.isRegularFile()) {
                    entries.add(new TreeEntry(root, child, attributes));
                } else {
                    LOGGER.warn("Refusing to digest a tree containing an unrecognised entry: " + child);
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isPlainDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
    }

    private static boolean stable(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Required of every Java platform.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /** One regular file of a tree, as the canonical form and the stability check see it. */
    private static final class TreeEntry {
        private final String relativePath;
        private final Path systemRelativePath;
        private final byte[] pathBytes;
        private final long size;
        private final FileTime lastModifiedTime;

        private TreeEntry(Path root, Path file, BasicFileAttributes attributes) {
            this.systemRelativePath = root.relativize(file);
            StringBuilder canonical = new StringBuilder();
            for (Path component : systemRelativePath) {
                if (canonical.length() > 0) {
                    canonical.append('/');
                }
                canonical.append(component.toString());
            }
            this.relativePath = canonical.toString();
            this.pathBytes = relativePath.getBytes(StandardCharsets.UTF_8);
            this.size = attributes.size();
            this.lastModifiedTime = attributes.lastModifiedTime();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TreeEntry)) {
                return false;
            }
            TreeEntry that = (TreeEntry) other;
            return size == that.size
                    && relativePath.equals(that.relativePath)
                    && lastModifiedTime.equals(that.lastModifiedTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(relativePath, size, lastModifiedTime);
        }
    }
}
