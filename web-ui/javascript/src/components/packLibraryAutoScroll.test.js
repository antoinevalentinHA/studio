/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The device dropzone scrolls itself while a pack is dragged near its edges.
 *
 * These are specifications, not characterization: the behaviour is new, and the reason it exists is
 * that a drag raises dragenter only for tiles the pointer reaches, so a pack below the fold was
 * previously not a possible drop target at all.
 *
 * The frame loop is driven by hand here. jsdom has no layout, so a real requestAnimationFrame would
 * only make the test wait; and the point under test is what the component writes to scrollTop on
 * each frame, which a stub observes exactly.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import { Simulate } from 'react-dom/test-utils';

jest.mock('../actions', () => ({}));

const { PackLibrary } = require('./PackLibrary');

// The zone spans y = 100..400. The component treats the 80px nearest each edge as the scroll band.
const ZONE = { left: 100, top: 100, width: 400, height: 300, right: 500, bottom: 400 };

const pack = (uuid) => ({
    uuid, title: uuid, format: 'fs', version: 1, official: false, nightModeAvailable: false, image: null
});

let container;
let frames;          // pending rAF callbacks, keyed by handle
let nextFrameHandle;
let realRaf, realCancel, realGetBox;

// Runs the queued frame callbacks n times over. Each callback re-registers itself, exactly as the
// component's loop does, so this walks the loop forward one frame at a time.
const advanceFrames = (n) => {
    for (let i = 0; i < n; i++) {
        const due = Array.from(frames.values());
        frames.clear();
        due.forEach(cb => cb());
    }
};

beforeEach(() => {
    realGetBox = Element.prototype.getBoundingClientRect;
    Element.prototype.getBoundingClientRect = function () { return ZONE; };

    frames = new Map();
    nextFrameHandle = 1;
    realRaf = window.requestAnimationFrame;
    realCancel = window.cancelAnimationFrame;
    window.requestAnimationFrame = (cb) => { const h = nextFrameHandle++; frames.set(h, cb); return h; };
    window.cancelAnimationFrame = (h) => { frames.delete(h); };

    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    document.body.removeChild(container);
    Element.prototype.getBoundingClientRect = realGetBox;
    window.requestAnimationFrame = realRaf;
    window.cancelAnimationFrame = realCancel;
});

// jsdom has no layout, so scrollTop would stay 0 whatever the component assigns. Back it with a
// plain property: what is asserted is what the component writes, which is the contract that matters.
const withObservableScroll = (element) => {
    let value = 0;
    Object.defineProperty(element, 'scrollTop', {
        configurable: true,
        get: () => value,
        set: (v) => { value = v; }
    });
    return element;
};

const renderGrid = () => {
    const noop = () => {};
    const props = {};
    ['reorderOnDevice', 'removeFromDevice', 'addFromLibrary', 'addToLibrary', 'downloadPackFromLibrary',
     'loadPackInEditor', 'convertPackInLibrary', 'removeFromLibrary', 'uploadPackToLibrary',
     'verifyConversion', 'createPackInEditor', 'loadSampleInEditor', 'setAllowEnriched']
        .forEach(name => { props[name] = noop; });

    ReactDOM.render(
        <PackLibrary
            t={key => key}
            device={{
                metadata: { uuid: 'd', serial: 's', firmware: '1.0', driver: 'fs',
                            storage: { size: 8000000000, taken: 1000000000 } },
                packs: [pack('a'), pack('b'), pack('c')]
            }}
            library={{ metadata: {}, packs: [] }}
            settings={{ allowEnriched: false }}
            {...props} />,
        container);

    return {
        zone: withObservableScroll(container.querySelector('.device-dropzone')),
        tiles: container.querySelectorAll('.device-dropzone .pack-tile')
    };
};

const dragOverAt = (zone, clientY) => Simulate.dragOver(zone, { clientY, dataTransfer: { getData: () => '' } });

describe('device list auto-scroll during a drag', () => {

    it('does not scroll while the pointer is away from both edges', () => {
        const { zone } = renderGrid();

        dragOverAt(zone, 250);          // dead centre of a 100..400 zone
        advanceFrames(5);

        expect(zone.scrollTop).toBe(0);
        expect(frames.size).toBe(0);    // and no loop was left running
    });

    it('scrolls up near the top edge and down near the bottom edge', () => {
        const { zone } = renderGrid();

        dragOverAt(zone, 110);          // 10px into a 80px band at the top
        advanceFrames(1);
        const afterUp = zone.scrollTop;
        expect(afterUp).toBeLessThan(0);

        dragOverAt(zone, 390);          // 10px into the band at the bottom
        advanceFrames(1);
        expect(zone.scrollTop).toBeGreaterThan(afterUp);
    });

    it('scrolls faster the deeper the pointer is into the edge band', () => {
        const { zone: near } = renderGrid();
        dragOverAt(near, 395);          // 5px from the bottom edge
        advanceFrames(1);
        const deep = near.scrollTop;

        ReactDOM.unmountComponentAtNode(container);
        const { zone: shallow } = renderGrid();
        dragOverAt(shallow, 330);       // just inside the band
        advanceFrames(1);

        expect(deep).toBeGreaterThan(shallow.scrollTop);
    });

    it('keeps scrolling while the pointer is held still, without further dragover events', () => {
        const { zone } = renderGrid();

        dragOverAt(zone, 110);          // one event, then nothing — a held pointer
        advanceFrames(1);
        const afterOneFrame = zone.scrollTop;
        advanceFrames(4);

        expect(zone.scrollTop).toBeLessThan(afterOneFrame);
    });

    it('stops the loop when the drag ends', () => {
        const { zone, tiles } = renderGrid();

        // dragEnd reads the order captured at dragStart, so the drag has to have actually started.
        Simulate.dragStart(tiles[0], { dataTransfer: { setData: () => {}, getData: () => '' } });
        dragOverAt(zone, 110);
        advanceFrames(1);
        const whenDragEnded = zone.scrollTop;

        Simulate.dragEnd(tiles[0], { dataTransfer: { getData: () => '' } });
        advanceFrames(5);

        expect(zone.scrollTop).toBe(whenDragEnded);
        expect(frames.size).toBe(0);
    });

    it('stops the loop when the pack is dropped', () => {
        const { zone } = renderGrid();

        dragOverAt(zone, 390);
        advanceFrames(1);
        const whenDropped = zone.scrollTop;

        Simulate.drop(zone, { dataTransfer: { getData: () => '' } });
        advanceFrames(5);

        expect(zone.scrollTop).toBe(whenDropped);
        expect(frames.size).toBe(0);
    });

    it('cancels the pending frame when the component unmounts mid-drag', () => {
        const { zone } = renderGrid();

        dragOverAt(zone, 110);
        expect(frames.size).toBe(1);

        ReactDOM.unmountComponentAtNode(container);

        expect(frames.size).toBe(0);
    });
});
