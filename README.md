About this fork
===============

This is a fork of [marian-m12l/studio](https://github.com/marian-m12l/studio), maintained
independently and focused on **robustness in real use**: making the device detect reliably, making
failures report honestly, and progressively hardening what STUdio writes to the card. The upstream
project has seen little activity recently; this fork keeps the same purpose, licence and
attribution, and adds fixes, tests and characterization work on top.

**It is work in progress, not a finished product.** Several parts of the write path are still open,
and nothing here claims to survive a power cut. Read *Current status* and *Limitations* below before
deciding whether it fits your use.

## What this fork changes

Grouped by area rather than listed change by change. The detail is in `TESTING.md` and the commit
history.

**Device and transport**

- The partition search waits for the OS to mount the device instead of giving up after ten seconds,
  and an unplug cancels a search in flight.
- A dropped monitoring channel is no longer reported as a failed transfer.
- The libusb context has a single owner, with an idempotent init and shutdown, and the detection
  workers survive transient failures instead of dying silently.
- File handles are released on every path out, not only the nominal one.

**Pack index and filesystem**

- A `.pi` whose length is not a whole number of 16-byte records is rejected rather than turned into
  a fabricated pack UUID.
- An index entry whose content is missing no longer makes the whole device unreadable; the other
  packs stay listable.
- The free-space precheck is `long` end to end and counts what the transfer is known to add — cipher
  padding, the boot file, the index growth and its temporary copy — instead of summing source sizes
  and truncating to an `int`.
- The temporary `.pi.new` is created exclusively, removed again on a controlled failure, and a
  pre-existing one is refused rather than overwritten.
- The temporary is synchronised, then installed with a single `Files.move(ATOMIC_MOVE)`, so the index
  is no longer written through in place. If the atomic move is unsupported the operation fails —
  there is deliberately no fallback to the previous non-atomic copy.
- An upload creates its `.content` folder exclusively. A folder already at that name is **refused,
  never written into and never emptied**: it may be the residue of an interrupted upload, of a delete
  that could not finish, or of another tool, and nothing can tell which. The refusal reads nothing,
  writes nothing and removes nothing.

**Library and conversions**

- A converted file is no longer sent to the device merely because it is the most recent one present.
  When the library holds both something the device can read and something that could be converted
  into it, STUdio asks which one you mean — unless it can prove they correspond.
- New conversions record what they were made from. Before reusing one, STUdio re-reads both the
  source and the converted file and compares them against what it recorded: only a match transfers
  without asking. A difference, or anything it cannot establish, asks.
- Conversions made before this existed carry no such record, so they are never assumed to match. They
  ask, once, and re-converting produces one that no longer will.

**Testing**

- A CI workflow running the Java suite on Linux and Windows, plus the JavaScript suite.
- Characterization and specification tests covering the metadata, index, detection and write paths.
- An opt-in FAT32 suite, exercised by hand on a disposable volume — see `TESTING.md`.
- Three documented sessions of real device operations, on two devices — see `FIELD-VALIDATION.md`.

## Current status

Actively worked on, and **not finished**. What is described above is implemented and tested in the
conditions documented in `TESTING.md`. What is still open, and known to be:

- an upload that fails part-way leaves an orphan `.content` folder that nothing cleans up;
- that orphan then makes every later upload of the same pack **refuse**, which is deliberate and
  explained in the error — but STUdio still offers no supported way to resolve the state it leaves;
- `deletePack` removes the index entry before the content, so a failed removal leaves the content
  behind, de-indexed;
- the driver can list `.content` folders the index does not reference, but nothing calls it: a
  device is not examined for partial states when it is plugged in, and the web UI never shows them.
  Nothing acts on them either — the listing is read-only by design.

Use the operational protocol in `FIELD-VALIDATION.md` if you are writing to a real device.

## Limitations

- **No crash-safety is claimed or proven.** The improvements above concern ordinary failures —
  errors returned by the filesystem — not power loss or physical removal mid-write.
- `force()` asks the operating system to push a file to the card. It is not evidence that the bytes
  reached the flash: the card's controller may acknowledge earlier, and nothing here can observe it.
- `ATOMIC_MOVE` is atomic with respect to the filesystem. It is not a guarantee about power loss.
- FAT32, which is what a device uses, has no journal.
- The FAT corruption incident recorded in `FIELD-VALIDATION.md` is a **forensic hypothesis**, not a
  demonstrated cause. Nothing in this repository establishes what caused it.

## Testing

At the time of writing: **282 Java tests** in the standard suite and **57 JavaScript tests**, all
green, on Linux and on Windows. `TESTING.md` holds the current counts, the opt-in FAT32 figure and
its caveats, and is the authority — the numbers here will go stale before it does.

Two caveats worth knowing before reading anything into any of those numbers:

- **CI never exercises FAT32.** A hosted runner has no such volume, so the opt-in tests skip there
  and are validated by hand on a disposable VHD instead.
- Field results come from three sessions, one machine, two devices. They do not generalise to other
  firmware revisions, other cards or other Windows versions.

`TESTING.md` describes how to run everything, what is covered, and what is deliberately not.

## Getting started

There is **no release, and no tag, for this fork yet**. The download link in the upstream
instructions below points at upstream's own build, which does **not** contain any of the changes
described here.

**Building from source is the only way to run this fork today.** The upstream prerequisites apply
unchanged — Java JDK 11+ to run it, Maven 3+ to build it — but clone **this** repository rather than
the one named in *For developers*:

```
git clone https://github.com/antoinevalentinHA/studio.git
cd studio
mvn clean install
```

That produces the distribution archive in `web-ui/target/`; unzip it and run the launcher script for
your platform, as the upstream instructions describe.

Packaging a release for the fork has not been decided.

## Relationship to upstream

Based on STUdio by [@marian-m12l](https://github.com/marian-m12l), whose reverse-engineering work
this exists on top of. Licence, attribution and disclaimers are unchanged and reproduced below. The
fork can be rebased on upstream if it becomes active again; until then the changes above are
maintained here.

---

The rest of this file is the upstream README, kept as it was except where it would say something
untrue of this fork. What is annotated: the release badge and the download link, which refer to
upstream builds; the clone URL, which is upstream's repository and not this one; and the rule
deciding which file is transferred, which this fork changed. Nothing else in it is edited.

---

[![Upstream release](https://img.shields.io/github/v/release/marian-m12l/studio?label=upstream%20release)](https://github.com/marian-m12l/studio/releases/latest)

*This badge and the download links below refer to **upstream** builds, which do not include this
fork's changes. This fork has no release of its own yet — see [Getting started](#getting-started).*

> [!WARNING]
> Support for V3 devices has been added thanks to the community effort! :partying_face:
> 
> :warning: Implementation in this repository remains mostly untested! Make backups and be prepared to reinitialize your device, should issues arise. :warning:

STUdio - Story Teller Unleashed
===============================

[Instructions en français](README_fr.md)

Create and transfer your own story packs to and from the Lunii\* story teller device.


DISCLAIMER
----------

This software relies on my own reverse engineering research, which is limited to gathering the information necessary to
ensure interoperability with the Lunii\* story teller device, and does not distribute any protected content.

**USE AT YOUR OWN RISK**. Be advised that despite my best efforts to keep this software safe, it comes with
**NO WARRANTY** and may brick your device.

\* Lunii is a registered trademark of Lunii SAS. I am (and this work is) in no way affiliated with Lunii SAS.


USAGE
-----

### Prerequisite

* Java JDK 11+
* On Windows, this application requires the _libusb_ driver to be installed. The easiest way to achieve this is to have
  the official Luniistore\* software installed (but not running).

### Installation

* **Download** [the latest upstream release](https://github.com/marian-m12l/studio/releases/latest)
— this is upstream's build and does not contain this fork's changes; there is no fork release yet —
(alternatively, you can [build the application](#for-developers)).
* **Unzip** the distribution archive
* **Run the launcher script**: either `studio-linux.sh`, `studio-macos.sh` or `studio-windows.bat` depending on your
platform. You may need to make them executable first.
* If it does not open automatically, **open a browser** and type the url `http://localhost:8080` to load the web UI.

Note: avoid running the script as superuser/administrator, as this may create permissions issues.

### Using the application

The web UI is made of two screens:

* The pack library, to manage your local library and transfer to / from your device
* The pack editor, to create or edit a story pack

#### Local library and transfer to/from the device

The pack library screen always shows the story packs in your local library. These are the packs located on your computer
(in a per-user `.studio` folder). **Three file formats** may exist in your library:
* `Raw` is the official format understood by the **older devices** (firmware v1.x -- these devices use a low-level USB protocol)
* `FS` is the official format understood by the **newer devices** (firmware v2.x -- these devices are seen as a removable storage)
* `Archive` is an unofficial format, used by STUdio only in the story pack **editor**

**Conversion** of a story pack will happen automatically when a transfer is initiated, or may be triggered manually.
Variations of a given story pack are grouped together in the UI for better readability. **The most recent file**
(highlighted in the UI) gets transferred to the device.

*This fork changed that rule.* Recency no longer decides: when the library holds both a file the
device can read and something that could be converted into one, this fork asks which you mean,
unless it can prove the first was produced from the second. See
[What this fork changes](#what-this-fork-changes).

When the device is plugged, **another pane will appear on the left side**, showing the device metadata and story packs.
**Dragging and dropping** a pack from or to the device will initiate the transfer.

#### Pack editor

The pack editor screen shows the current story pack being edited. By default, it shows a sample story pack intended as
a model of correct usage.

A pack is composed of a few metadata and the diagram describing the various steps in the story:

* Stage nodes are used to display an image and/or play a sound
* Action nodes are used to transition from one stage to the next, and to manage the available options

The editor supports several file formats for audio and image assets.

##### Images

Image files may use the following formats (formats marked with asterisks are automatically converted when transferring
to the device) :
* PNG
* JPEG
* BMP (24-bits)

**Image dimensions must be 320x240**. Images may use colors, even though some colors may not render accurately due to
the screen being behind the plastic cover. Bear in mind that the color of the cover may change.

##### Audio

Audio files may use the following formats (formats marked with asterisks are automatically converted when transferring
to the device) :
* MP3
* OGG/Vorbis
* WAVE (signed 16-bits, mono, 32000 Hz)

MP3 and OGG files are expected to be sampled at 44100Hz.

#### Wiki

More information, including an illustrated usage guide courtesy of [@appenzellois](https://github.com/appenzellois),
available [in the project wiki](https://github.com/marian-m12l/studio/wiki/Documentation).


FOR DEVELOPERS
--------------

### Prerequisite

* Maven 3+

### Building the application

* Clone this repository: `git clone https://github.com/marian-m12l/studio.git`
— that is **upstream's** repository; to build this fork clone
`https://github.com/antoinevalentinHA/studio.git` instead, see [Getting started](#getting-started)
* Build the application: `mvn clean install`

This will produce the **distribution archive** in `web-ui/target/`.


THIRD-PARTY APPLICATIONS
------------------------

If you liked STUdio, you will also like:
* [Moiki](https://moiki.fr/) is an online tool to create interactive stories that can be exported for STUdio (courtesy
of [@kaelhem](https://github.com/kaelhem))
* [mhios (Mes Histoires Interactives Open Stories)](https://github.com/sebbelese/mhios) was an online open library of interactive
stories (courtesy of [@sebbelese](https://github.com/sebbelese))

LICENSE
-------

This project is licensed under the terms of the **Mozilla Public License 2.0**. The terms of the license are in
the `LICENSE` file.

The `vorbis-java` library, as well as the `VorbisEncoder` class are licensed by the Xiph.org Foundation. The terms of
the license can be found in the `LICENSE.vorbis-java` file.

The `com.jhlabs.image` package is licensed by Jerry Huxtable under the terms of the Apache License 2.0. The terms of
the license can be found in the `LICENSE.jhlabs` file.
