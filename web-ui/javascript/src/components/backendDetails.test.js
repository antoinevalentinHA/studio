/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * What is worth showing a user out of an Error the backend produced.
 *
 * The driver refuses some operations with a long, deliberate explanation — why the upload was
 * refused, what was left untouched, what STUdio will not do about it. That text already reaches the
 * browser, and until now it went nowhere but a GitHub issue template. This helper decides whether
 * there is anything real to show; the guard matters because an Error is not guaranteed to carry a
 * usable message, and "undefined" in a toast is worse than no detail at all.
 *
 * A pure function, so the decision is testable without rendering anything — the project has no React
 * rendering stack and this lot does not add one.
 */

const { backendDetails } = require('./IssueReportToast');

describe('backendDetails', () => {

    it('returns the message of a real error', () => {
        const message = 'Internal Server Error: A pack content folder already exists on the device'
            + ' partition: /media/lunii/.content/ABCDEF12. It was not created by this operation.';

        expect(backendDetails(new Error(message))).toBe(message);
    });

    it('trims surrounding whitespace rather than showing a padded block', () => {
        expect(backendDetails(new Error('  Internal Server Error: refused  '))).toBe(
            'Internal Server Error: refused');
    });

    it('returns null when there is no error at all', () => {
        expect(backendDetails(undefined)).toBeNull();
        expect(backendDetails(null)).toBeNull();
    });

    it('returns null rather than the string "undefined" when the message is missing', () => {
        // The case that would put the word "undefined" in front of a user.
        expect(backendDetails({})).toBeNull();
        expect(backendDetails({ message: undefined })).toBeNull();
        expect(backendDetails({ message: null })).toBeNull();
    });

    it('returns null for an empty or blank message', () => {
        expect(backendDetails(new Error(''))).toBeNull();
        expect(backendDetails(new Error('   '))).toBeNull();
        expect(backendDetails(new Error('\n\t '))).toBeNull();
    });

    it('returns null for a message that is not a string', () => {
        expect(backendDetails({ message: 42 })).toBeNull();
        expect(backendDetails({ message: {} })).toBeNull();
    });
});
