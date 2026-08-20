/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

export function sortPacks(packs) {
    return packs.sort((a,b) => {
        // Official packs last, alphabetic order except for missing titles (uuids, last)
        let titleA = (a.packs[0].title && a.packs[0].title.toUpperCase()) || '__'+a.uuid.toUpperCase();
        let titleB = (b.packs[0].title && b.packs[0].title.toUpperCase()) || '__'+b.uuid.toUpperCase();
        let officialA = a.packs[0].official || false;
        let officialB = b.packs[0].official || false;
        if (officialA === officialB) {
            return (titleA < titleB) ? -1 : (titleA > titleB) ? 1 : 0;
        } else {
            return (officialA < officialB) ? -1 : 1;
        }
    });
}

export function generateFilename(model) {
    return model.title.replace(/ /g, '_') + '-' + model.getEntryPoint().getUuid() + '-v' + model.version + '.zip';
}


/*
 * Names STUdio gives its own conversion outputs: `<uuid>.converted_<millis>`, followed by `.pack`
 * or `.zip` when the output is a file rather than a folder. Six places in LibraryService write this
 * and nothing else in the project produces the substring.
 *
 * This is a naming convention, not recorded provenance. It says a name looks like something STUdio
 * produced; it cannot establish that a file was, nor what it was produced from. A hand-made file can
 * carry the name, and a conversion output that has been renamed no longer does. C7-2 is where a real
 * link between a source and its conversion has to come from — until then this is the strongest
 * signal the library actually carries.
 */
const CONVERSION_OUTPUT_NAME = /\.converted_\d+(\.pack|\.zip)?$/;

function basename(path) {
    // The library sends a file name already, never a path — this only guards against that changing.
    const separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return separator === -1 ? path : path.slice(separator + 1);
}

/*
 * Whether an artefact's name matches the convention above.
 *
 * Anything unrecognisable answers false, which classifies it as a possible source rather than as a
 * conversion output. That is the direction that leads to asking the user rather than to transferring
 * without asking.
 */
export function isConversionOutput(pack) {
    if (!pack || typeof pack.path !== 'string') {
        return false;
    }
    return CONVERSION_OUTPUT_NAME.test(basename(pack.path));
}

/*
 * Decide what to do when a pack is dropped onto a device.
 *
 * `packs` are one UUID's artefacts as the library lists them, most recent first; `driverFormat` is
 * the format the plugged device reads. The result names the action and the two artefacts it
 * concerns.
 *
 * The rule this replaced compared modification times: reuse the device-compatible artefact unless
 * another one was strictly newer. That cannot work. A modification time travels with the content
 * when a file is copied, so an archive holding newer content routinely carries an older timestamp
 * than a conversion that came before it — and then the conversion sorts first, the comparison weighs
 * it against itself, and stale content goes to the device with no warning and a success report. It
 * is an ordering test asked to answer a question about identity, which no stricter or looser
 * comparison would fix.
 *
 * Nothing in the library records which source a conversion output was produced from, so whether the
 * cached artefact matches what is in the library now cannot be established here. Rather than guess,
 * this defers to the user whenever there is something to compare against.
 *
 * Three selections, deliberately distinct:
 *
 *   cached          the most recent artefact the device can read as it stands
 *   convertible     the most recent artefact in some other format — anything at all
 *   sourceCandidate the most recent artefact in some other format whose name is not one STUdio
 *                   gives its own conversion outputs
 *
 * `convertible` and `sourceCandidate` differ because a conversion output can itself be `archive`,
 * `raw` or `fs`: the UI offers a conversion button per format, so `<uuid>.converted_<millis>.zip` is
 * one click away from any pack. Selecting on format alone would offer such a file to the user as the
 * source to convert from, which is how a conversion gets proposed as the source of a conversion.
 *
 * Timestamps still order the list, which is how the most recent artefact within each of the three
 * selections is picked. They never decide whether the cached artefact matches a source, nor whether
 * a re-conversion is needed.
 */
export function chooseDropAction(packs, driverFormat) {
    if (!Array.isArray(packs) || packs.length === 0) {
        return { action: 'none', source: undefined, cached: undefined };
    }
    const cached = packs.find(p => p.format === driverFormat);
    const convertible = packs.find(p => p.format !== driverFormat);
    const sourceCandidate = packs.find(p => p.format !== driverFormat && !isConversionOutput(p));

    if (!cached) {
        // Nothing the device can read: convert. There is no cached artefact to compare anything
        // against, so there is no ambiguity to resolve and this path is left as it was.
        return { action: 'convert', source: convertible, cached };
    }
    if (!sourceCandidate) {
        // No identifiable non-converted source is currently present in the library, so STUdio has no
        // current source to offer for re-conversion. Something produced this artefact at some point;
        // that is not in question, and it is not something the library records.
        return { action: 'transfer', source: undefined, cached };
    }
    // Both a device-readable artefact and something that could be converted into one. Which of them
    // reflects what the user means to send cannot be established here, so the choice is theirs.
    return { action: 'confirm', source: sourceCandidate, cached };
}

/*
 * The one verdict that is allowed to skip the confirmation.
 *
 * `chooseDropAction` decides that a question exists; this decides whether the backend has answered
 * it. Only the exact string `MATCH` may turn the question into a transfer, and it means the backend
 * read both artefacts just now and found both digests equal to the ones recorded when the conversion
 * was made. Nothing weaker qualifies: not a name, not a timestamp, not a size, and not the absence
 * of evidence to the contrary.
 *
 * Everything else keeps the confirmation, and that includes cases nobody planned for — a request
 * that failed, a verdict that arrives empty, a value from some future version this build does not
 * recognise. They are all reported as `UNKNOWN`, which is what they are. The alternative, defaulting
 * to reuse when the answer is unclear, is precisely the behaviour this line of work removed.
 *
 * `MISMATCH` and `UNKNOWN` both keep the dialog, and are still distinguished: one is something
 * STUdio established, the other is something it could not, and the dialog says which.
 */
export function applyProvenanceVerdict(decision, verdict) {
    if (!decision || decision.action !== 'confirm') {
        // A verdict is only ever sought when there is something to confirm.
        return decision;
    }
    if (verdict === 'MATCH') {
        return { ...decision, action: 'transfer', verdict: 'MATCH' };
    }
    return { ...decision, verdict: verdict === 'MISMATCH' ? 'MISMATCH' : 'UNKNOWN' };
}
