# Contributing

This is a fork of [marian-m12l/studio](https://github.com/marian-m12l/studio) focused on the
robustness of the write path: what STUdio puts on a Lunii, and how it fails when it fails. The
conventions below exist because of that focus. They are short to read and non-negotiable in review.

## Building and testing

Java 11, Maven. The frontend is built by Maven too, through a pinned Node, but the Java suite does
not need it:

```
mvn -Dskip.installnodeyarn=true -Dskip.yarn=true test
```

The JavaScript suite runs on its own, from `web-ui/javascript`:

```
yarn install --frozen-lockfile
yarn test
```

`TESTING.md` explains both in detail, including the opt-in FAT32 suite that CI cannot run. CI
runs the Java suite on **Linux and Windows** — several behaviours under test are Windows-specific,
file locking above all — and the JavaScript suite on Linux. A pull request is green when all three
are.

Coverage is measured by JaCoCo during the same `mvn test`, one HTML report per module under
`target/site/jacoco/`; CI keeps them as artifacts. It is measured, not enforced: there is no
threshold, and adding one is a decision to make after looking at the numbers, not before.

## What a change looks like

- **One fix, one branch, one pull request.** A pull request that fixes two things gets reviewed
  as neither.
- **Tests on both sides of the change.** A Java change comes with Java tests, a frontend change
  with JavaScript tests. A fix comes with a test that was red before it.
- **The build must not dirty the tree.** CI runs `git diff --exit-code` after the build. A plugin
  that rewrites a tracked file (a lockfile, a generated resource) is a failure, not a convenience.
- **Comments and commit messages in English.** The rest of the project is, and the upstream is.
- **A pull request description says what was wrong, what changed, and what was deliberately not
  done.** The repository's history is written that way on purpose: a reader six months later should
  understand the decision without the conversation that led to it.
- **No device data, no third-party content in the repository.** Fixtures are synthesised in code.
  Keys, serials and official story packs are described in `FORMATS.md`, never reproduced.

## Two kinds of test

The suite distinguishes **characterization** tests, which pin what the code does today — including
where it is wrong — from **specification** tests, which state what it must do. A characterization
test that records a defect carries `KNOWN GAP` in its display name and a comment saying why. It
passing does not mean the behaviour is acceptable; it means the behaviour is known, and that the day
someone fixes it the test goes red and forces a deliberate decision.

A test moves from the first kind to the second when the behaviour is corrected — never silently.
The old assertions are inverted on the same fixture, in the same commit as the fix. Deleting or
loosening a `KNOWN GAP` test without changing the behaviour it records is the one thing a review
will always refuse.

`TESTING.md` has the full account, the coverage map, and what the tests cannot establish (durability
on a card, firmware behaviour). `FORMATS.md` has the file formats, with what was verified on a
device and what is only what the code does.

## Where things are tracked

Issues live in the fork's own tracker. Pull requests go to `antoinevalentinHA/studio:master`; the
owner merges. When GitHub offers the upstream as the base, re-select the fork.
