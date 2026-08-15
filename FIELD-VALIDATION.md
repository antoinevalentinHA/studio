# Field Validation

What has been observed on real hardware, and — just as importantly — what those observations do not
establish. The automated suites live in `TESTING.md`; this file records the manual exercise that
complements them.

## Scope

The runtime exercised was `0.4.3-SNAPSHOT` carrying the five hardening changes merged as C1 to C5,
i.e. the tree at commit `22e457d`. The host was a single Windows machine. Two story tellers were
used. The session performed real reads, real transfers and real deletions through the STUdio UI.

These are field observations. They complement the automated tests; they do not replace them, and
they do not generalise. Nothing here has been verified across other firmware revisions, other
Windows versions, other SD cards or other machines, and the sample is two devices.

Where a fact below is an observation rather than a demonstrated property, it says so.

## Devices exercised

| Device | Serial | Notes |
| --- | --- | --- |
| A | `40024040004586` | four packs written and read back; also the device involved in the incident described below |
| B | `23424040008282` | one pack written and read back |

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
- that the `.pi` write path is now sound. It has not been modified. See the last section.

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

## Remaining integrity gap

The field results above concern detection, transfer tracking, handle lifecycle and libusb ownership.
None of them touches the integrity of what is written to the card, and none should be read as
evidence about it.

As of this commit:

- `writePackIndex` is unchanged. It writes `.pi.new`, then replaces `.pi` with a non-atomic copy,
  then deletes the temporary file.
- Nothing in the codebase forces data to the card before reporting success.
- The `.pi` write path is the main remaining integrity subject.
- FAT32 behaviour is still uncharacterised; the automated suite covers NTFS only, and its FAT32 test
  is opt-in (`TESTING.md`).

No conclusion about resilience to interruption or to power loss should be drawn from the field
results recorded in this document.
