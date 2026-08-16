/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transfer must not leave a directory handle open on the source tree when the copy fails.
 *
 * <p>{@code copyPackFolder} walks the source pack with {@code Files.walk} and consumes it with
 * {@code forEach}. The lambda throws when a file cannot be copied, which abandons the stream
 * mid-traversal; the {@code Stream<Path>} owns the open directory handles, and nothing closes them.
 * Same family of defect as the metadata stream leak fixed in C4, on a {@code Stream<Path>} rather
 * than an {@code InputStream}.
 *
 * <h2>Why this class is FAT32-only</h2>
 *
 * <p>The leak exists on every platform, but it is only <em>observable</em> on FAT32. Java's
 * directory streams open their handles with {@code FILE_SHARE_DELETE}, so on Windows
 * {@code RemoveDirectory} still succeeds on the abandoned directory — it does not throw — and the
 * directory enters the delete-pending state. On NTFS the name is unlinked from the parent index
 * straight away and the residue is invisible. On FAT32 the directory entry <em>is</em> the object's
 * identity, so it cannot be unlinked while a handle survives: the entry stays enumerable in the
 * parent, and removing the parent fails with {@code DirectoryNotEmptyException}. The leftover
 * reports {@code exists() == false} while still being listed, which is the signature of that state.
 *
 * <p>This was measured, not assumed. The same scenario run against a temporary directory on NTFS
 * cleans up completely, and so does the control case below where the copy succeeds and the walk
 * closes its own directories. FAT32 is also what a device actually uses, and it is where the
 * residue was first noticed, during the C6c failure scenarios.
 *
 * <h2>Why the failure is injected on a download</h2>
 *
 * <p>The leak lives in {@code copyPackFolder}, which serves both directions. It used to be reached
 * here through {@code uploadPack}, by planting a conflicting {@code ni} in the destination
 * {@code .content} folder: clear-text files are copied with {@code Files.copy} and no
 * {@code REPLACE_EXISTING}, so the copy threw while the walk of the source was still in progress.
 *
 * <p>C6d-5 made that injection unreachable, and deliberately so. {@code uploadPack} now claims
 * {@code .content/<folder>} with an exclusive {@code Files.createDirectory} and refuses outright if
 * it already exists, which is exactly the state this fixture used to build. The refusal happens
 * before the device metadata is read and before {@code copyPackFolder} is ever called, so the walk
 * no longer runs at all. The assertions would still have passed — the exception is still a
 * {@code FileAlreadyExistsException}, and a source tree that was never opened is trivially
 * removable — while covering nothing. That is a silent loss of coverage, and CI cannot catch it
 * because this class only runs against a FAT32 volume.
 *
 * <p>The injection therefore moved to {@code downloadPack}, which is the other caller of
 * {@code copyPackFolder} and whose destination is still created with {@code mkdirs()}: C6d-5 changed
 * nothing there, because a download writes into the user's own library rather than onto the card,
 * and the ownership question is not the same. The mechanism is identical — a conflicting clear-text
 * file in the destination, {@code Files.copy} without {@code REPLACE_EXISTING}, the walk abandoned
 * part-way — and so is the invariant these tests assert: <strong>the tree being walked must stay
 * fully removable once the failed copy has returned</strong>. What changed is which tree plays that
 * role. On a download it is the device's own {@code .content} folder, which is if anything the more
 * interesting of the two: it is the one that actually sits on FAT32 on a real device.
 *
 * <p>The upload direction keeps its control case below, where the walk runs to completion.
 * <strong>There is no longer any way to make an upload fail mid-walk from the outside</strong>,
 * which is a consequence of C6d-5 rather than a gap in this class: the only reachable upload failure
 * is now the refusal, and it happens before the walk starts.
 *
 * <p><strong>Opt-in, with the same two guards as the other FAT32 classes.</strong>
 *
 * <pre>
 *   mvn -pl driver test -Dstudio.test.fat32.root=W:/
 * </pre>
 *
 * <p>Use a forward slash: {@code W:} alone means "the current directory on drive W" in Java, not its
 * root. CI has no FAT32 volume, so these tests skip there — the regression they guard is therefore
 * not caught by CI. <strong>Never point this at a device.</strong>
 *
 * <p>Unlike the C6b and C6c classes these are <em>specification</em> tests: they state what the
 * driver must do, not what it happens to do.
 */
@EnabledIfSystemProperty(named = "studio.test.fat32.root", matches = ".+",
        disabledReason = "set -Dstudio.test.fat32.root=<drive> to run the FAT32 handle-leak tests")
class FilesWalkResourceLeakTest {

    private static final UUID PACK = UUID.fromString("66666666-7777-8888-9999-aaaaccccdddd");

    /** A fresh directory on the FAT32 volume, standing in for the device partition. */
    private Path partition;
    /** Source packs, on the same volume so that nothing crosses filesystems mid-test. */
    private Path library;

    @BeforeEach
    void createWorkingDirectoriesOnTheFat32Volume() throws IOException {
        Path volumeRoot = Paths.get(System.getProperty("studio.test.fat32.root"));

        String type = Files.getFileStore(volumeRoot).type().toUpperCase(Locale.ROOT);
        Assumptions.assumeTrue(type.contains("FAT"),
                () -> "studio.test.fat32.root points at a " + type + " volume, not a FAT one");

        Assumptions.assumeFalse(
                Files.exists(volumeRoot.resolve(".md")) || Files.exists(volumeRoot.resolve(".pi")),
                "refusing to run: the target volume looks like a story teller device");

        partition = Files.createTempDirectory(volumeRoot, "walk-part-");
        library = Files.createTempDirectory(volumeRoot, "walk-lib-");
        writeDeviceMetadataV7();
    }

    @AfterEach
    void removeWorkingDirectories() {
        for (Path root : new Path[]{partition, library}) {
            if (root != null) {
                deleteTree(root);
            }
        }
    }

    // ------------------------------------------------------------------ fixtures

    private void writeDeviceMetadataV7() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(7);
        out.write(0);
        out.write('3');
        out.write('.');
        out.write('3');
        out.write(new byte[21], 0, 21);
        byte[] serial = new byte[24];
        System.arraycopy("SN0123456789ABCDEF012345".getBytes(StandardCharsets.US_ASCII), 0, serial, 0, 24);
        out.write(serial, 0, 24);
        out.write(new byte[14], 0, 14);
        byte[] keyMaterial = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyMaterial[i] = (byte) (0xC0 + i);
        }
        out.write(keyMaterial, 0, 32);
        Files.write(partition.resolve(".md"), out.toByteArray());
        Files.write(partition.resolve(".pi"), new byte[0]);
    }

    /** A clear-text source pack, the shape {@code copyPackFolder} expects to cipher on the way in. */
    private Path sourcePack(String name) throws IOException {
        Path pack = library.resolve(name);
        Files.createDirectories(pack);
        Files.write(pack.resolve(".cleartext"), new byte[0]);
        Files.write(pack.resolve("ni"), filler(200, 'n'));
        Files.write(pack.resolve("ri"), filler(100, 'r'));
        Files.write(pack.resolve("si"), filler(50, 's'));
        Files.write(pack.resolve("li"), filler(30, 'l'));
        return pack;
    }

    private static byte[] filler(int length, char seed) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) seed);
        return data;
    }

    /**
     * Puts a real pack on the device, so that a download has something to walk.
     *
     * <p>This goes through {@code uploadPack} rather than writing the folder by hand: the walked tree
     * has to be exactly what a transfer produces, ciphered assets and generated {@code bt} included.
     * The destination does not exist yet, so C6d-5's exclusive claim succeeds and the upload runs
     * nominally.
     *
     * @return the device's content folder for {@link #PACK} — the tree the download will walk
     */
    private Path putPackOnTheDevice(FsStoryTellerAsyncDriver driver) throws IOException {
        driver.uploadPack(PACK.toString(), sourcePack("pack-to-download").toString(), null).join();
        return partition.resolve(".content").resolve(driver.computePackFolderName(PACK.toString()));
    }

    /**
     * Makes the next download fail part-way through the walk of the device folder: clear-text files
     * are copied with {@code Files.copy} and no {@code REPLACE_EXISTING}, so a conflicting {@code ni}
     * already in the destination throws while the walk is still in progress.
     *
     * <p>{@code downloadPack} writes into {@code <outputPath>/<uuid>}, and creates it with
     * {@code mkdirs()} — untouched by C6d-5, which only claims the device-side {@code .content}
     * folder. Seeding that destination is what this does.
     *
     * @return the output directory to hand to {@code downloadPack}
     */
    private Path planDownloadFailure(String outputName) throws IOException {
        Path output = library.resolve(outputName);
        Path destination = output.resolve(PACK.toString());
        Files.createDirectories(destination);
        Files.write(destination.resolve("ni"), filler(7, 'X'));
        return output;
    }

    // ------------------------------------------------------------------ observation

    /**
     * How many regular files a tree holds, or {@code 0} if it does not exist.
     *
     * <p>Used to prove the walk was actually under way when it failed. A test that asserts "nothing
     * was left behind" passes trivially when nothing was ever opened, which is precisely the trap
     * C6d-5 set for the previous upload-based fixture.
     */
    private static long countRegularFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    /**
     * Deletes a tree deepest-first and reports what could not be removed.
     *
     * <p>The walk is collected before anything is deleted, so this helper never enumerates a
     * directory it is also modifying — otherwise it would be exercising its own bug rather than the
     * driver's. A leftover is reported by name: on FAT32 a delete-pending directory is still listed
     * by its parent even though {@code deleteIfExists} returned without error.
     */
    private static List<String> deleteTree(Path root) {
        List<String> leftovers = new ArrayList<>();
        List<Path> deepestFirst;
        try (Stream<Path> paths = Files.walk(root)) {
            deepestFirst = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        } catch (IOException e) {
            return List.of(root + " could not even be walked: " + e);
        }
        for (Path path : deepestFirst) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                leftovers.add(root.relativize(path) + " -> " + e.getClass().getSimpleName());
            }
        }
        if (Files.exists(root)) {
            try (Stream<Path> remaining = Files.list(root)) {
                remaining.map(p -> root.relativize(p) + " (still listed by its parent)")
                        .forEach(leftovers::add);
            } catch (IOException e) {
                leftovers.add(root + " still exists and cannot be listed: " + e);
            }
        }
        return leftovers;
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the leak

    @Test
    @DisplayName("a copy interrupted mid-walk releases its handles on the walked tree")
    void anInterruptedCopyReleasesTheWalkedTree() throws Exception {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
        Path walkedTree = putPackOnTheDevice(driver);
        Path output = planDownloadFailure("download-out");

        CompletionException raised = null;
        try {
            driver.downloadPack(PACK.toString(), output.toString(), null).join();
        } catch (CompletionException e) {
            raised = e;
        }

        // The business failure is unchanged: this is about the handle, not about the error.
        assertTrue(raised != null && hasCause(raised, FileAlreadyExistsException.class),
                "the copy should still fail the way it always did, got: " + raised);
        // And it must have failed DURING the walk, not before it: if the traversal never started
        // there is no abandoned stream and the assertion below would hold vacuously. Files the walk
        // did copy before hitting the conflicting `ni` are the proof that it was under way.
        assertTrue(Files.exists(walkedTree), "precondition: the device folder is what was walked");
        assertTrue(countRegularFiles(output.resolve(PACK.toString())) > 1,
                "the walk must have copied something before it failed, otherwise this test is vacuous;"
                        + " destination holds " + countRegularFiles(output.resolve(PACK.toString())) + " file(s)");

        // The subject of the test. Before the fix the abandoned walk left the walked folder
        // delete-pending, so the tree it lives in could not be removed.
        assertEquals(List.of(), deleteTree(partition),
                "the walked tree must be fully removable once the failed copy has returned");
    }

    @Test
    @DisplayName("repeated failed downloads do not accumulate handles on the walked tree")
    void repeatedFailuresDoNotAccumulateHandles() throws Exception {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
        putPackOnTheDevice(driver);

        for (int i = 0; i < 5; i++) {
            Path output = planDownloadFailure("download-out-" + i);
            try {
                driver.downloadPack(PACK.toString(), output.toString(), null).join();
            } catch (CompletionException expected) {
                // Every attempt fails on the same conflicting file.
            }
        }

        assertEquals(List.of(), deleteTree(partition),
                "no failed attempt may leave a handle behind on the tree it walked");
    }

    // ------------------------------------------------------------------ controls

    @Test
    @DisplayName("a successful copy also leaves the source tree removable")
    void aSuccessfulCopyReleasesTheSourceTree() throws Exception {
        // Control case, and the reason the leak is attributable to the abandoned walk and to nothing
        // else in the upload path: a walk consumed to completion closes its directories as it goes,
        // so this passed even before the fix.
        Path source = sourcePack("source-pack");
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);

        driver.uploadPack(PACK.toString(), source.toString(), null).join();

        assertEquals(List.of(), deleteTree(library),
                "the source tree must be removable after a successful copy");
    }

    @Test
    @DisplayName("measuring a folder's size leaves it removable")
    void measuringFolderSizeReleasesTheTree() throws Exception {
        // FileUtils.getFolderSize walks the tree too, but sum() consumes the stream to completion, so
        // no leak is observable here and this test was green before the fix as well. The closing
        // added to that method is a correctness fix by inspection — it protects the day the traversal
        // starts throwing, for instance on an unreadable directory. This test pins the nominal
        // property so the method cannot regress the other way.
        Path source = sourcePack("source-pack");

        long size = FileUtils.getFolderSize(source.toString());

        assertTrue(size > 0, "size = " + size);
        assertEquals(List.of(), deleteTree(library),
                "the measured folder must remain removable");
    }
}
