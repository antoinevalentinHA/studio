/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

const { parseJson } = require('./json');

describe('parseJson', () => {
    it('parses valid JSON', () => {
        expect(parseJson('{"a":1}')).toEqual({ a: 1 });
    });

    it('answers undefined for anything that is not JSON, instead of throwing', () => {
        expect(parseJson('not json')).toBeUndefined();
        expect(parseJson('')).toBeUndefined();
        expect(parseJson(null)).toBeUndefined();
        expect(parseJson(undefined)).toBeUndefined();
    });
});
