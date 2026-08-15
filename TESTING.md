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
are wrong — a truncated index producing a fabricated pack, a truncated `.md` reporting firmware
`0.0`. Those are marked `KNOWN GAP` in their display name and explained in a comment.

A `KNOWN GAP` test passing does not mean the behaviour is acceptable. It means the behaviour is
known, and that the day someone fixes it the test will fail and force a deliberate decision. When the
integrity work starts, these are the tests to convert from "this is what happens" to "this is what
must happen".

That conversion has already happened once: the test that asserted the `.md` stream stayed open on
failure paths now asserts the opposite, and the class it lives in went back to `@TempDir` so that its
cleanup fails loudly if the leak ever returns. Later suites are specification tests from the start.

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
| `.pi` parsing | `PackIndexCharacterizationTest` | read path only — the write path is not covered |
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

- **FAT32 is unverified**, as described above. **This remains the largest gap**, and it stays open
  until the write path is characterised and exercised on a real FAT32 volume. Field operations
  having gone well (see `FIELD-VALIDATION.md`) says nothing about it: the code that writes `.pi` has
  not been changed.
- **Durability is not tested and cannot be.** No test can establish that `force()` reached the card,
  that the SD controller honoured it, or that a rename survives a power cut.
- **Firmware behaviour is unknown.** Nothing here says how a device reacts to a visible `.pi`, a
  `.pi` whose size is not a multiple of 16, or a stray `.pi.new` at the root.
- **The write path is still not covered.** `uploadPack`, `deletePack`, `reorderPacks` and
  `writePackIndex` have no tests at all. The obstacle that used to be given for this — the driver's
  constructor initialising libusb — has largely been removed since: `FsStoryTellerAsyncDriver` has a
  package-private constructor taking a `DevicePartitionLocator`, `DriverTestSupport` can build an
  instance without running the constructor and point it at a temporary directory, and
  `LibUsbDetectionHelper` exposes a `LibUsbEnvironment` seam with `setEnvironmentForTest`. The
  workers expose `handleEvents`, `pause` and `scanOnce` for the same reason. What remains missing is
  the tests themselves, not the means to write them. Nothing above should be read as saying the USB
  layer is trivially testable: everything native still goes through a substituted environment, and
  the real filesystem behaviour of a device is out of reach.
