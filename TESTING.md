# Testing

## Running the tests

```
mvn -Dskip.installnodeyarn=true -Dskip.yarn=true test
```

The two `skip` properties bypass the frontend goals, which download Node 12 and run `yarn install`.
There is no JavaScript test suite, so skipping them costs no coverage while saving roughly two and a
half minutes per run and removing a dependency on the npm registry. Every Java module, `web-ui`
included, is still compiled. Dropping the properties runs the full build, frontend bundle included.

Both are wired into CI (`.github/workflows/ci.yml`), which runs on every pull request and on pushes
to `master`, on Linux and on Windows. `.github/workflows/maven.yml` is unchanged and keeps doing what
it did: packaging and publishing a nightly artifact from `master`.

## What these tests are, and what they are not

They are **characterization tests**. They pin what the code does today so that a later change is
visible, and they were written before any behaviour was modified. Several of them assert things that
are wrong — a truncated index producing a fabricated pack, a truncated `.md` reporting firmware
`0.0`. Those are marked `KNOWN GAP` in their display name and explained in a comment.

A `KNOWN GAP` test passing does not mean the behaviour is acceptable. It means the behaviour is
known, and that the day someone fixes it the test will fail and force a deliberate decision. When the
integrity work starts, these are the tests to convert from "this is what happens" to "this is what
must happen".

Nothing here touches a device. Fixtures are synthesised in code; no device data is committed.

## Coverage map

| Area | Test class | Notes |
| --- | --- | --- |
| Endianness helpers used for the V3 AES key | `BytesUtilsCharacterizationTest` | core |
| UUID → `.content` folder name | `PackFolderNamingCharacterizationTest` | includes the driver/core duplication cross-check |
| V2 and V3 transfer ciphering, file selection | `CipherUtilsCharacterizationTest` | |
| `.md` parsing, version dispatch, key derivation | `DeviceMetadataCharacterizationTest` | |
| `.pi` parsing | `PackIndexCharacterizationTest` | |
| Windows DOS attributes, `ATOMIC_MOVE` | `WindowsFileSemanticsCharacterizationTest` | Windows only, skipped elsewhere |
| Same, on FAT32 | `Fat32AtomicMoveCharacterizationTest` | opt-in, see below |

## Windows filesystem findings

Measured on NTFS, which is what a CI runner's temporary directory uses:

- `new FileOutputStream(hiddenFile)` fails with "access denied". This is what the temporary-file
  dance in `writePackIndex` works around.
- The NIO equivalents (`Files.newOutputStream`, `Files.write`) open the very same hidden file without
  complaint, and preserve the attribute. The constraint is a `java.io` limitation, not a Windows one.
- `Files.copy(src, dst, REPLACE_EXISTING)` succeeds on a hidden target **and clears the hidden
  attribute**, because the attribute is inherited from the source file.
- `ATOMIC_MOVE` is available, and works onto a hidden target — no `AccessDeniedException`, no
  `AtomicMoveNotSupportedException`.
- The attribute follows the source, so marking the replacement file hidden before the move preserves
  it with no post-move fix-up.

## FAT32: not covered by CI

A Lunii uses FAT32, and **none of the above has been verified on FAT32**. NTFS results do not
transfer: FAT32 has no journal, and its rename semantics are its own.

The test exists but is opt-in, because getting a FAT32 volume onto a GitHub-hosted runner means
driving `diskpart` to create and attach a VHD — slow, elevation-dependent, and prone to failures
unrelated to this project. Making every pull request depend on that was judged a worse trade than
leaving the gap explicit.

To run it against a FAT32 volume:

```
mvn -pl driver test -Dstudio.test.fat32.root=E:\
```

Use a spare FAT32-formatted USB stick, or create a VHD by hand (Disk Management → Action → Create
VHD → initialise → new simple volume → format FAT32). The test refuses to run if the target does not
report a FAT filesystem, and refuses again if it finds a `.md` or `.pi` at the root — but do not aim
it at a story teller regardless.

## Known limitations

- **FAT32 is unverified**, as described above. This is the largest gap.
- **Durability is not tested and cannot be.** No test can establish that `force()` reached the card,
  that the SD controller honoured it, or that a rename survives a power cut.
- **Firmware behaviour is unknown.** Nothing here says how a device reacts to a visible `.pi`, a
  `.pi` whose size is not a multiple of 16, or a stray `.pi.new` at the root.
- **The transfer path itself is not covered.** `uploadPack`, `deletePack`, `reorderPacks` and
  `writePackIndex` need a seam between USB detection and filesystem access before they can be driven
  from a test; the driver's constructor initialises libusb. `DriverTestSupport` works around this
  with reflection for the read-only methods, which is enough to characterise parsing but not enough
  to characterise writing.
- **No JavaScript tests.** The web UI is untested, and the event-bus behaviour is untested.
