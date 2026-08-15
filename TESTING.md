# Testing

There are two suites: the Java one, run by Maven, and the web UI one, run by yarn. They are
independent and are reported by separate CI jobs.

## Running the Java tests

```
mvn -Dskip.installnodeyarn=true -Dskip.yarn=true test
```

The two `skip` properties bypass the frontend goals, which download Node 12 and run `yarn install`.
They cost the Java suite no coverage — the JavaScript tests are run separately, see below — while
saving roughly two and a half minutes per run and removing a dependency on the npm registry. Every
Java module, `web-ui` included, is still compiled. Dropping the properties runs the full build,
frontend bundle included.

## Running the web UI tests

```
cd web-ui/javascript
yarn install --frozen-lockfile
yarn test --watchAll=false
```

`--frozen-lockfile` turns an out-of-date `yarn.lock` into a failure rather than a silent rewrite of a
tracked file.

## CI

`.github/workflows/ci.yml` runs on every pull request and on pushes to `master`, and reports three
jobs:

| Job | Platform | What it runs |
| --- | --- | --- |
| `Tests (ubuntu-latest)` | Linux | the Maven command above |
| `Tests (windows-latest)` | Windows | the same command — several behaviours under test are Windows-specific |
| `Web UI tests` | Linux | `yarn install --frozen-lockfile` then `yarn test` |

Each job also runs `git diff --exit-code`, so a build that dirties a tracked file fails.

`.github/workflows/maven.yml` is unchanged from upstream and keeps doing what it did: packaging and
publishing a nightly artifact from `master`. Since `package` invokes `test`, it now runs the Java
suite too.

## What these tests are, and what they are not

They are **characterization tests**. They pin what the code does today so that a later change is
visible, and they were written before any behaviour was modified. Several of them assert things that
are wrong — a truncated `.md` reporting firmware `0.0`, for one. Those are marked `KNOWN GAP` in
their display name and explained in a comment.

A `KNOWN GAP` test passing does not mean the behaviour is acceptable. It means the behaviour is
known, and that the day someone fixes it the test will fail and force a deliberate decision. When the
integrity work starts, these are the tests to convert from "this is what happens" to "this is what
must happen".

That conversion has happened twice so far. The test that asserted the `.md` stream stayed open on
failure paths now asserts the opposite, and the class it lives in went back to `@TempDir` so that its
cleanup fails loudly if the leak ever returns. The three that recorded a truncated `.pi` being turned
into a fabricated pack now require it to be rejected instead. Later suites are specification tests
from the start.

Nothing here touches a device. Fixtures are synthesised in code; no device data is committed.

## Coverage map

Not exhaustive — it lists what is covered, not what ought to be. Cases marked Windows-only are
skipped on Linux rather than absent, so the two platforms report the same total with different skip
counts.

| Area | Test class | Notes |
| --- | --- | --- |
| Endianness helpers used for the V3 AES key | `BytesUtilsCharacterizationTest` | core module |
| UUID → `.content` folder name | `PackFolderNamingCharacterizationTest` | includes the driver/core duplication cross-check |
| V2 and V3 transfer ciphering, file selection | `CipherUtilsCharacterizationTest` | |
| `.md` parsing, version dispatch, key derivation, stream lifecycle | `DeviceMetadataCharacterizationTest` | 4 handle-lifecycle cases are Windows-only |
| `.pi` parsing | `PackIndexCharacterizationTest` | the malformed-index cases are specifications, not characterization |
| `.pi` framing, orphan index entries, stream ownership on reads | `ReadPathHardeningTest` | **specifications**, not characterization: what the read path must guarantee |
| Write path: `writePackIndex`, upload ordering, retry, delete ordering, free-space estimate | `WritePathCharacterizationTest` | characterization only; runs on the runner's temporary directory, NTFS on Windows; 5 cases Windows-only |
| Same, on FAT32, plus what a pack costs in allocated space | `Fat32WritePathCharacterizationTest` | opt-in, see below |
| Directory streams released when a pack copy is interrupted | `FilesWalkResourceLeakTest` | opt-in FAT32 — the leak exists everywhere but is only observable there |
| Windows DOS attributes, `ATOMIC_MOVE` | `WindowsFileSemanticsCharacterizationTest` | Windows only, skipped elsewhere |
| Same, on FAT32 | `Fat32AtomicMoveCharacterizationTest` | opt-in, see below |
| Partition discovery: late mount, cancellation, retry | `DevicePartitionLocatorTest` | |
| Detection state machine: plug, unplug during search, replug | `FsDeviceDetectionTest` | |
| libusb workers surviving transient failures, backoff, abort | `LibUsbWorkerResilienceTest` | covers the Windows polling path |
| libusb context ownership, shared init, idempotent shutdown | `LibUsbLifecycleTest` | |

Web UI (`web-ui/javascript`, run by yarn):

| Area | Test file | Notes |
| --- | --- | --- |
| Event bus channel: subscribe while closed, reconnect, no duplicate handlers | `src/services/eventBusChannel.test.js` | drives the real `vertx3-eventbus-client` over a fake SockJS transport |
| Transfer tracking: a lost channel is not a failed transfer | `src/actions/addFromLibrary.test.js` | |

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

A Lunii uses FAT32, and NTFS results do not transfer to it: FAT32 has no journal, and its rename
semantics are its own. Most of the findings above have since been replayed on one real FAT32 volume
and held — the hidden attribute is lost there too, `ATOMIC_MOVE` worked, handle semantics matched —
but that is one VHD, one Windows version, one cluster size, and no device.

The FAT32 tests exist but are opt-in, because getting a FAT32 volume onto a GitHub-hosted runner
means driving `diskpart` to create and attach a VHD — slow, elevation-dependent, and prone to
failures unrelated to this project. Making every pull request depend on that was judged a worse trade
than leaving the gap explicit. **CI therefore never exercises FAT32**, and the one regression that is
only observable there — `FilesWalkResourceLeakTest` — is not guarded by it.

To run it against a FAT32 volume:

```
mvn -pl driver test -Dstudio.test.fat32.root=E:\
```

Use a spare FAT32-formatted USB stick, or create a VHD by hand (Disk Management → Action → Create
VHD → initialise → new simple volume → format FAT32). The test refuses to run if the target does not
report a FAT filesystem, and refuses again if it finds a `.md` or `.pi` at the root — but do not aim
it at a story teller regardless.

## Known limitations

- **FAT32 is covered only by opt-in tests**, on a single volume, and never by CI. The write path has
  been characterised there, which closes the measurement gap — it does not close the integrity one.
  Field operations having gone well (see `FIELD-VALIDATION.md`) says nothing about it either: the
  code that writes `.pi` has not been changed.
- **Durability is not tested and cannot be.** No test can establish that `force()` reached the card,
  that the SD controller honoured it, or that a rename survives a power cut.
- **Firmware behaviour is unknown.** Nothing here says how a device reacts to a visible `.pi`, a
  `.pi` whose size is not a multiple of 16, or a stray `.pi.new` at the root.
- **The write path is described, not hardened.** `writePackIndex`, `uploadPack`, `deletePack`,
  `reorderPacks` and the free-space estimate are now covered — by `WritePathCharacterizationTest` on
  the standard filesystem, and by `Fat32WritePathCharacterizationTest` on an opt-in FAT32 volume.
  Those are **characterization** tests: they record what the code does, including the parts that are
  plainly undesirable — `.pi` replaced by a non-atomic copy, `.pi.new` left behind by a failed
  rewrite, an orphan `.content` folder left by a failed upload, an index rewritten before the content
  it points at is removed, an estimate that does not upper-bound what gets allocated. **None of that
  has been fixed.** A test passing here means the behaviour is known, not that it is safe, and
  nothing in this repository establishes crash-safety or durability.
- **There is no seam for the critical window.** `writePackIndex` builds its paths with
  `Paths.get(String)`, so no instrumented filesystem can be substituted and there is no extension
  point between writing `.pi.new` and the end of the copy. Proving directly that there is an instant
  with no valid index on the device therefore remains out of reach.
- **Testability is no longer the obstacle it was.** `FsStoryTellerAsyncDriver` has a package-private
  constructor taking a `DevicePartitionLocator`, `DriverTestSupport` can build an instance without
  running the constructor and point it at a temporary directory, and `LibUsbDetectionHelper` exposes
  a `LibUsbEnvironment` seam with `setEnvironmentForTest`. The workers expose `handleEvents`, `pause`
  and `scanOnce` for the same reason. Nothing here should be read as saying the USB layer is
  trivially testable: everything native still goes through a substituted environment, and the real
  filesystem behaviour of a device is out of reach.
