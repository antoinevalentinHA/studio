/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * JSON.parse for input STUdio does not control: a drag-and-drop payload, an uploaded file, a
 * response body. Anything that is not JSON answers undefined instead of throwing out of the
 * handler that asked.
 */
export function parseJson(text) {
    if (typeof text !== 'string' || text === '') {
        return undefined;
    }
    try {
        return JSON.parse(text);
    } catch (e) {
        return undefined;
    }
}
