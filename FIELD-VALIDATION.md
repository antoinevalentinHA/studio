# Field Validation

What has been observed on real hardware, and — just as importantly — what those observations do not
establish. The automated suites live in `TESTING.md`; this file records the manual exercise that
complements them.

## Scope

This file records **four sessions**, on different builds.

The first exercised `0.4.3-SNAPSHOT` carrying the five hardening changes merged as C1 to C5, i.e. the
tree at commit `22e457d`. The host was a single Windows machine. Two story tellers were used. It
performed real reads, real transfers and real deletions through the STUdio UI. Everything from
*Devices exercised* to *Operational write protocol* comes from it.

The second exercised the C6 series — the reworked `.pi` write path — on one of the same devices. It
is recorded under *Field validation — the C6 write path on real hardware*, and it is the first field
evidence of that code.

The third exercised the same C6 write path again, this time on **both** devices, and is recorded
under *Second C6 field session — a second device and three more writes*.

The fourth ran the **released commit**, `0.4.3-fork.1` at `45e3a55`, on one device, and is the first
to exercise the provenance check on real hardware. It is recorded under *Fourth session — the
provenance check on real hardware*.

These are field observations. They complement the automated tests; they do not replace them, and
they do not generalise. Nothing here has been verified across other firmware revisions, other
Windows versions, other SD cards or other machines, and the sample is two devices on one firmware
revision, driven from one host.

Where a fact below is an observation rather than a demonstrated property, it says so.

## Devices exercised

| Device | Serial | Notes |
| --- | --- | --- |
| A | `40024040004586` | first session: four packs written and read back; also the device involved in the incident described below. Second session: the only device exercised. Third session: two more adds. **Fourth session: nine adds and three same-UUID replacements** |
| B | `23424040008282` | first session: one pack written and read back. Not exercised in the second session. **Third session: written to under the C6 write path.** Not exercised in the fourth |

Serial numbers are recorded because the read-only preflight below checks them, and because the
incident timeline is tied to one specific device.

## C2 — transfer tracking

Exercised during a **real write**, not a simulation:

- a pack was transferred to a device through the normal UI flow;
- the driver logged `Pack added.`;
- the device pack index went from 4 to 5;
- the device was re-read several times afterwards with a stable result;
- **no `INVALID_STATE_ERR` was raised**, and the UI did not report a failure;
- the pack was then played on the device.

**C2 was exercised successfully during a real write operation.** That is the claim, and its limit:
it says this transfer completed and was reported honestly. It does not say future transfers are
guaranteed to behave the same way.

## C3 — device detection

The most direct field evidence of the five, because it crosses a threshold the previous code
measurably could not:

- on plugging the device in, Windows took roughly **20 seconds** to mount the partition;
- the driver kept waiting instead of giving up — the previous implementation allowed ten one-second
  attempts and then abandoned the device silently;
- the device was detected and became usable;
- a full unplug / replug cycle was then performed;
- the device was detected again **without restarting STUdio**, which the previous code could not do
  once its poller had died.

Both failure modes C3 addressed — the abandoned partition search and the permanently dead poller —
were therefore exercised end to end.

## C4 — metadata stream lifecycle

The device was left connected and re-interrogated repeatedly for about ten minutes, more than ten
reads, with no handle exhaustion or file-locking symptom observed.

This is a **field observation, not a demonstration that no leak can occur**. Ten minutes of polling
cannot establish the absence of a leak. The structural guarantee comes from the fix itself — the
metadata stream is now closed on every path out of `getDeviceInfos()` — and from the automated tests
that assert it, including a case that performs 200 consecutive failing calls and then deletes the
file.

## C5 — libusb lifecycle

Confirmed by reading the driver logs during a normal run:

- a single owner announced itself: `context owned by LibUsbDetectionHelper`;
- the second driver logged `reusing the shared context` rather than initialising its own;
- no spurious re-initialisation appeared over the life of the process;
- the arrangement survived plug / unplug cycles.

C5 is the one change directly visible to an operator: the two log lines above are enough to tell
whether a build has it.

## FAT incident

### Established facts

1. A pack was transferred to device A and played successfully.
2. Later, the device was disconnected without a safe eject, at a moment when a write or an open
   handle may still have been outstanding.
3. The device subsequently reported `no audiobook`.
4. A read-only forensic inspection of the card found:
   - roughly **944 MB of clusters still allocated**;
   - roughly **216 bytes visible** through the directory;
   - `.md` present and intact;
   - the volume **not** flagged dirty, and not reformatted;
   - the directory entries for `.pi` and `.content` no longer resolvable.
5. The device was recovered by a factory reset through the official application.

No write was performed on the card during the inspection.

### Observation

The symptoms are **compatible with directory / FAT corruption following an unsafe disconnect**: the
payload was still physically present in allocated clusters while the directory had lost its
references to it, and the one file STUdio never writes — `.md` — was the one still intact.

### Not proven

The causality is **not demonstrated**. The evidence is consistent with the reading above, but it does
not exclude a contribution from the write path itself, and it does not identify which operation left
the directory in that state. Read it as a strongly supported forensic hypothesis, not as an
established root cause.

Two things in particular do **not** follow from this incident or from the operations that came after
it:

- that unsafe disconnection is the sole mechanism by which this can happen;
- that the `.pi` write path is now sound. It was unmodified at the time of these operations. It has
  been reworked since, and later exercised on this same device in the second session below — but a
  session in which nothing went wrong does not explain what went wrong here. See the last section.

## Operational write protocol

The discipline followed during the field operations. It held across every operation performed,
including four delete-and-transfer cycles on device A after the incident, with no recurrence.

1. The official Lunii application is closed. Only one application talks to the device.
2. STUdio is the only writer.
3. No direct access to the device filesystem — not `D:`, not `.pi`, not `.content`, not `.md`. Every
   write goes through STUdio.
4. Read-only preflight before any write, checking: the expected serial number, the firmware version,
   the current pack count, the absence of any HTTP 500, and the absence of any libusb error in the
   log. If any of these is off, stop.
5. One pack operation at a time.
6. Re-read the device after the operation.
7. The pack count must have moved by **exactly ±1**.
8. UUID uniqueness and state stability confirmed across several reads.
9. Replacing a pack is a normal STUdio delete followed by a normal transfer — never a direct
   manipulation of the packs already on the device.
10. Safe Windows eject before unplugging.

This protocol was **successful in the observed field operations**. That is all it claims. It reduces
exposure by removing a suspected trigger and by making each step verifiable; it does **not** make the
`.pi` write path resilient to interruption, because it does not change that code.

## Field validation — the C6 write path on real hardware

A second session, separate from the one recorded above and on a later build, exercised the reworked
`.pi` write path on a device. Everything above it predates that code; this is its first field
evidence.

### Runtime, host, device

- Build: `0.4.3-SNAPSHOT` carrying the C6 series, rebuilt with `mvn clean install` — **188 tests, 0
  failures, 12 skipped**. That is the build used in this field session, not a standing figure for the
  repository.
- Host: a single Windows machine.
- Device exercised: **A**, serial `40024040004586`, firmware `3.3` — the same device involved in the
  FAT incident described above, recovered since by a factory reset.
- Device **B was not exercised** in this session.

### Verification method

Worth recording, because it is what makes the observations mean anything: **the checks were read from
the driver log, not from the UI**. The UI reports what it was told; the log reports what the driver
read back off the card.

- the `Number of packs in index` line, i.e. the count parsed from the real `.pi`;
- that count moving by **exactly ±1** per operation;
- UUID uniqueness across the index;
- the absence of `INVALID_STATE_ERR`, of libusb errors and of HTTP 500 over the window.

### Operations exercised

Packs are designated neutrally. They were personal packs holding third-party content, and which work
each one carried is not part of the evidence; the operation, the identity relation between rows and
the counts are.

| Operation | Pack | Index count | Outcome |
| --- | --- | --- | --- |
| Add | personal pack 1 | 30 → 31 | completed |
| Replace, same UUID | personal pack 2 | 31 → 30 → 31 | completed |
| Replace, same UUID | personal pack 3 | 31 → 30 → 31 | completed, after the separate finding below |

A same-UUID replacement is **one of the more demanding normal write-path scenarios**: the existing
entry and content are removed, then a pack of the same identity is written back. Mishandled, it is
where a duplicate index entry or a leftover content folder would appear. It is an ordinary operation
performed through the normal UI flow, not a stress test — nothing was pushed beyond ordinary use.

### Observed result

- Roughly ten writes over the session, all completing.
- Final state: 31 packs on device A, re-read several times with a stable result.
- Index counts consistent with every operation performed.
- No `INVALID_STATE_ERR`, no libusb error, no HTTP 500 observed over the window.
- No corruption observed on the device that had previously suffered the FAT incident.

**What this establishes, and no more:** the operations performed during this session finished cleanly
on this device, and the index was correct after each of them. It says nothing about operations that
were not performed.

### Limits

- One machine, one firmware revision (`3.3`), one SD card, one device for the writes, one session —
  all of that describes *this* session. The session recorded below adds a second device; the rest of
  these limits survive it unchanged.
- **No interruption was attempted during an `ATOMIC_MOVE`**, nor during a copy.
- No unsafe-disconnection or power-loss stress test of any kind.
- **No physical crash-safety is demonstrated.** `force` remains a request to the operating system,
  and an atomic move remains atomic with respect to the filesystem only — neither claim is tested by
  a session in which nothing was interrupted.
- Nothing here generalises to other devices, firmware revisions, cards, hosts or Windows versions.
- An observation is an observation. One session is not a sample, and a clean run is not a guarantee.

### A separate finding

The session also exposed a stale converted-cache issue: a same-UUID re-transfer could reuse an
out-of-date `converted_*` folder from the local library and send the previous content to the device.
That lives in the library and conversion path, **not** in the device filesystem write path — the
write itself was correct and reported honestly, and the index was right. It was tracked separately;
the silent part of it has since been closed by C7-1, which is described where the next session
touches the same subject.

## Second C6 field session — a second device and three more writes

A third session, later than both of the above, exercised the same reworked `.pi` write path. What it
adds over the session before it is one thing above all: **device B was written to**, so the C6 write
path is no longer evidenced on a single story teller.

### Runtime, host, devices

- Build: `0.4.3-SNAPSHOT` carrying the C6 series, rebuilt and relaunched — **188 tests, 0 failures**.
  That is the build used in this field session, not a standing figure for the repository, and the
  repository has moved since.
- Host: a single Windows machine — the same one.
- Device **A**, serial `40024040004586`, firmware `3.3`.
- Device **B**, serial `23424040008282`, firmware `3.3`.
- Verification read from the driver log, i.e. the count parsed from the real `.pi`, as before.

The session opened with device A at 31 packs, which is where the previous session left it.

### Operations exercised

Three adds. No deletions, no replacements.

| Device | Pack | UUID suffix | Index count | Outcome |
| --- | --- | --- | --- | --- |
| **B** | multi-track custom pack | `fea112e9` | 5 → 6 | completed |
| **A** | multi-track custom pack | `fea112e9` | 31 → 32 | completed |
| **A** | personal pack 4 | `3d75e924` | 32 → 33 | completed |

Neutral designations again, and the UUID suffixes carry what matters here: the first two rows are the
**same pack** sent to two devices, which is what the reuse observation below rests on, and the third
is a different one. These are locally authored packs, so the identifiers are STUdio's own and resolve
to nothing outside this repository.

Final state: device A at 33 packs, device B at 6.

### Observed result

- No `INVALID_STATE_ERR`, no libusb error, no HTTP 500 over the window.
- Index counts re-read and stable, moving by exactly one per operation.
- UUID uniqueness confirmed across the index — no duplicate entry.
- The seven personal packs already on device A were intact after each operation.

**What this establishes, and no more:** three further operations completed cleanly, one of them on a
device that had not previously been written to under this code, and the index was correct after each.
It says nothing about operations that were not performed.

### An additional pack topology

The multi-track custom pack used a graph shape the earlier sessions had not produced: a single action
node holding every track, each track carrying `autoJumpEnabled` and an OK transition to the next, so that
autoplay and the OK button follow the same edge; the last track returns to a cover node rather than
looping. It passed STUdio's own *Verify* with no error and played correctly on both devices.

This is an **authoring and compatibility observation** — that the editor accepts the graph and the
firmware plays what was written. It is not a new property of the write path, which copies whatever it
is given and cannot distinguish one graph shape from another. It is recorded because it is an
additional pack topology exercised through the same C6 write path, not because it demonstrates
anything about integrity.

### The converted cache, as it stood then

The second transfer in the table above sent the same unchanged pack to a second device, reusing the
converted instance produced for the first, with no re-encoding. The content that arrived was correct,
and reusing the conversion was the right thing to do — the source had not changed.

**That observation describes the build used in this session, which predates C7-1. It is not what
`master` does now.** C7-1 changed this path: when a source candidate and a device-compatible
converted instance both exist in the library, STUdio no longer chooses between them silently; it
asks. The same transfer today would present that choice rather than reuse the conversion without a
word.

What the session does show is that reusing an existing conversion **can** be correct when the source
content is unchanged — and equally, that the timestamp comparison the old code used could not
establish that fact reliably. It could not: the artefacts are *named* with the UUID and the library
*groups* them by UUID, but neither the naming nor the grouping records which source a conversion was
produced from, and modification times do not answer that question. Same UUID does not imply same
content, and nothing in the library proved otherwise.

C7-1 removed the silence, not the uncertainty. Establishing that a given conversion was produced from
a given source was still an open problem at the time of this session, and this session was a concrete
argument for solving it: the same pack distributed to several devices is an ordinary thing to want,
and it was exactly the case where a reusable cache is both correct and unprovable.

**It has been solved since, for conversions made after the work landed.** A conversion now records
what it was made from, and both artefacts are re-read and compared before it is reused. The fourth
session exercised that on a device — see *Fourth session — the provenance check on real hardware*.
A conversion made before the recording existed still carries no record, and is still unprovable.

On method: the field report used the count of re-encoding lines in the driver log to tell a fresh
conversion from a reused one — eight for the transfer to B, none for the transfer to A. Their
presence does prove a re-encoding happened. Their absence is only meaningful when the logging
configuration in use actually emits those lines, which are logged at `FINE`; read the count as an
observation from this session rather than as a general test.

### Limits

Everything the previous session could not establish, it still cannot. The sample grew; its shape did
not.

- Two devices, **one firmware revision** (`3.3`), one host machine, one operating system, one
  session.
- Three operations, all adds. No replacement and no deletion were exercised here.
- No interruption was attempted during an `ATOMIC_MOVE` or a copy.
- No unsafe-disconnection or power-loss test of any kind.
- **No physical crash-safety is demonstrated.**
- Additional successful writes on the previously affected device do not establish the cause of the
  historical corruption.
- Nothing here generalises to other firmware revisions, other hardware revisions, other cards, other
  hosts or other Windows versions.

## Fourth session — the provenance check on real hardware

The first session to run the **released commit**, and the first to exercise the provenance work end
to end on a device. What it adds over the three before it is one thing: STUdio was made to compare a
converted pack against the source it was supposedly made from, on real hardware, and it **proved they
differed** rather than merely admitting it could not tell.

### Runtime, host, device

- Version `0.4.3-fork.1`, **commit `45e3a55`**, working tree clean.
- **A local rebuild of that commit**, not the published archive. Same tree as the CI-built asset
  attached to the pre-release; different binary, since a build stamps its own timestamp.
- Host: a single Windows machine. Device **A**, firmware `3.3`. Device **B was not exercised** — its
  last write remains the third session.

On identifying a build: `0.4.3-fork.1` does **not** name one. The version is fixed rather than a
snapshot, so `master` kept declaring it after moving past `45e3a55`; any build from a later tree
reports the released version without being the released tree. A field report therefore has to give
the **commit**, and say whether the runtime was the published asset or a local rebuild.

### What was exercised

Two paths, in order.

**Nine adds**, each a UUID the device had not seen. Index `.pi` **33 → 42**, every added UUID present
and unique on re-read, nine `Pack added.`, no incident. None of the nine had a conversion in the
library beforehand, so all nine converted fresh and none raised a question — which is what should
happen.

**Three same-UUID replacements**, on packs whose content had genuinely changed since their first
conversion: one audio asset replaced, another left alone. Three packs, designated neutrally and
identified by the leading bytes of their UUIDs:

| Pack | UUID prefix | Delete | Verdict shown | Rewrite |
| --- | --- | --- | --- | --- |
| personal pack 5 | `18fce021` | 42 → 41 | MISMATCH | 41 → 42 |
| personal pack 6 | `53c560ca` | 42 → 41 | MISMATCH | 41 → 42 |
| personal pack 7 | `a9da502d` | 42 → 41 | MISMATCH | 41 → 42 |

Final state: **42 packs**, every UUID present and unique, the packs not involved untouched, and over
the whole window **no `INVALID_STATE_ERR`, no libusb error, no HTTP 500**. Safe eject succeeded.

### Deleting first is the hardening, not an obstacle

A replacement had to be done as *delete from the device, then transfer again*. That is the
consequence of refusing a pre-existing `.content` folder: STUdio will not write into a folder it did
not create, so overwriting in place is no longer available. It is the intended behaviour observed
from the outside, and this is its first field observation.

### The verdict, and how it was read

Each of the three drops opened the dialog **and said which of its two answers it was giving**. The
wording seen was the one shown only when the check ran and found a difference:

> STUdio **has checked it**: it was not made from the pack the library holds now, so it would not
> send what you have.

Not the wording used when nothing could be established, which says instead that STUdio **cannot
check** that it was produced from the pack the library holds now, *so it may not match*.

That distinction is the whole point, and it is legible on screen without reading a log:

| What the dialog says | What it means |
| --- | --- |
| *cannot check … so it may not match* | nothing was established |
| *has checked it: it was not made from* | both artefacts were read, and they differ |

So the full chain ran: provenance **recorded** at the first conversion, source and converted artefact
**re-read and re-hashed** at transfer time, and a **difference proven**. Choosing *re-convert*
produced a fresh conversion each time, and of the two audio assets re-encoded, one hashed differently
from before — the one that had been changed — and one did not. Reusing the cached instance would have
sent the wrong content here, and the dialog made that explicit instead of leaving it to be discovered
on the device.

**This is the first field evidence that the provenance check works**, as opposed to the earlier
sessions, which only ever showed that reuse *could* be correct and that timestamps could not
establish it.

On method: counting re-encoding lines in the driver log is still a `FINE`-level oracle and is used
here only to corroborate the on-screen verdict and the new conversion folder, never on its own. The
index count and UUID uniqueness remain readable whatever the log level, and
`POST /api/library/verify-conversion` answers the same question independently of logging — it was not
used in this session.

### Limits

- **One device, one firmware revision (`3.3`), one host, one session.** Device B was not exercised.
- **The runtime was a local rebuild**, not the published archive. Same tree, different binary.
- Only one of the three verdicts was produced. A **match** — convert once, change nothing, transfer
  again, expecting no question at all — was **not exercised**, and it is the case that would show the
  check does not raise false questions. Neither was the **unknown** case, nor the dialog's option to
  send the cached instance anyway; *re-convert* was chosen every time.
- No interruption was attempted during an `ATOMIC_MOVE` or a copy. No unsafe disconnection, no power
  loss. **No physical crash-safety is demonstrated**, and a clean eject remains the last line of
  defence.
- On tooling: `Get-Volume` still reported the volume for a few seconds after a successful eject. It
  is not a reliable oracle for "still mounted", and a positive result there should not be read as an
  eject having failed.

## Remaining integrity gap

The C1 to C5 field results concern detection, transfer tracking, handle lifecycle and libusb
ownership. None of them touches the integrity of what is written to the card, and none should be read
as evidence about it.

### What has changed since, and on what evidence

The `.pi` write path has been reworked since those operations: the index is now written to a
temporary that is created exclusively and cleaned up on failure, synchronised with `force`, and
installed by a single atomic move with no fallback to the previous non-atomic copy. The free-space
precheck and the index parsing were hardened too. `TESTING.md` describes all of it.

That code **has now been exercised on two devices**, across the three sessions that followed the
first. What each kind of evidence covers:

| Kind of evidence | What it covers |
| --- | --- |
| Automated tests | NTFS on Linux and Windows, in CI |
| Filesystem characterization | one disposable FAT32 VHD, run by hand, never in CI |
| Field observation | the C1–C5 sessions, **and** three sessions exercising the reworked write path on **two devices**, all on firmware `3.3` from the same Windows host — adds on both, same-UUID replacements on device A, an additional pack topology played on both, index verified from the driver log throughout |
| Not proven | what happens if power is lost or the device pulled mid-install; that anything reached the flash; any other firmware revision, hardware revision, card or host; the cause of the FAT incident; that a conversion made **before** provenance was recorded came from the source beside it |

A VHD is not a story teller. It shares a filesystem format and nothing else: no firmware, no SD
controller, no removable-media timing — so the characterization work and the field session remain
different kinds of evidence and neither substitutes for the other.

The field sessions move one thing, and precisely one: **whether this code has run against real
hardware**. It has — on two devices, across three sessions, and the operations completed. That is a
larger sample, not a different kind of evidence: it does not move anything about interruption,
durability or generalisation, none of which any session tested.

### What is still open

- An upload that fails part-way leaves an orphan `.content` folder that nothing cleans up, and
  retrying it has no defined semantics.
- `deletePack` rewrites the index before removing the content it points at.
- Nothing detects or reports partial states on a device at connection time.
- Firmware behaviour remains unknown: how a device reacts to a stray `.pi.new`, to a visible `.pi`,
  or to an index whose size is not a multiple of 16 is not documented anywhere here.
- A conversion made **before** provenance was recorded still cannot be tied to the source beside it.
  For those, C7-1's refusal to assume is all there is. Conversions made since carry a record and are
  checked against it, which the fourth session exercised on a device.

No conclusion about resilience to interruption or to power loss should be drawn from the field
results recorded in this document, nor from the filesystem work that followed them.
