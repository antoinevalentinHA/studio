/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Characterization of the device pack reordering drag & drop.
 *
 * Reordering packs on the device is the only way to change the order the Lunii plays them in, and
 * it is driven entirely by the HTML5 drag & drop handlers on the device grid. Nothing has ever
 * exercised those handlers: the reorder path has no test at any level above the pack index writer.
 *
 * These tests drive the real handlers through React's own event system. No rendering library is
 * added for this: react-dom ships `test-utils`, and `Simulate` propagates through the React tree,
 * so a dragleave simulated on a tile reaches the dropzone handler exactly as a bubbling one would.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import { Simulate } from 'react-dom/test-utils';

// The component imports the action creators only to build mapDispatchToProps, which the raw class
// under test never uses. Stubbing the module keeps the editor's dependency graph out of this test.
jest.mock('../actions', () => ({}));

const { PackLibrary } = require('./PackLibrary');

// The dropzone's onDragLeave decides whether the pointer left the zone by comparing the event
// coordinates against the zone's bounding box. jsdom reports every box as zero-sized, which would
// make the check meaningless, so give the layout a real rectangle.
const DROPZONE_BOX = { left: 100, top: 100, width: 400, height: 300, right: 500, bottom: 400 };

const pack = (uuid, title) => ({
    uuid,
    title,
    format: 'fs',
    version: 1,
    official: false,
    nightModeAvailable: false,
    image: null
});

const PACK_A = pack('11111111-1111-1111-1111-111111111111', 'A');
const PACK_B = pack('22222222-2222-2222-2222-222222222222', 'B');
const PACK_C = pack('33333333-3333-3333-3333-333333333333', 'C');

const deviceProps = (packs) => ({
    metadata: {
        uuid: 'device-uuid',
        serial: 'serial',
        firmware: '1.0',
        driver: 'fs',
        storage: { size: 8000000000, taken: 1000000000 }
    },
    packs
});

// A drag never carries data across these handlers — dragStart writes it and nothing reads it back —
// but setData must exist or the handler throws.
const dataTransfer = () => ({ setData: () => {}, getData: () => '' });

let container;
let reorderOnDevice;
let realGetBoundingClientRect;

beforeEach(() => {
    realGetBoundingClientRect = Element.prototype.getBoundingClientRect;
    Element.prototype.getBoundingClientRect = function () { return DROPZONE_BOX; };

    container = document.createElement('div');
    document.body.appendChild(container);
    reorderOnDevice = jest.fn();
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    document.body.removeChild(container);
    Element.prototype.getBoundingClientRect = realGetBoundingClientRect;
});

const renderDeviceGrid = (packs) => {
    const noop = () => {};
    ReactDOM.render(
        <PackLibrary
            t={key => key}
            device={deviceProps(packs)}
            library={{ metadata: {}, packs: [] }}
            settings={{ allowEnriched: false }}
            reorderOnDevice={reorderOnDevice}
            removeFromDevice={noop}
            addFromLibrary={noop}
            addToLibrary={noop}
            downloadPackFromLibrary={noop}
            loadPackInEditor={noop}
            convertPackInLibrary={noop}
            removeFromLibrary={noop}
            uploadPackToLibrary={noop}
            verifyConversion={noop}
            createPackInEditor={noop}
            loadSampleInEditor={noop}
            setAllowEnriched={noop}
        />,
        container);
    return container.querySelectorAll('.device-dropzone .pack-tile');
};

describe('device pack reordering', () => {

    it('sends the new order when a pack is dragged onto another one', () => {
        const tiles = renderDeviceGrid([PACK_A, PACK_B, PACK_C]);

        Simulate.dragStart(tiles[0], { dataTransfer: dataTransfer() });
        Simulate.dragEnter(tiles[2], { dataTransfer: dataTransfer() });
        Simulate.dragEnd(tiles[0], { dataTransfer: dataTransfer() });

        expect(reorderOnDevice).toHaveBeenCalledTimes(1);
        expect(reorderOnDevice).toHaveBeenCalledWith([PACK_B.uuid, PACK_C.uuid, PACK_A.uuid]);
    });

    it('drops the reorder silently when a dragleave reports coordinates outside the dropzone', () => {
        const tiles = renderDeviceGrid([PACK_A, PACK_B, PACK_C]);

        Simulate.dragStart(tiles[0], { dataTransfer: dataTransfer() });
        Simulate.dragEnter(tiles[2], { dataTransfer: dataTransfer() });
        // A dragleave bubbling from a tile, carrying the coordinates browsers report when the
        // pointer is considered to have left the element.
        Simulate.dragLeave(tiles[2], { clientX: 0, clientY: 0, dataTransfer: dataTransfer() });
        Simulate.dragEnd(tiles[0], { dataTransfer: dataTransfer() });

        expect(reorderOnDevice).not.toHaveBeenCalled();
    });
});
