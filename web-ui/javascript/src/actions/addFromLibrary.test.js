/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Behavioural contract of actionAddFromLibrary, the action that produced the false failure.
 *
 * The point of these tests is a single distinction: a transfer that the backend reported as failed
 * must be shown as a failure, and a transfer whose *monitoring channel* went away must not. Only
 * two collaborators are stubbed — the HTTP service and the toast library — so the action's own
 * control flow is exercised for real.
 */

jest.mock('../services/device');
jest.mock('react-toastify', () => ({
    toast: Object.assign(jest.fn(() => 'toast-id'), {
        update: jest.fn(),
        error: jest.fn(),
        dismiss: jest.fn(),
        TYPE: { INFO: 'info', SUCCESS: 'success', ERROR: 'error' }
    })
}));

const { toast } = require('react-toastify');
const deviceService = require('../services/device');
const { actionAddFromLibrary } = require('./index');
const { SUBSCRIBED, DEFERRED } = require('../services/eventBusChannel');

/** Translation stub: returns the key, so assertions pin the message identity, not its wording. */
const t = key => key;

/** Minimal stand-in for the event bus channel, with the same contract as the real one. */
function fakeChannel({ open = true } = {}) {
    const handlers = new Map();
    return {
        isOpen: () => open,
        subscribe: jest.fn((address, callback) => {
            if (!open) {
                return DEFERRED;
            }
            handlers.set(address, callback);
            return SUBSCRIBED;
        }),
        unsubscribe: jest.fn(address => handlers.delete(address)),
        emit: (address, body) => {
            const handler = handlers.get(address);
            if (!handler) {
                throw new Error('no handler registered for ' + address);
            }
            handler(null, { body });
        },
        addressCount: () => handlers.size
    };
}

const flush = () => new Promise(resolve => setImmediate(resolve));

/** Renders whatever was handed to toast.update into something assertable. */
function lastRenderKey() {
    const calls = toast.update.mock.calls;
    for (let i = calls.length - 1; i >= 0; i--) {
        const render = calls[i][1] && calls[i][1].render;
        if (typeof render === 'string') {
            return render;
        }
        if (render && render.props) {
            // IssueReportToast wraps the message in a `content` element whose child is the key.
            const content = render.props.content;
            if (content && content.props && content.props.children) {
                return content.props.children;
            }
        }
    }
    return null;
}

async function runAddFromLibrary(channel) {
    const dispatch = jest.fn(action => (typeof action === 'function' ? action(dispatch) : action));
    await actionAddFromLibrary('uuid-1', 'pack.zip', 'fs', 'fs', { channel }, t)(dispatch);
    await flush();
    return dispatch;
}

beforeEach(() => {
    jest.clearAllMocks();
    deviceService.addFromLibrary.mockReset();
    deviceService.fetchDeviceInfos.mockResolvedValue({ plugged: true, driver: 'fs' });
    deviceService.fetchDevicePacks.mockResolvedValue([]);
});

describe('actionAddFromLibrary', () => {

    it('E6: a backend failure is still reported as a failed transfer', async () => {
        deviceService.addFromLibrary.mockRejectedValue(new Error('Internal Server Error: boom'));

        await runAddFromLibrary(fakeChannel({ open: true }));

        expect(lastRenderKey()).toBe('toasts.device.addingFailed');
    });

    it('E6b: a transfer the backend reports as unsuccessful is reported as a failure', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: false });

        expect(lastRenderKey()).toBe('toasts.device.addingFailed');
    });

    it('E5: losing the channel must NOT be reported as a failed transfer', async () => {
        // The channel is down when the action tries to subscribe. This is precisely the field
        // scenario: the POST already succeeded, so the backend is writing the pack right now.
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(fakeChannel({ open: false }));

        expect(lastRenderKey()).not.toBe('toasts.device.addingFailed');
        expect(lastRenderKey()).toBe('toasts.device.trackingLost');
    });

    it('E5b: the transfer request is still issued even though monitoring is unavailable', async () => {
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(fakeChannel({ open: false }));

        expect(deviceService.addFromLibrary).toHaveBeenCalledWith('uuid-1', 'pack.zip');
    });

    it('a successful transfer is reported as success', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: true });

        expect(lastRenderKey()).toBe('toasts.device.added');
    });

    it('E7: both subscriptions are released once the transfer completes', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        expect(channel.addressCount()).toBe(2);

        channel.emit('storyteller.transfer.tid.done', { success: true });

        expect(channel.unsubscribe).toHaveBeenCalledTimes(2);
        expect(channel.addressCount()).toBe(0);
    });

    it('subscribes to progress and done under the transfer id returned by the backend', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);

        const addresses = channel.subscribe.mock.calls.map(call => call[0]);
        expect(addresses).toEqual([
            'storyteller.transfer.tid.progress',
            'storyteller.transfer.tid.done'
        ]);
    });
});
