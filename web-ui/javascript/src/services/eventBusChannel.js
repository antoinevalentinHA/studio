/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import EventBus from 'vertx3-eventbus-client';

/*
 * A thin, honest wrapper around vertx3-eventbus-client.
 *
 * Three properties of the underlying client make it unsafe to call directly from application code,
 * all of them verified against the library source in eventBusChannel.test.js:
 *
 *   1. registerHandler / unregisterHandler / send throw Error('INVALID_STATE_ERR') whenever the
 *      socket is not OPEN. Called from inside a promise chain, that throw is indistinguishable from
 *      a backend failure — which is how a successful transfer came to be reported as a failed one.
 *   2. Reconnection is off by default and is NOT enabled by passing options; only an explicit
 *      enableReconnect(true) turns it on.
 *   3. Every handler is discarded when the socket is re-established, so anything not re-registered
 *      after a reconnect is silently lost.
 *
 * The channel owns the connection lifecycle and a registry of *desired* subscriptions. Subscribing
 * is always safe: if the socket is not open the subscription is recorded and applied as soon as it
 * is. Callers therefore never have to reason about socket state, and a dropped socket can no longer
 * masquerade as an application error.
 *
 * What this deliberately does NOT do: infer anything about a transfer. Losing the channel says
 * nothing about what the backend is doing, and the channel never synthesises a completion event.
 */

export const CHANNEL_CONNECTING = 'connecting';
export const CHANNEL_OPEN = 'open';
export const CHANNEL_CLOSED = 'closed';

/** The subscription is active on the wire right now. */
export const SUBSCRIBED = 'subscribed';
/** The subscription is recorded and will be applied on the next open. Not an error. */
export const DEFERRED = 'deferred';

const DEFAULT_MAX_RECONNECT_ATTEMPTS = 60;
const DEFAULT_RECONNECT_DELAY_MIN = 1000;
const DEFAULT_RECONNECT_DELAY_MAX = 5000;

export function createEventBusChannel(url, options = {}) {
    const onStateChange = options.onStateChange || (() => {});
    const maxReconnectAttempts = options.maxReconnectAttempts || DEFAULT_MAX_RECONNECT_ATTEMPTS;

    // address -> Set of callbacks the application wants registered whenever the socket is open.
    const desired = new Map();
    let state = CHANNEL_CONNECTING;

    const bus = new EventBus(url, {
        vertxbus_reconnect_attempts_max: maxReconnectAttempts,
        vertxbus_reconnect_delay_min: options.reconnectDelayMin || DEFAULT_RECONNECT_DELAY_MIN,
        vertxbus_reconnect_delay_max: options.reconnectDelayMax || DEFAULT_RECONNECT_DELAY_MAX
    });

    // Assigned synchronously, before the socket has had any chance to complete its handshake.
    // Assigning onopen later — for instance from a React setState callback — loses the event
    // outright, because the client calls it at most once and only if it is already set.
    bus.onopen = () => {
        state = CHANNEL_OPEN;
        console.log('event bus channel open');
        replayDesiredSubscriptions();
        onStateChange(CHANNEL_OPEN);
    };

    bus.onclose = () => {
        const wasOpen = state === CHANNEL_OPEN;
        state = CHANNEL_CLOSED;
        if (wasOpen) {
            console.warn('event bus channel closed; transfers in progress are unaffected');
        }
        onStateChange(CHANNEL_CLOSED);
    };

    bus.enableReconnect(true);

    function replayDesiredSubscriptions() {
        // The client resets its handler table on every new socket, so everything must be
        // re-registered. `desired` is the single source of truth, which is what keeps this
        // idempotent across any number of reconnections.
        desired.forEach((callbacks, address) => {
            callbacks.forEach(callback => {
                try {
                    bus.registerHandler(address, callback);
                } catch (e) {
                    console.warn('failed to restore subscription to ' + address, e);
                }
            });
        });
    }

    function subscribe(address, callback) {
        let callbacks = desired.get(address);
        if (!callbacks) {
            callbacks = new Set();
            desired.set(address, callbacks);
        }
        if (callbacks.has(callback)) {
            // Already recorded: registering again would deliver every message twice.
            return state === CHANNEL_OPEN ? SUBSCRIBED : DEFERRED;
        }
        callbacks.add(callback);

        if (state !== CHANNEL_OPEN) {
            return DEFERRED;
        }
        try {
            bus.registerHandler(address, callback);
            return SUBSCRIBED;
        } catch (e) {
            // The socket closed between the state check and the call. Keep the subscription
            // recorded so the next open picks it up, and report it as deferred rather than failed.
            console.warn('subscription to ' + address + ' deferred: ' + e.message);
            return DEFERRED;
        }
    }

    function unsubscribe(address, callback) {
        const callbacks = desired.get(address);
        if (!callbacks || !callbacks.has(callback)) {
            return;
        }
        callbacks.delete(callback);
        if (callbacks.size === 0) {
            desired.delete(address);
        }
        if (state !== CHANNEL_OPEN) {
            // Nothing to unregister on the wire, and the raw call would throw. Dropping it from
            // `desired` is enough: it will simply not be replayed.
            return;
        }
        try {
            bus.unregisterHandler(address, callback);
        } catch (e) {
            console.warn('failed to unsubscribe from ' + address, e);
        }
    }

    function close() {
        try {
            bus.close();
        } catch (e) {
            console.warn('failed to close the event bus channel', e);
        }
        state = CHANNEL_CLOSED;
        desired.clear();
    }

    return {
        subscribe,
        unsubscribe,
        close,
        getState: () => state,
        isOpen: () => state === CHANNEL_OPEN,
        /** Exposed for diagnostics only; application code should not touch the raw client. */
        rawBus: () => bus
    };
}
