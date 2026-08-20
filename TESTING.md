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

The suite holds two kinds of test, and telling them apart matters.

**Characterization tests** pin what the code does today so that a later change is visible. They were
written before any behaviour was modified, and several of them assert things that are wrong — a
truncated `.md` reporting firmware `0.0`, for one. Those are marked `KNOWN GAP` in their display name
and explained in a comment. A `KNOWN GAP` test passing does not mean the behaviour is acceptable. It
means the behaviour is known, and that the day someone fixes it the test will fail and force a
deliberate decision.

**Specification tests** state what the code must do. They came later, as each defect was fixed, and
they are what the hardening work is held to.

Tests move from the first category to the second when a behaviour is corrected, never silently: the
old test goes red, the change is deliberate, and the assertions are inverted on the same fixture.
That has happened for the `.md` stream that stayed open on failure paths, for the truncated `.pi`
that produced a fabricated pack, for the free-space estimate that truncated to an `int`, for the
`.pi.new` left behind by a failed rewrite, and for the retry cases in W5. Whole classes written after
the fact — `ReadPathHardeningTest`, `PackTransferSizeEstimatorTest`, `PackIndexTemporaryFileTest`,
`PackIndexInstallationTest`, `UploadDestinationOwnershipTest` — are specifications from the start.

`WritePathCharacterizationTest` and `Fat32WritePathCharacterizationTest` now hold both: most of their
cases still record behaviour that has not been changed, a few have been converted. The individual
tests say which they are.

Nothing here touches a device. Fixtures are synthesised in code; no device data is committed.

## Current counts

| Suite | Tests |
| --- | --- |
| Java, standard | **237**, 13 skipped — the opt-in FAT32 classes, and one link case that only Linux can set up |
| Java, with `-Dstudio.test.fat32.root=<volume>` | last measured at **172** before the C6d-5 additions; not re-measured since, because it needs the volume mounted |
| JavaScript | **50** |

On Linux the Java totals are the same with a higher skip count, because the Windows-only cases are
skipped rather than absent.

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
| Write path: index rewrite, upload ordering, retry, delete ordering, free-space estimate | `WritePathCharacterizationTest` | mostly characterization, a few converted specifications — W5 (retry) and parts of W3 and W7 are now specifications; runs on the runner's temporary directory, NTFS on Windows; 5 cases Windows-only |
| A pre-existing `.content/<pack>` is refused, never merged into | `UploadDestinationOwnershipTest` | **specifications** — the general ownership rule, on a folder planted by hand |
| Folders under `.content` that `.pi` does not reference | `UnreferencedPackFolderTest` | **specifications** — read-only: reporting one must leave the index, the folders and the listing untouched, and says nothing about where they came from |
| Same, on FAT32, plus what a pack costs in allocated space | `Fat32WritePathCharacterizationTest` | opt-in, see below |
| What the free-space precheck must count | `PackTransferSizeEstimatorTest` | **specifications** — a conservative bound on logical bytes, explicitly not on allocated space |
| `.pi.new` ownership, exclusive creation, cleanup | `PackIndexTemporaryFileTest` | **specifications** |
| Index installed by an atomic move, synchronised first, no fallback | `PackIndexInstallationTest` | **specifications** |
| The driver delegates the index write to an injectable writer | `PackIndexWriterSeamTest` | structural only; asserts no integrity property |
| Directory streams released when a pack copy is interrupted | `FilesWalkResourceLeakTest` | opt-in FAT32 — the leak exists everywhere but is only observable there |
| Windows DOS attributes, `ATOMIC_MOVE` | `WindowsFileSemanticsCharacterizationTest` | Windows only, skipped elsewhere |
| Same, on FAT32 | `Fat32AtomicMoveCharacterizationTest` | opt-in, see below |
| Partition discovery: late mount, cancellation, retry | `DevicePartitionLocatorTest` | |
| Detection state machine: plug, unplug during search, replug | `FsDeviceDetectionTest` | |
| libusb workers surviving transient failures, backoff, abort | `LibUsbWorkerResilienceTest` | covers the Windows polling path |
| libusb context ownership, shared init, idempotent shutdown | `LibUsbLifecycleTest` | |
| Nothing — proves Maven runs JUnit 5 for this module | `WebUiTestHarnessTest` | `web-ui` module; infrastructure only, asserts nothing about the application |
| Nothing — proves Maven runs JUnit 5 for this module | `MetadataTestHarnessTest` | `metadata` module; infrastructure only, asserts nothing about the application. This module compiles to Java 8 |
| The metadata database is released after every read, and its contents survive a round trip | `DatabaseMetadataServiceResourceTest` | `metadata` module; **specifications** — the four delete cases are Windows-only, since POSIX unlinks an open file and would prove nothing |
| When the library may reuse a parsed pack, and when it must re-read the file | `LibraryCacheCoherenceTest` | `web-ui` module; **specifications** — coherence on size and modification time, with the same-size/same-mtime case tested as a stated limit, not as a guarantee |
| What identifies a pack artefact: file digest, canonical tree digest, refusal of unstable reads and of links | `ContentDigestTest` | `web-ui` module; **specifications** — pins the canonical form itself. The junction case is Windows-only, the symbolic-link case Linux-only: neither platform can set up both |
| The provenance ledger: round trip, schema version, and refusal to overwrite a ledger it cannot read | `ConversionProvenanceStoreTest` | `web-ui` module; **specifications** — nothing is wired to conversions yet, so this changes no behaviour |

Web UI (`web-ui/javascript`, run by yarn):

| Area | Test file | Notes |
| --- | --- | --- |
| Event bus channel: subscribe while closed, reconnect, no duplicate handlers | `src/services/eventBusChannel.test.js` | drives the real `vertx3-eventbus-client` over a fake SockJS transport |
| Transfer tracking: a lost channel is not a failed transfer | `src/actions/addFromLibrary.test.js` | |
| Which artefact a drop sends to the device, and when the user is asked | `src/utils/packs.test.js` | the decision is a pure function; the component is not rendered |

## Windows filesystem findings

Measured on NTFS, which is what a CI runner's temporary directory uses:

- `new FileOutputStream(hiddenFile)` fails with "access denied". This was the reason originally given
  for writing the index through a temporary file. It is a `java.io` limitation, so it is no longer
  the reason: the temporary exists to keep the index out of band until it can be installed in one
  operation.
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

- **FAT32 is covered only by opt-in tests**, on a single volume, and never by CI. The write path is
  both characterised and exercised there, which closes the measurement gap — it does not close the
  integrity one. Field operations having gone well (see `FIELD-VALIDATION.md`) says nothing about it
  either: those observations predate the write-path changes and were not repeated afterwards.
- **Durability is not tested and cannot be.** No test can establish that `force()` reached the card,
  that the SD controller honoured it, or that a rename survives a power cut. What the tests do
  establish is narrower: that the synchronisation is *requested*, and requested before the index is
  installed.
- **Firmware behaviour is unknown.** Nothing here says how a device reacts to a visible `.pi`, a
  `.pi` whose size is not a multiple of 16, or a stray `.pi.new` at the root.
- **Part of the write path is hardened; part is only described.** The index installation, the
  temporary file and the free-space precheck have specifications and have been changed. Three
  recorded behaviours have **not** been fixed and are still characterization only: an upload that
  fails part-way leaves an orphan `.content` folder that nothing cleans up, that orphan then makes
  every later upload of the same pack refuse with no supported way to resolve it, and `deletePack`
  rewrites the index before removing the content it points at. A test passing on those means the
  behaviour is known, not that it is safe.
- **The delete order is a deliberate choice, not an accident.** Removing the index entry before the
  content means a failed removal leaves an unreferenced folder — invisible, and enough to make a
  later upload of that pack refuse. Doing it the other way round would be worse: the index would
  point at a folder being dismantled, and what a device makes of a pack whose content is half gone is
  not documented anywhere. The order stays until something safer than either is designed.
- **The critical window is narrowed, not observable.** `PackIndexWriter` is injectable and its
  create / write / sync / install steps can each be made to fail, which is what the installation
  specifications use. What still cannot be observed is the inside of the move itself; proving
  directly what a device sees at that instant remains out of reach, and so does anything about power
  loss.
- **Testability is no longer the obstacle it was.** `FsStoryTellerAsyncDriver` has a package-private
  constructor taking a `DevicePartitionLocator`, `DriverTestSupport` can build an instance without
  running the constructor and point it at a temporary directory, and `LibUsbDetectionHelper` exposes
  a `LibUsbEnvironment` seam with `setEnvironmentForTest`. The workers expose `handleEvents`, `pause`
  and `scanOnce` for the same reason. Nothing here should be read as saying the USB layer is
  trivially testable: everything native still goes through a substituted environment, and the real
  filesystem behaviour of a device is out of reach.
