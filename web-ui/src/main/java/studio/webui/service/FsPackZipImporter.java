/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static studio.core.v1.reader.fs.FsStoryPackReader.IMAGE_INDEX_FILENAME;
import static studio.core.v1.reader.fs.FsStoryPackReader.NODE_INDEX_FILENAME;
import static studio.core.v1.reader.fs.FsStoryPackReader.SOUND_INDEX_FILENAME;

/**
 * Recognises a zip that holds a pack in the device's own layout, and unpacks it.
 *
 * <p>The layout is the one {@code FsStoryPackReader} reads from a folder: {@code ni}, {@code ri},
 * {@code si} and the asset folders beside them. In a zip those files sit either at the root or
 * under a single top-level folder — the device names its pack folders by UUID, and a zip made from
 * one keeps that folder.
 */
final class FsPackZipImporter {

    private FsPackZipImporter() {
    }

    /**
     * Where the pack files sit in the zip: {@code ""} for the root, {@code "<folder>/"} for a single
     * top-level folder, or empty when the zip is not a pack folder at all — including when it is not
     * a zip, or holds a {@code story.json}, which is an archive and someone else's business.
     */
    static Optional<String> packPrefix(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            if (file.getEntry("story.json") != null) {
                return Optional.empty();
            }
            Set<String> topLevelFolders = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                int slash = name.indexOf('/');
                if (slash >= 0) {
                    topLevelFolders.add(name.substring(0, slash + 1));
                }
            }
            if (isPackFolder(file, "")) {
                return Optional.of("");
            }
            if (topLevelFolders.size() == 1) {
                String prefix = topLevelFolders.iterator().next();
                if (isPackFolder(file, prefix)) {
                    return Optional.of(prefix);
                }
            }
            return Optional.empty();
        } catch (ZipException notAZip) {
            return Optional.empty();
        }
    }

    private static boolean isPackFolder(ZipFile file, String prefix) {
        return file.getEntry(prefix + NODE_INDEX_FILENAME) != null
                && file.getEntry(prefix + IMAGE_INDEX_FILENAME) != null
                && file.getEntry(prefix + SOUND_INDEX_FILENAME) != null;
    }

    /**
     * Unpacks the entries under {@code prefix} into {@code target}, which must already exist.
     *
     * <p>Every entry is checked to land inside {@code target} before anything is written for it —
     * but an entry that climbs out is found part-way through the zip, after earlier entries have
     * been written, so the caller unpacks into a folder it can throw away.
     *
     * @throws IOException when an entry would land outside {@code target}, or on any I/O failure
     */
    static void unpack(Path zip, String prefix, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(prefix)) {
                    continue;
                }
                String relative = entry.getName().substring(prefix.length());
                if (relative.isEmpty()) {
                    continue;
                }
                Path destination = root.resolve(relative).normalize();
                if (!destination.startsWith(root)) {
                    throw new IOException("Zip entry would land outside the pack folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream in = file.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
