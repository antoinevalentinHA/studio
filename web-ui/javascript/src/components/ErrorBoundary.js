/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';

/*
 * The last line of defence around the application: React 16 unmounts the whole tree when a render
 * error goes uncaught, which the user sees as a blank page with no way back. This shows what
 * happened and offers a reload. It deliberately depends on nothing — no i18n, no store — since
 * either of those may be what failed; the two sentences are given in both languages instead.
 */
class ErrorBoundary extends React.Component {

    constructor(props) {
        super(props);
        this.state = { error: null };
    }

    static getDerivedStateFromError(error) {
        return { error };
    }

    componentDidCatch(error, info) {
        console.error('Unrecoverable render error', error, info && info.componentStack);
    }

    render() {
        if (this.state.error) {
            const message = (this.state.error && this.state.error.message) || String(this.state.error);
            return (
                <div className="error-boundary" style={{ padding: '2em', fontFamily: 'sans-serif' }}>
                    <h2>Something went wrong / Une erreur est survenue</h2>
                    <p>STUdio hit an error it could not recover from. Reloading the page is safe: nothing on the device is affected by a display error.</p>
                    <p>STUdio a rencontré une erreur dont il ne peut pas se remettre. Recharger la page est sans risque : rien sur l'appareil n'est affecté par une erreur d'affichage.</p>
                    <pre style={{ whiteSpace: 'pre-wrap' }}>{message}</pre>
                    <button onClick={() => window.location.reload()}>Reload / Recharger</button>
                </div>
            );
        }
        return this.props.children;
    }
}

export default ErrorBoundary;
