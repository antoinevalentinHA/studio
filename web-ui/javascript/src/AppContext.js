/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';


export const AppContext = React.createContext({
    // The event bus channel (see services/eventBusChannel). Never the raw vertx client: callers
    // must not have to reason about socket state, nor be able to trigger INVALID_STATE_ERR.
    channel: null
});
