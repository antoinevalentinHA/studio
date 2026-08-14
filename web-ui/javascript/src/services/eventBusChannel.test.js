/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * These tests drive the REAL vertx3-eventbus-client. Only the SockJS transport underneath it is
 * faked, so that a test can open, close and reconnect the socket on demand. Nothing else is mocked:
 * the INVALID_STATE_ERR reproduced below is thrown by the actual library, not by a stand-in.
 */

// Must be `mock`-prefixed: jest hoists the factory above the module scope and only allows it to
// close over variables whose name starts with "mock".
var mockSockets = [];

jest.mock('sockjs-client', () => {
    class FakeSockJS {
        constructor(url) {
            this.url = url;
            this.sent = [];
            this.closed = false;
            mockSockets.push(this);
        }

        send(payload) {
            if (this.closed) {
                throw new Error('FakeSockJS: send on a closed socket');
            }
            this.sent.push(JSON.parse(payload));
        }

        close() {
            this.serverClose();
        }

        // --- test controls ---------------------------------------------

        /** Completes the handshake, as a real SockJS connection would. */
        serverOpen() {
            this.onopen && this.onopen();
        }

        /** Drops the socket the way the Vert.x bridge does when the ping times out. */
        serverClose() {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.onclose && this.onclose({ code: 1000 });
        }

        /** Delivers an event-bus message to the client. */
        serverSend(address, body) {
            this.onmessage && this.onmessage({ data: JSON.stringify({ type: 'message', address, body }) });
        }

        registrations() {
            return this.sent.filter(m => m.type === 'register').map(m => m.address);
        }

        unregistrations() {
            return this.sent.filter(m => m.type === 'unregister').map(m => m.address);
        }
    }

    return function (url, _reserved, options) {
        return new FakeSockJS(url, options);
    };
});

const EventBus = require('vertx3-eventbus-client');
const {
    createEventBusChannel,
    CHANNEL_OPEN,
    CHANNEL_CLOSED,
    CHANNEL_CONNECTING,
    SUBSCRIBED,
    DEFERRED
} = require('./eventBusChannel');

const URL = 'http://localhost:8080/eventbus';

beforeEach(() => {
    mockSockets.length = 0;
    jest.useFakeTimers();
});

afterEach(() => {
    jest.clearAllTimers();
    jest.useRealTimers();
});

const sockets = () => mockSockets;
const lastSocket = () => mockSockets[mockSockets.length - 1];

/* ====================================================================================
 * Root cause. These assert the behaviour of the library as it is today, and explain why
 * the UI reported a failed transfer for a transfer that had actually succeeded.
 * ==================================================================================== */

describe('root cause: raw vertx3-eventbus-client', () => {

    it('throws INVALID_STATE_ERR when registerHandler runs on a closed bus', () => {
        const bus = new EventBus(URL);
        lastSocket().serverOpen();
        expect(bus.state).toBe(EventBus.OPEN);

        lastSocket().serverClose();

        // This is the exact error seen in the field. It is a transport-level error, but the caller
        // in actions/index.js catches it in the same `.catch` as a backend failure and renders
        // "Échec de l'ajout du pack à l'appareil" — for a transfer the backend is still running.
        expect(() => bus.registerHandler('storyteller.transfer.abc.progress', () => {}))
            .toThrow('INVALID_STATE_ERR');
    });

    it('throws INVALID_STATE_ERR while still connecting, before the socket is open', () => {
        const bus = new EventBus(URL);

        expect(bus.state).toBe(EventBus.CONNECTING);
        expect(() => bus.registerHandler('storyteller.plugged', () => {})).toThrow('INVALID_STATE_ERR');
    });

    it('does not reconnect on its own: reconnectEnabled is false unless explicitly enabled', () => {
        const bus = new EventBus(URL, { vertxbus_reconnect_attempts_max: 10 });

        // Passing the option only raises the cap; it does NOT switch reconnection on. Only
        // enableReconnect(true) does. A fix that merely passes options would silently do nothing.
        expect(bus.reconnectEnabled).toBe(false);

        lastSocket().serverOpen();
        lastSocket().serverClose();
        jest.advanceTimersByTime(60000);

        expect(sockets().length).toBe(1);
    });

    it('drops every handler when the socket is re-established', () => {
        const bus = new EventBus(URL);
        bus.enableReconnect(true);
        lastSocket().serverOpen();
        bus.registerHandler('storyteller.plugged', () => {});
        expect(Object.keys(bus.handlers)).toEqual(['storyteller.plugged']);

        lastSocket().serverClose();
        jest.advanceTimersByTime(10000);
        lastSocket().serverOpen();

        // setupSockJSConnection resets handlers to {}. Anything not re-registered is lost silently.
        expect(Object.keys(bus.handlers)).toEqual([]);
    });

    it('never calls onopen when it is assigned after the socket has already opened', () => {
        const bus = new EventBus(URL);
        lastSocket().serverOpen();

        let called = false;
        bus.onopen = () => { called = true; };

        // App.js assigns onopen inside a setState callback, i.e. one React tick after construction.
        // On localhost the socket can win that race, and the global handlers are then never
        // registered for the lifetime of the page.
        expect(called).toBe(false);
    });
});

/* ====================================================================================
 * Target contract: the channel wrapper.
 * ==================================================================================== */

describe('eventBusChannel', () => {

    it('E1: a subscription made on an open channel receives events', () => {
        const channel = createEventBusChannel(URL);
        lastSocket().serverOpen();
        const received = [];

        const outcome = channel.subscribe('storyteller.transfer.abc.done', (err, msg) => received.push(msg.body));

        expect(outcome).toBe(SUBSCRIBED);
        expect(lastSocket().registrations()).toContain('storyteller.transfer.abc.done');

        lastSocket().serverSend('storyteller.transfer.abc.done', { success: true });

        expect(received).toEqual([{ success: true }]);
    });

    it('E2: subscribing before the channel is open defers instead of throwing', () => {
        const channel = createEventBusChannel(URL);
        expect(channel.getState()).toBe(CHANNEL_CONNECTING);
        const received = [];

        // The raw client throws INVALID_STATE_ERR here. The channel must not.
        let outcome;
        expect(() => {
            outcome = channel.subscribe('storyteller.plugged', (err, msg) => received.push(msg.body));
        }).not.toThrow();
        expect(outcome).toBe(DEFERRED);

        lastSocket().serverOpen();

        expect(lastSocket().registrations()).toContain('storyteller.plugged');
        lastSocket().serverSend('storyteller.plugged', { uuid: 'x' });
        expect(received).toEqual([{ uuid: 'x' }]);
    });

    it('E2b: subscribing on a closed channel defers instead of throwing', () => {
        const channel = createEventBusChannel(URL);
        lastSocket().serverOpen();
        lastSocket().serverClose();
        expect(channel.getState()).toBe(CHANNEL_CLOSED);

        let outcome;
        expect(() => {
            outcome = channel.subscribe('storyteller.transfer.abc.done', () => {});
        }).not.toThrow();

        expect(outcome).toBe(DEFERRED);
    });

    it('E3: losing the socket reports a channel state change, never a transfer verdict', () => {
        const states = [];
        const channel = createEventBusChannel(URL, { onStateChange: s => states.push(s) });
        lastSocket().serverOpen();
        const messages = [];
        channel.subscribe('storyteller.transfer.abc.done', (err, msg) => messages.push(msg.body));

        lastSocket().serverClose();

        expect(states).toEqual([CHANNEL_OPEN, CHANNEL_CLOSED]);
        // The channel must never fabricate a completion event out of a transport failure.
        expect(messages).toEqual([]);
        expect(channel.isOpen()).toBe(false);
    });

    it('E4: on reconnect, subscriptions are restored exactly once', () => {
        const channel = createEventBusChannel(URL);
        lastSocket().serverOpen();
        const received = [];
        channel.subscribe('storyteller.plugged', (err, msg) => received.push(msg.body));

        lastSocket().serverClose();
        jest.advanceTimersByTime(10000);
        const reconnected = lastSocket();
        reconnected.serverOpen();

        expect(sockets().length).toBe(2);
        expect(reconnected.registrations()).toEqual(['storyteller.plugged']);

        reconnected.serverSend('storyteller.plugged', { uuid: 'y' });
        expect(received).toEqual([{ uuid: 'y' }]);
    });

    it('E4b: subscribing twice with the same callback registers a single handler', () => {
        const channel = createEventBusChannel(URL);
        lastSocket().serverOpen();
        const callback = jest.fn();

        channel.subscribe('storyteller.plugged', callback);
        channel.subscribe('storyteller.plugged', callback);

        lastSocket().serverSend('storyteller.plugged', { uuid: 'z' });

        expect(callback).toHaveBeenCalledTimes(1);
    });

    it('E7: repeated connect/disconnect cycles do not accumulate handlers', () => {
        const channel = createEventBusChannel(URL);
        lastSocket().serverOpen();
        const callback = jest.fn();
        channel.subscribe('storyteller.plugged', callback);

        for (let i = 0; i < 5; i++) {
            lastSocket().serverClose();
            jest.advanceTimersByTime(10000);
            lastSocket().serverOpen();
        }

        expect(lastSocket().registrations()).toEqual(['storyteller.plugged']);

        lastSocket().serverSend('storyteller.plugged', { uuid: 'w' });
        expect(callback).toHaveBeenCalledTimes(1);
    });

    it('E7b: unsubscribing removes the handler and does not restore it on reconnect', () => {
        const channel = createEventBusChannel(URL);
        lastSocket().serverOpen();
        const callback = jest.fn();
        channel.subscribe('storyteller.transfer.abc.done', callback);

        channel.unsubscribe('storyteller.transfer.abc.done', callback);

        expect(lastSocket().unregistrations()).toContain('storyteller.transfer.abc.done');

        lastSocket().serverClose();
        jest.advanceTimersByTime(10000);
        lastSocket().serverOpen();

        expect(lastSocket().registrations()).toEqual([]);
        expect(callback).not.toHaveBeenCalled();
    });

    it('E7c: unsubscribing on a closed channel is a no-op, not a throw', () => {
        const channel = createEventBusChannel(URL);
        lastSocket().serverOpen();
        const callback = jest.fn();
        channel.subscribe('storyteller.transfer.abc.done', callback);
        lastSocket().serverClose();

        // unregisterHandler on the raw client would throw INVALID_STATE_ERR here.
        expect(() => channel.unsubscribe('storyteller.transfer.abc.done', callback)).not.toThrow();
    });

    it('registers handlers on the very first open, with no assignment race', () => {
        // The channel wires its own callbacks synchronously inside createEventBusChannel, so a
        // socket that opens immediately still triggers the subscription replay.
        const channel = createEventBusChannel(URL);
        channel.subscribe('storyteller.plugged', () => {});

        lastSocket().serverOpen();

        expect(channel.getState()).toBe(CHANNEL_OPEN);
        expect(lastSocket().registrations()).toEqual(['storyteller.plugged']);
    });

    it('reconnection attempts are bounded and stop after the cap', () => {
        const channel = createEventBusChannel(URL, { maxReconnectAttempts: 3 });
        lastSocket().serverOpen();

        for (let i = 0; i < 10; i++) {
            lastSocket().serverClose();
            jest.advanceTimersByTime(30000);
        }

        // 1 initial socket + at most 3 reconnect attempts.
        expect(sockets().length).toBeLessThanOrEqual(4);
        expect(channel.getState()).toBe(CHANNEL_CLOSED);
    });
});
