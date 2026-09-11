/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// The UMD build by name: marked 4 points its `main` at a `.cjs` file, which the jest of
// react-scripts 3 hands to its catch-all file transform (anything not .js/.jsx/.ts/.tsx/.css/.json
// becomes a filename string) — and its config cannot be overridden without ejecting. The UMD file
// is plain .js, loads under both webpack 4 and jest 24, and is the same source. Drop this the day
// react-scripts is upgraded.
import { marked } from 'marked/lib/marked.umd.js';
import DOMPurify from 'dompurify';

/*
 * The single door for raw HTML into the page.
 *
 * Everything that ends up in a `dangerouslySetInnerHTML` goes through here. The announce is fetched
 * from GitHub (an upstream repository this fork does not control, over a connection that could be
 * intercepted), and the translation bundles are served as data rather than compiled in: neither is
 * something the browser should execute script from. DOMPurify keeps the markup that is meant to be
 * there — paragraphs, lists, emphasis, links, the glyphicon spans of the help dialogs — and drops
 * scripts, event handlers and `javascript:` URLs.
 */

// DOMPurify keeps form controls by default, since they carry no script. Neither the announce nor a
// translation has any business asking the user to type something, and a form in a dialog fetched
// from the network is a phishing prompt, not content.
const SANITIZE_OPTIONS = {
    FORBID_TAGS: ['form', 'input', 'textarea', 'select', 'button']
};

export function sanitizeHtml(html) {
    return DOMPurify.sanitize(html == null ? '' : String(html), SANITIZE_OPTIONS);
}

export function renderMarkdown(markdown) {
    return sanitizeHtml(marked(markdown == null ? '' : String(markdown)));
}
