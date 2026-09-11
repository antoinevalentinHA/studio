# File formats — what the code knows, and what was checked against a device

This is the written counterpart of the readers and writers in `core` and of the device layer in
`driver`. Until now the formats lived only in that code. The point of writing them down is to
separate three things the code blurs together: what has been **verified on a real device**, what
is **only what the code does** (and may or may not be what the firmware expects), and what is
**unknown**. Every field below carries one of those three statuses.

The device used for the verified parts:

| | |
| --- | --- |
| USB id | `0483:a341` (STMicroelectronics), what `LibUsbDetectionHelper` calls `V2` |
| `.md` format | version 7, firmware string `3.2.3` |
| Card | 7.4 GB FAT32, label `LUNII` |
| Session | 2026-09-11, read-only mount, nothing written to the card |

No device data is committed, as with the test suites. The numbers, offsets and byte values below
were read from that card; the only bytes reproduced verbatim are structural ones (headers,
magic values), never keys, serials or content.

Terminology: the code says "V2" for the USB id and "V3" for the AES cipher, and this device is
both. Below, **V2 cipher** means XXTEA with a common key (`.md` versions 1–3) and **V3 cipher**
means AES-CBC with a device key (`.md` versions 6–7); the USB id is not what selects the cipher,
the `.md` version is.

---

## 1. The card

Root of the FAT32 partition, as found:

| Entry | Size | Status | What it is |
| --- | --- | --- | --- |
| `.md` | 128 B | **verified** (v7) | Device metadata: firmware, serial, cipher material. §2 |
| `.pi` | 16 B × packs | **verified** | Pack index: the order of stories on the device. §3 |
| `.content/` | | **verified** | One folder per pack, named by the tail of its UUID. §4 |
| `.cfg` | 42 B | **unknown** | Device settings, apparently `(value u16, key u16)` pairs. §8 |
| `CFG~1` | 42 B | **unknown** | Byte-identical copy of `.cfg`. A FAT short-name artefact of a rename, or a backup. |
| `.logo` | ~11 KB | **verified format** | A BMP, 320×240, 4-bit, RLE4 — the exact image format of pack assets (§6). The boot logo, presumably. Not read or written by STUdio. |
| `.pi.hidden` | 0 B | **not a firmware file** | Written by [Lunii.QT](https://github.com/o-daneel/Lunii.QT), not by the device: its "hide stories" feature moves `.pi` entries here so that the Luniistore app does not see them — and does not delete them (see the factory flag, §5). Empty here because nothing is currently hidden. Same 16-byte UUID records as `.pi`, presumably. |
| `.syncextras` | 4 B | **unknown** | Four zero bytes. |
| `uplugged` | 0 B | **unknown** | Empty marker, written at the last Luniistore sync. Possibly "unplugged without eject". |
| `etc/wifi.prefs` | 1160 B | **unknown** | High-entropy blob, i.e. ciphered. Wi-Fi credentials of the Luniistore app, most likely. |
| `System Volume Information/` | | — | Windows indexer residue, nothing to do with the device. |

Two things about the volume itself, both observed:

- **The firmware leaves the FAT dirty bit set.** After the device has played a story, the next
  Linux mount logs `Volume was not properly unmounted`, even though the previous host unmount
  was clean. The Lunii mounts the card to play and does not clear the flag on power-off. Any
  tool that treats a dirty volume as a reason to refuse would refuse every Lunii that has been
  used; the driver does not, and should not start to.
- **The USB serial string is not the `.md` serial.** `lsusb -v` / the kernel log show a 12-digit
  `iSerialNumber`; `.md` holds a different 14-digit number. Only the latter enters the key
  material.

STUdio reads `.md`, `.pi` and `.content/`. It never touches the rest, which is the right default:
the meaning of `.cfg` and the two empty markers is not established, and a wrong write there is a
write to a device the user cannot easily reflash.

---

## 2. `.md` — device metadata

Little-endian throughout unless stated. The first `u16` is the format version and selects the
layout.

### Versions 6 and 7 — **verified on v7**

| Offset | Size | Field | Status | Notes |
| --- | --- | --- | --- | --- |
| 0x00 | 2 | Format version | verified | `7` on this device. |
| 0x02 | 6 | Firmware version, ASCII, NUL-terminated | verified | `3.2.3\0`. **The parser reads one digit for the major, skips the dot, reads one digit for the minor and ignores the patch level** — `readAsciiToShort(fis, 1)` twice. A firmware `3.10` or `10.0` would be misread. Known gap. |
| 0x08 | 18 | Zero | verified | Skipped by the parser. |
| 0x1A | 24 | Serial number, ASCII, zero-padded | verified | 14 digits followed by 10 NUL bytes on this device. The parser keeps the 24 bytes as the string, NULs included. |
| 0x32 | 2 | Zero | verified | Inside the 14 bytes the parser skips. |
| 0x34 | 2 | **USB vendor id** | **verified, not read by the code** | `0x0483`, little-endian. |
| 0x36 | 2 | **USB product id** | **verified, not read by the code** | `0xa341`. |
| 0x38 | 2 | Zero | verified | |
| 0x3A | 2 | `1` | unknown | |
| 0x3C | 2 | `0` | unknown | |
| 0x3E | 2 | `16` | unknown | Could be a key length, or a count. |
| 0x40 | 16 | AES key | verified | See cipher note below. v7 only; on v6 the key is derived from the serial instead (code only). |
| 0x50 | 16 | AES IV | verified | Same. |
| 0x60 | 16 | High-entropy bytes | **unknown** | Not read. Looks like key material. Tried as an XXTEA key, an AES key and an AES IV against a Luniistore pack, in every byte order; nothing came out. |
| 0x70 | 16 | ASCII, 16 uppercase hex digits | **unknown** | Not read. 8 bytes in hex. Possibly a hardware id (a MAC is 6 bytes, so not exactly that). |

**How the key is used** — verified. `AESCBCCipher` runs the key and the IV through
`BytesUtils.reverseEndianness`, which reverses the byte order *inside each 32-bit word* (not the
whole array). With the key and IV from `.md` transformed that way, `openssl enc -aes-128-cbc -d
-nopad` on the first 512 bytes of a pack file on the card gives exactly the clear file from the
library. Verified on `ri` (whole file, 112 bytes) and on the first 512 bytes of an image. The
firmware therefore stores the key as little-endian words; the code's transform is the one that
matters, not a quirk.

**`bt` on v7** — verified, with a twist. The 32-byte `bt` file the driver writes into every pack
is `serial[0:24] ‖ serial[0:8]` — the 24-byte serial field (NULs included) followed by its first
8 bytes again. **Nine of the twelve packs** on the card have exactly that `bt`, byte-identical:
the three STUdio-made ones and six of the nine Luniistore ones. All nine open with the device
key from `.md`. So for those, `bt` binds a pack to a device and to nothing else.

**The other three Luniistore packs carry an opaque `bt`**: 32 high-entropy bytes, different for
each, and **the device key does not decipher their `ri`, `li` or assets**. Whatever cipher or key
they use is not in `.md` in any form tried (§10). The natural reading — `bt` is a per-pack key
wrapped with the device key — was tested (AES-CBC and AES-ECB unwrap, both halves as key/IV,
word-swapped or not) and did not pan out. Nothing else distinguishes these three: same layout,
same `ni` format, same asset naming, `nm` present, factory flag `0`, and the device plays them.

On v6 the code does the opposite (key from serial, `bt` from `.md`) — code only, no v6 device.

**Device UUID on v6/v7** — code only. The driver fills the 64-byte UUID with `key ‖ iv ‖ zeros`
because these versions have no UUID field. It is a placeholder, not a device property.

### Versions 1 to 3 — code only

No device to check. What the parser does: skip 4, `u16` major, `u16` minor, then a **big-endian**
`u64` serial (formatted as 14 decimal digits; `0`, `-1` and `-4294967296` mean "no serial"), skip
238, then 256 bytes of UUID. The UUID is what `computeSpecificKeyV2FromUUID` turns into the V2
boot key. None of this has been seen on a card during this work.

---

## 3. `.pi` — pack index — **verified**

A bare concatenation of 16-byte UUIDs, **big-endian** (network order, the way `java.util.UUID`
serialises), no header, no count, no terminator. Twelve packs → 192 bytes. The order in the
file is the order on the device. `PackIndexCharacterizationTest` and the C6 series in
`TESTING.md` cover how the driver rewrites it.

One UUID is one slot. Two packs with the same UUID cannot coexist on the device: the second
upload replaces the first. This matters for versioning (§9).

---

## 4. `.content/` — one folder per pack — **verified**

The folder name is the **last 4 bytes of the UUID, as 8 uppercase hex digits** — `5848679e-…-0c61**52d36868**` → `52D36868`. `computePackFolderName` does exactly that; all twelve
folders on the card match their `.pi` entry. The reader also tolerates a `.<something>` suffix on
the folder name (it trims from the first dot), which the library uses for conversion outputs
(`<uuid>.converted_<timestamp>`); nothing on the card had one.

Inside a folder:

| File | Ciphered | Status | Content |
| --- | --- | --- | --- |
| `ni` | no | verified | Node index: pack header + stage nodes. §5 |
| `li` | 512-byte prefix | verified | List index: the option lists that action nodes point into. §5 |
| `ri` | 512-byte prefix | verified | Image index: one 12-byte path per image. §6 |
| `si` | 512-byte prefix | verified | Sound index: same, for sounds. §6 |
| `rf/` | each file, 512-byte prefix | verified | The images. |
| `sf/` | each file, 512-byte prefix | verified | The sounds. |
| `bt` | no | verified | Boot file. 32 bytes on v7, serial-derived on 9 of 12 packs and opaque on 3 (§2); 64 bytes with the V2 cipher (code only). |
| `nm` | — | verified present | Empty marker: night mode available. Present on 7 of the 12 packs, including 2 STUdio-made ones. |
| `.cleartext` | — | library only | Empty marker meaning "assets are not ciphered". Written by STUdio in the library, never on the card. The reader also treats a pack with clear files but no marker as clear and repairs the marker. |

**The cipher boundary** — verified. Exactly the first **512 bytes** of `li`, `ri`, `si` and every
asset are ciphered; byte 512 onward is identical between the card and the clear library copy.
`ni` and `nm` are never ciphered. A file shorter than 512 bytes is ciphered whole. With the V3
cipher a whole-file length that is not a multiple of 16 is **zero-padded up**: the library `ri`
is 108 bytes (9 × 12) and the card's is 112. The reader keeps the padding; `ri`/`si` are
consumed by count, not by length, so it is harmless there. `CipherUtilsCharacterizationTest`
marks the size change as a `KNOWN GAP` because it invalidates size-based verification.

---

## 5. `ni` and `li` — the graph — **verified**

### `ni` header, 512 bytes, little-endian

| Offset | Size | Field | Value seen |
| --- | --- | --- | --- |
| 0 | u16 | Node index format version | `1` on all twelve packs |
| 2 | u16 | **Story pack version** | `1` on eleven packs; `2` on the one whose `story.json` says `2`. See §9. |
| 4 | u32 | Offset of the node list | `512` |
| 8 | u32 | Size of a stage node | `44` |
| 12 | u32 | Number of stage nodes | 18 … 174 |
| 16 | u32 | Number of images | |
| 20 | u32 | Number of sounds | |
| 24 | u8 | "Factory pack" flag | **`1` on every STUdio-made pack, `0` on every Luniistore pack.** The writer hard-codes `1`; the comment says it stops the Luniistore app from inspecting the pack. The Lunii.QT README states that a Luniistore sync **deletes third-party stories from the card** (it added a "hide" feature to work around it). The two older STUdio-made packs on this card survived a Luniistore sync with the flag set, which is consistent with the flag being what protects them — consistent, not proven. Nothing on the device side depends on it: both values play. |
| 25 | 487 | Zero | all zero on all twelve packs |

File size is always `512 + nodes × 44` — checked on all twelve.

### Stage node, 44 bytes, little-endian

| Offset | Size | Field | Notes |
| --- | --- | --- | --- |
| 0 | i32 | Image index in `ri` | `-1` = no image |
| 4 | i32 | Sound index in `si` | `-1` = no sound |
| 8 | i32 | OK transition: action node = offset into `li` | `-1` = none |
| 12 | i32 | OK transition: number of options | |
| 16 | i32 | OK transition: selected option | |
| 20 | i32 | HOME transition: offset into `li` | `-1` = none |
| 24 | i32 | HOME transition: number of options | |
| 28 | i32 | HOME transition: selected option | |
| 32 | u16 | Control: wheel | `0`/`1` |
| 34 | u16 | Control: OK | |
| 36 | u16 | Control: HOME | |
| 38 | u16 | Control: pause | |
| 40 | u16 | Control: autoplay | |
| 42 | u16 | Padding | `0` on every node of every pack |

The first stage node is the pack's entry point; in the archive format its UUID *is* the pack
UUID (`ArchiveStoryPackReader` returns `nodes.get(0).getUuid()`). The fs format has no node
UUIDs at all — the reader invents them on read, except for node 0 which gets the folder's UUID.
This is the `FIXME node uuids from metadata file` in `FsStoryPackReader`, and the reason the
`core` round-trip tests mark the pack UUID as a `KNOWN GAP`.

### `li`

A flat array of `i32` **stage node indices**. An action node is a `(offset, count)` window into
it, as given by the transitions above; the same window can be referenced from several stage
nodes. No header. Length is `4 × (total options)`; a pack with 21 stages and 80 bytes of `li` has
20 options across its menus.

---

## 6. `ri`, `si` and the assets — **verified**

`ri` and `si` are arrays of **12-byte entries**, one per asset, in index order. Each entry is a
relative path, ASCII, backslash-separated, **no terminator**: `000\00000000`, `000\00000001`, …
— a 3-character folder under `rf/` or `sf/`, a backslash, an 8-character file name. Entry *n* is
the path of asset *n*. Ciphered on the card (first 512 bytes, so a pack with more than 42 assets
has its later entries in clear).

**Naming differs by origin** — verified:

| | STUdio-made | Luniistore-made |
| --- | --- | --- |
| Folder | `000` | `000`, then `001` past some count (a 161-sound pack uses both) |
| File name | `00000000`, `00000001`, … sequential | 8 uppercase hex digits, not sequential — `09F05276`, `300F1017` — content-derived, most likely |

The reader never assumes either scheme: it reads the path from the index. The writer produces
the sequential one, and deduplicates assets by SHA-1 before numbering (the header counts are
*distinct* assets, not stage nodes).

**Image format** — verified on the clear library copy and on the card past the cipher boundary:
Windows BMP, `BM` magic, 320 × 240, 1 plane, **4 bits per pixel, compression 2 (RLE4)**, 16-colour
palette. `FsStoryPackWriter` refuses anything else. `.logo` at the card root has the identical
header, which is how its format is known.

**Sound format** — code only, not decoded here: MP3, mono, no ID3 tag, per the writer's checks.

---

## 7. The V2 cipher (XXTEA) — code only

No `.md` v1–3 device was available. What the code does, for the record:

- XXTEA (`btea`) over the first 512 bytes (`CIPHER_BLOCK_SIZE_ASSETS_V2`) of `li`, `ri`, `si`
  and every asset, data as **little-endian** `u32` words, key as **big-endian** words, with
  `rounds = 1 + 52/n` — **not** the reference `6 + 52/n`. A `btea` implementation with the
  reference round count does not interoperate.
- The common key is the 16-byte constant in `XXTEACipher.COMMON_KEY`.
- `bt` is 64 bytes: the first 64 bytes of `ri` (already ciphered with the common key) ciphered
  *again* with a device-specific key derived from the device UUID (`computeSpecificKeyV2FromUUID`:
  decipher the UUID's first block with the common key, then permute its 16 bytes).
- Length-preserving, unlike V3.

`XXTEACipherTest` pins the primitive; `BootFileStreamLifecycleTest` pins `bt`. Neither can say
whether a real V2 firmware accepts the output — that needs a V2 device.

---

## 8. `.cfg` — device settings — **unknown**

42 bytes. Reads naturally as a 4-byte header followed by nine `(value u16, key u16)` pairs and a
trailing `u16`:

```
header  00 01 00 00
pairs   key 1 = 300 · key 2 = 60 · key 3 = 5 · key 4 = 1 · key 5 = 30
        key 6 = 2   · key 7 = 0  · key 8 = 1 · key 9 = 1
tail    01 00
```

The values are the shape of settings — 300 and 60 look like seconds, 5 like a volume level,
the 0/1/2 like toggles or enums — but nothing was changed on the device to confirm which is
which, and this document will not guess. To establish them: change one setting on the device,
copy `.cfg` again, diff. STUdio has no reason to write this file.

---

## 9. Versioning — what the formats allow

The facts, all verified above, for the question "can one story exist in several versions":

1. **The story pack version** is a `u16` at offset 2 of `ni`, `"version"` in `story.json`, a
   `u16` in the raw format's first sector. It travels through every format, the editor edits it
   (`editor.metadata.version`), the library names its archives `<title>-<uuid>-v<version>.zip`,
   and the device stores it. **The firmware does not gate playback on it** — tested: the
   version byte of a `v2` pack on the card was patched to `1` in place (`ni` is clear, one byte,
   nothing else touched), the device was unplugged and the story played normally, menus
   included; the byte was then restored. Whether anything *reads* the field remains unknown —
   the nine Luniistore packs all say `1`, and the Lunii has no display to show it. Also
   observed during that session: the firmware wrote nothing to the card (`.pi`, `.cfg`, the
   empty markers and the pack folders kept their timestamps), so there is no device-side
   state to reconcile with.
2. **The device identifies a story by its UUID only** (`.pi`, folder name). Same UUID → same
   slot → the upload replaces. Different UUID → a second story, unrelated as far as the device
   knows, even if it is byte-for-byte the same graph.
3. **The pack UUID is the first stage node's UUID.** The editor keeps node UUIDs across loads
   and saves (`StageNodeModel` only generates one when none is given), so editing and
   re-exporting a story keeps its UUID and its slot. Duplicating a node, or building a story
   again from scratch, generates new ones.

The card checked had the second situation: the same STUdio story present under **two different
UUIDs with byte-identical `ni`** (only the assets differ), and its current version, `version = 2`,
under a third UUID. That is what happens when versions are made by re-creating rather than
re-exporting: the device fills up with siblings it cannot relate, and only the file name in the
library remembers which is which.

What this means for a "series of versions with history" feature: the device offers nothing to
build on beyond the version number, so the history has to live in the library, keyed by UUID —
several archives of one UUID, the highest version being what goes to the device. That is a
library and UI design, not a format change; it is tracked as its own issue.

---

## 10. Unknowns, in the order they are worth attacking

1. **Three Luniistore packs use a key that is not the device key.** They are the three with an
   opaque `bt` (§2). Same layout, same 512-byte boundary, but neither the device's AES key nor
   the XXTEA common key opens `ri`, `li` or an asset. Tried: every 16-byte field of `.md` as key
   and as IV, raw, word-swapped and reversed, CBC and ECB; XXTEA with the common key and with each
   of those fields; `bt` itself and `bt` unwrapped with the device key, either half as key or
   IV. The other six Luniistore packs on the same card open with the device key, so this is
   a property of those packs (or of how they were delivered), not of official packs in general.
   `.md` 0x60 and 0x70 are the two unread fields with the right shape to be involved. Nothing
   in STUdio depends on reading official packs, so this is curiosity unless a feature needs it.
2. **`.md` 0x3A–0x3F, 0x60, 0x70** — see §2.
3. **`.cfg`** — see §8. One diff session would settle it.
4. **The firmware's use of the story pack version** — unobservable without the Luniistore app
   and an account.
5. **`uplugged`, `.syncextras`** — empty or zero here; their non-empty form has not been
   seen. (`.pi.hidden` is accounted for: Lunii.QT, §1.)
5b. **Does the factory flag really shield a pack from a Luniistore sync?** Testable, but only
   by syncing with the official app, which is also what would delete a pack if the answer is
   no. Back up first.
6. **Whether the "factory" flag matters** — every STUdio pack sets it, every official one
   clears it, and the device plays both.
7. **`.md` v1–3, the V2 cipher, the raw (v1) format** — code only, no device.
8. **The v1 vendor SCSI commands (`0xf6 …`) are not exposed by this firmware.** Probed
   read-only through `SG_IO`: `INQUIRY` answers `STM  Product  0.01` (ST's stock mass-storage
   stack); `0xf6 0x24` (read status register) fails at the USB transport level with no sense
   data, and the kernel log shows one `reset high-speed USB device` per attempt: the device
   stalls on the opcode and is reset by the host — it does not recognise it at all. `RawStoryTellerAsyncDriver` is
   therefore v1-only by construction, and there is no USB path to the internal flash on a
   `0483:a341` device. Nothing else was probed: guessing opcodes on an unknown firmware is how
   one finds a write command by accident.
