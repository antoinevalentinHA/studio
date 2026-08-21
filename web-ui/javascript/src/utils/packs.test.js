/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * What to do when a pack is dropped onto a device, and how a conversion output is told apart from
 * something that could have been converted.
 *
 * The library may hold several artefacts for one UUID: a pack the user put there, and conversion
 * outputs produced from one of them at some point in the past. Only one can be sent to the device,
 * and the previous rule picked between them by comparing modification times.
 *
 * That comparison cannot answer the question it was asked. A modification time travels with the
 * content when a file is copied, so an archive holding newer content can carry an older timestamp
 * than the conversion that preceded it — at which point the guard compares the conversion to itself
 * and stale content is sent to the device without a word. This is not hypothetical: it happened on
 * real hardware, and it is recorded in FIELD-VALIDATION.md.
 *
 * Format alone cannot answer it either, and that is the second thing tested here. A conversion
 * output can be `archive`, `raw` or `fs` — the UI offers a conversion button per format — so an
 * artefact whose format merely differs from the driver's may itself be a conversion.
 *
 * Pure functions, so the decision is testable without rendering anything: the project has no React
 * rendering stack and this lot does not add one.
 */

const { chooseDropAction, isConversionOutput, applyProvenanceVerdict } = require('./packs');

// The library lists a pack's artefacts most-recent-first; these fixtures keep that order, and carry
// the file names the backend actually sends.
const pack = (format, path, timestamp) => ({ uuid: 'u', format, path, timestamp });

describe('isConversionOutput', () => {

    it('recognises the three shapes STUdio writes', () => {
        expect(isConversionOutput(pack('fs', 'u.converted_123', 1))).toBe(true);
        expect(isConversionOutput(pack('archive', 'u.converted_123.zip', 1))).toBe(true);
        expect(isConversionOutput(pack('raw', 'u.converted_123.pack', 1))).toBe(true);
    });

    it('does not recognise a pack the user put in the library', () => {
        expect(isConversionOutput(pack('archive', 'u.zip', 1))).toBe(false);
        expect(isConversionOutput(pack('raw', 'u.pack', 1))).toBe(false);
        expect(isConversionOutput(pack('fs', 'Personal Pack', 1))).toBe(false);
    });

    it('does not fire on the substring appearing somewhere else in the name', () => {
        // The convention is a suffix carrying a timestamp, not the word appearing anywhere.
        expect(isConversionOutput(pack('archive', 'converted_pack.zip', 1))).toBe(false);
        expect(isConversionOutput(pack('archive', 'my converted_stories.zip', 1))).toBe(false);
        expect(isConversionOutput(pack('archive', 'u.converted_123.edited.zip', 1))).toBe(false);
        expect(isConversionOutput(pack('archive', 'u.converted_.zip', 1))).toBe(false);
        expect(isConversionOutput(pack('archive', 'u.converted_abc.zip', 1))).toBe(false);
    });

    it('reads the file name when given a path, on either separator', () => {
        // The library sends a file name; this only guards against that changing.
        expect(isConversionOutput(pack('fs', 'C:\\library\\u.converted_123', 1))).toBe(true);
        expect(isConversionOutput(pack('fs', '/home/user/.studio/library/u.converted_123', 1))).toBe(true);
        // A parent folder carrying the name must not classify the artefact inside it.
        expect(isConversionOutput(pack('archive', 'C:\\u.converted_123\\pack.zip', 1))).toBe(false);
        expect(isConversionOutput(pack('archive', '/library/u.converted_123/pack.zip', 1))).toBe(false);
    });

    it('answers false rather than throwing on unusable input', () => {
        // False classifies the artefact as a possible source, which leads to asking the user.
        expect(isConversionOutput(undefined)).toBe(false);
        expect(isConversionOutput(null)).toBe(false);
        expect(isConversionOutput({})).toBe(false);
        expect(isConversionOutput({ path: 42 })).toBe(false);
    });
});

describe('chooseDropAction', () => {

    describe('nothing the device can read', () => {

        it('converts, and this path is unchanged', () => {
            const source = pack('archive', 'u.zip', 2000);

            expect(chooseDropAction([source], 'fs')).toEqual({ action: 'convert', source, cached: undefined });
        });

        it('converts from a conversion output when that is all there is', () => {
            // No cached artefact means nothing to compare against and no ambiguity to resolve, so
            // this keeps converting whatever is convertible, as it did before.
            const leftover = pack('archive', 'u.converted_2000.zip', 2000);

            expect(chooseDropAction([leftover], 'fs').action).toBe('convert');
            expect(chooseDropAction([leftover], 'fs').source).toBe(leftover);
        });

        it('converts the most recent convertible artefact', () => {
            const newer = pack('archive', 'u.zip', 3000);
            const older = pack('raw', 'u.pack', 1000);

            expect(chooseDropAction([newer, older], 'fs').source).toBe(newer);
        });
    });

    describe('a cached artefact and a source candidate', () => {

        it('S1: does not offer a conversion output as the source to convert from', () => {
            // Driver fs, with an archive conversion sorting above the pack the user put there.
            const cached = pack('fs', 'u.converted_3000', 3000);
            const otherConversion = pack('archive', 'u.converted_2000.zip', 2000);
            const sourceCandidate = pack('archive', 'u.zip', 1000);

            const decision = chooseDropAction([cached, otherConversion, sourceCandidate], 'fs');

            expect(decision.action).toBe('confirm');
            expect(decision.source).toBe(sourceCandidate);
            expect(decision.source).not.toBe(otherConversion);
            expect(decision.cached).toBe(cached);
        });

        it('S2: ignores a conversion made for another driver', () => {
            // Driver raw, with an fs conversion — meant for a different device — sorting first.
            const conversionForAnotherDriver = pack('fs', 'u.converted_5000', 5000);
            const cached = pack('raw', 'u.converted_2000.pack', 2000);
            const sourceCandidate = pack('archive', 'u.zip', 1000);

            const decision = chooseDropAction(
                [conversionForAnotherDriver, cached, sourceCandidate], 'raw');

            expect(decision.action).toBe('confirm');
            expect(decision.source).toBe(sourceCandidate);
            expect(decision.cached).toBe(cached);
        });

        it('S4: asks whatever the timestamps say', () => {
            // The whole point: ordering plays no part in deciding whether to ask.
            const cached = pack('fs', 'u.converted_2000', 2000);

            [1000, 2000, 3000].forEach(timestamp => {
                const sourceCandidate = pack('archive', 'u.zip', timestamp);
                const packs = timestamp > 2000 ? [sourceCandidate, cached] : [cached, sourceCandidate];

                const decision = chooseDropAction(packs, 'fs');

                expect(decision.action).toBe('confirm');
                expect(decision.source).toBe(sourceCandidate);
            });
        });

        it('picks the most recent artefact within each selection', () => {
            const newestSource = pack('archive', 'u.zip', 5000);
            const newestCache = pack('fs', 'u.converted_4000', 4000);
            const olderCache = pack('fs', 'u.converted_2000', 2000);
            const olderSource = pack('raw', 'u.pack', 1000);

            const decision = chooseDropAction(
                [newestSource, newestCache, olderCache, olderSource], 'fs');

            expect(decision.source).toBe(newestSource);
            expect(decision.cached).toBe(newestCache);
        });

        it('never reports the cached artefact as the source', () => {
            const cached = pack('fs', 'u.converted_3000', 3000);
            const sourceCandidate = pack('archive', 'u.zip', 1000);

            const decision = chooseDropAction([cached, sourceCandidate], 'fs');

            expect(decision.source).not.toBe(decision.cached);
        });
    });

    describe('a cached artefact and no source candidate', () => {

        it('S3: transfers without inventing a source to convert from', () => {
            // Every other artefact is a conversion output, so there is no current source to offer.
            const cached = pack('fs', 'u.converted_3000', 3000);
            const otherConversion = pack('archive', 'u.converted_2000.zip', 2000);

            const decision = chooseDropAction([cached, otherConversion], 'fs');

            expect(decision.action).toBe('transfer');
            expect(decision.source).toBeUndefined();
            expect(decision.cached).toBe(cached);
        });

        it('transfers when the cached artefact is the only one there is', () => {
            const cached = pack('fs', 'u.converted_1000', 1000);

            expect(chooseDropAction([cached], 'fs')).toEqual(
                { action: 'transfer', source: undefined, cached });
        });

        it('transfers when every artefact matches the driver format', () => {
            const newest = pack('fs', 'u.converted_3000', 3000);
            const older = pack('fs', 'u.converted_1000', 1000);

            const decision = chooseDropAction([newest, older], 'fs');

            expect(decision.action).toBe('transfer');
            expect(decision.cached).toBe(newest);
        });
    });

    describe('degenerate input', () => {

        it('does nothing rather than throwing on an empty list', () => {
            expect(chooseDropAction([], 'fs').action).toBe('none');
        });

        it('does nothing rather than throwing when there is no list at all', () => {
            expect(chooseDropAction(undefined, 'fs').action).toBe('none');
            expect(chooseDropAction(null, 'fs').action).toBe('none');
        });
    });
});

/*
 * What a provenance verdict is allowed to change.
 *
 * C7-1 decided when to ask; this decides when the answer lets the question be skipped. Exactly one
 * value does: the string MATCH, meaning the backend read both artefacts and found both digests equal
 * to the ones recorded when the conversion was made. Everything else — a mismatch it established, an
 * absence of proof, a request that failed, a value nobody expected — keeps the confirmation.
 *
 * That asymmetry is the point, and it is why the tests below spend more effort on what must NOT
 * silence the dialog than on what may.
 */
describe('applyProvenanceVerdict', () => {

    const confirmDecision = () => ({
        action: 'confirm',
        source: { path: 'u.zip', format: 'archive' },
        cached: { path: 'u.converted_3000', format: 'fs' }
    });

    it('MATCH sends the proven conversion without asking', () => {
        const decided = applyProvenanceVerdict(confirmDecision(), 'MATCH');

        expect(decided.action).toBe('transfer');
        expect(decided.cached.path).toBe('u.converted_3000');
    });

    it('MISMATCH keeps the confirmation and says which of the two it is', () => {
        const decided = applyProvenanceVerdict(confirmDecision(), 'MISMATCH');

        expect(decided.action).toBe('confirm');
        expect(decided.verdict).toBe('MISMATCH');
    });

    it('UNKNOWN keeps the confirmation', () => {
        const decided = applyProvenanceVerdict(confirmDecision(), 'UNKNOWN');

        expect(decided.action).toBe('confirm');
        expect(decided.verdict).toBe('UNKNOWN');
    });

    it('a legacy conversion, which the backend cannot place, keeps the confirmation', () => {
        // Every conversion made before provenance was recorded arrives here as UNKNOWN. There are
        // libraries full of them, and none of them may be sent without asking.
        expect(applyProvenanceVerdict(confirmDecision(), 'UNKNOWN').action).toBe('confirm');
    });

    it('a failed verification keeps the confirmation rather than assuming anything', () => {
        // What the caller passes when the request itself failed. A backend that cannot answer must
        // never be read as a backend that said yes.
        expect(applyProvenanceVerdict(confirmDecision(), undefined).action).toBe('confirm');
        expect(applyProvenanceVerdict(confirmDecision(), null).action).toBe('confirm');
    });

    it('an unrecognised verdict is treated as no proof at all', () => {
        // Defensive on purpose: only the exact string MATCH may remove the question, so a renamed
        // or mistyped verdict fails towards asking rather than towards sending.
        expect(applyProvenanceVerdict(confirmDecision(), 'match').action).toBe('confirm');
        expect(applyProvenanceVerdict(confirmDecision(), 'PROVEN').action).toBe('confirm');
        expect(applyProvenanceVerdict(confirmDecision(), '').verdict).toBe('UNKNOWN');
    });

    it('leaves every other decision exactly as C7-1 made it', () => {
        // A verdict is only ever sought when there is something to confirm; the other actions are
        // not the backend's business.
        const convert = { action: 'convert', source: { path: 'u.zip' }, cached: undefined };
        const transfer = { action: 'transfer', source: undefined, cached: { path: 'u.converted_1' } };
        const none = { action: 'none' };

        expect(applyProvenanceVerdict(convert, 'MATCH')).toBe(convert);
        expect(applyProvenanceVerdict(transfer, 'MISMATCH')).toBe(transfer);
        expect(applyProvenanceVerdict(none, 'UNKNOWN')).toBe(none);
    });
});
