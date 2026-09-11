/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * A render error anywhere used to unmount the whole tree: React 16 blanks the page when nothing
 * catches. The boundary sits at the root and shows a message instead. It is rendered with
 * react-dom into jsdom directly — the project has no component testing library and this does not
 * add one.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import ErrorBoundary from './ErrorBoundary';

function Bomb() {
    throw new Error('boom');
}

describe('ErrorBoundary', () => {
    let container;
    let consoleError;

    beforeEach(() => {
        container = document.createElement('div');
        document.body.appendChild(container);
        // React reports the caught error on console.error; that is expected here, not noise to fail on.
        consoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
    });

    afterEach(() => {
        ReactDOM.unmountComponentAtNode(container);
        container.remove();
        consoleError.mockRestore();
    });

    it('renders its children when nothing throws', () => {
        ReactDOM.render(<ErrorBoundary><p>fine</p></ErrorBoundary>, container);
        expect(container.textContent).toBe('fine');
    });

    it('shows a message instead of a blank page when a child throws', () => {
        ReactDOM.render(<ErrorBoundary><Bomb /></ErrorBoundary>, container);
        expect(container.querySelector('.error-boundary')).not.toBeNull();
        expect(container.textContent).toContain('boom');
    });
});
