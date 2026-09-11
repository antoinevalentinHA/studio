/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import JSZip from 'jszip';
import { readFromArchive } from './reader';

/*
 * An uploaded archive is user input. A story.json that is not JSON used to surface as a raw
 * SyntaxError from deep inside the promise chain; it is now a rejection that names the problem.
 */
describe('readFromArchive', () => {
    it('rejects a story.json that is not JSON with a message that says so', async () => {
        const zip = new JSZip();
        zip.file('story.json', '{not json');
        const blob = await zip.generateAsync({ type: 'blob' });

        await expect(readFromArchive(blob)).rejects.toThrow(/story\.json/);
    });

    it('rejects an archive without story.json rather than throwing on null', async () => {
        const zip = new JSZip();
        zip.file('readme.txt', 'nothing here');
        const blob = await zip.generateAsync({ type: 'blob' });

        await expect(readFromArchive(blob)).rejects.toThrow(/story\.json/);
    });
});
