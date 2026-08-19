# Field Validation

What has been observed on real hardware, and — just as importantly — what those observations do not
establish. The automated suites live in `TESTING.md`; this file records the manual exercise that
complements them.

## Scope

This file records **two sessions**, months apart, on different builds.

The first exercised `0.4.3-SNAPSHOT` carrying the five hardening changes merged as C1 to C5, i.e. the
tree at commit `22e457d`. The host was a single Windows machine. Two story tellers were used. It
performed real reads, real transfers and real deletions through the STUdio UI. Everything from
*Devices exercised* to *Operational write protocol* comes from it.

The second exercised the C6 series — the reworked `.pi` write path — on one of the same devices. It
is recorded under *Field validation — the C6 write path on real hardware*, and it is the first field
evidence of that code.

These are field observations. They complement the automated tests; they do not replace them, and
they do not generalise. Nothing here has been verified across other firmware revisions, other
Windows versions, other SD cards or other machines, and the sample is two devices.

Where a fact below is an observation rather than a demonstrated property, it says so.

## Devices exercised

| Device | Serial | Notes |
| --- | --- | --- |
| A | `40024040004586` | first session: four packs written and read back; also the device involved in the incident described below. Second session: the only device exercised |
| B | `23424040008282` | first session: one pack written and read back. Not exercised in the second session |

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

| Operation | Pack | Index count | Outcome |
| --- | --- | --- | --- |
| Add | Lucky Luke | 30 → 31 | completed |
| Replace, same UUID | Cornebidouille | 31 → 30 → 31 | completed |
| Replace, same UUID | Dans la classe | 31 → 30 → 31 | completed, after the separate finding below |

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

- One machine, one firmware revision (`3.3`), one SD card, one device for the writes, one session.
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
write itself was correct and reported honestly, and the index was right. It is tracked separately and
is not addressed here.

## Remaining integrity gap

The C1 to C5 field results concern detection, transfer tracking, handle lifecycle and libusb
ownership. None of them touches the integrity of what is written to the card, and none should be read
as evidence about it.

### What has changed since, and on what evidence

The `.pi` write path has been reworked since those operations: the index is now written to a
temporary that is created exclusively and cleaned up on failure, synchronised with `force`, and
installed by a single atomic move with no fallback to the previous non-atomic copy. The free-space
precheck and the index parsing were hardened too. `TESTING.md` describes all of it.

That code **has now been exercised on a device**, in the single session recorded above. What each
kind of evidence covers:

| Kind of evidence | What it covers |
| --- | --- |
| Automated tests | NTFS on Linux and Windows, in CI |
| Filesystem characterization | one disposable FAT32 VHD, run by hand, never in CI |
| Field observation | the C1–C5 sessions, **and** one session exercising the reworked write path on device A — adds, same-UUID replacements, index verified from the driver log |
| Not proven | what happens if power is lost or the device pulled mid-install; that anything reached the flash; any other device, firmware revision, card or host; the cause of the FAT incident |

A VHD is not a story teller. It shares a filesystem format and nothing else: no firmware, no SD
controller, no removable-media timing — so the characterization work and the field session remain
different kinds of evidence and neither substitutes for the other.

The field session moves one thing, and precisely one: **whether this code has ever run against real
hardware**. It has, once, and the operations completed. It does not move anything about interruption,
durability or generalisation, none of which that session tested.

### What is still open

- An upload that fails part-way leaves an orphan `.content` folder that nothing cleans up, and
  retrying it has no defined semantics.
- `deletePack` rewrites the index before removing the content it points at.
- Nothing detects or reports partial states on a device at connection time.
- Firmware behaviour remains unknown: how a device reacts to a stray `.pi.new`, to a visible `.pi`,
  or to an index whose size is not a multiple of 16 is not documented anywhere here.

No conclusion about resilience to interruption or to power loss should be drawn from the field
results recorded in this document, nor from the filesystem work that followed them.
