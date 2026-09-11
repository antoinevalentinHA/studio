/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * localStorage is not always there. Safari in private browsing, some embedded browsers and a few
 * privacy settings make every access throw, and the settings reducer read it while building its
 * initial state — so the store threw during creation and the application never rendered. These
 * helpers turn that into "no stored value", which is what an absent storage means.
 */

const { safeGetItem, safeSetItem } = require('./storage');

describe('safeGetItem / safeSetItem', () => {
    const realStorage = Object.getOwnPropertyDescriptor(window, 'localStorage');

    afterEach(() => {
        Object.defineProperty(window, 'localStorage', realStorage);
    });

    function replaceStorageWith(impl) {
        Object.defineProperty(window, 'localStorage', { configurable: true, get: () => impl });
    }

    it('reads and writes through when storage works', () => {
        safeSetItem('k', 'v');
        expect(safeGetItem('k')).toBe('v');
    });

    it('answers null when reading throws', () => {
        replaceStorageWith({ getItem: () => { throw new Error('SecurityError'); } });
        expect(safeGetItem('k')).toBeNull();
    });

    it('answers null when the storage accessor itself throws', () => {
        Object.defineProperty(window, 'localStorage', { configurable: true, get: () => { throw new Error('SecurityError'); } });
        expect(safeGetItem('k')).toBeNull();
    });

    it('swallows a failing write and says so', () => {
        replaceStorageWith({ setItem: () => { throw new Error('QuotaExceededError'); } });
        expect(safeSetItem('k', 'v')).toBe(false);
    });
});
