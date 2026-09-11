/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

export const LOCAL_STORAGE_ANNOUNCE_OPTOUT = 'announceOptOut';
export const LOCAL_STORAGE_ANNOUNCE_LAST_SHOWN = 'lastAnnounceShown';
export const LOCAL_STORAGE_ALLOW_ENRICHED_BINARY_FORMAT = 'allowEnrichedBinaryFormat';

/*
 * localStorage is not always there: Safari in private browsing, some embedded browsers and a few
 * privacy settings make every access throw — the accessor itself included. The settings reducer
 * read it while building its initial state, so the store threw during creation and the application
 * never rendered. An absent storage means "no stored value", and that is what these answer.
 */
export function safeGetItem(key) {
    try {
        return window.localStorage.getItem(key);
    } catch (e) {
        return null;
    }
}

export function safeSetItem(key, value) {
    try {
        window.localStorage.setItem(key, value);
        return true;
    } catch (e) {
        return false;
    }
}
