/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * What may reach the page through the two HTML sinks, and what may not.
 *
 * The announce dialog renders Markdown fetched from GitHub with `dangerouslySetInnerHTML`, and a few
 * dialogs do the same with HTML from the translation bundles. Before this helper existed the Markdown
 * went through an end-of-life `marked` and nothing was sanitized: whoever could change the upstream
 * ANNOUNCE.md, or sit between the user and raw.githubusercontent.com, could run script in every
 * STUdio user's browser.
 *
 * The positive cases matter as much as the payloads: a sanitizer that also strips the markup the
 * dialogs rely on would pass every "no script" assertion while breaking the help pages.
 */

import { renderMarkdown, sanitizeHtml } from './html';

describe('renderMarkdown', () => {

    it('renders ordinary announce Markdown', () => {
        const html = renderMarkdown('### Good news\n\nSTUdio is **improving**, see [the release](https://example.org/r).\n\n- one\n- two');
        expect(html).toContain('<h3');
        expect(html).toContain('<strong>improving</strong>');
        expect(html).toContain('<a href="https://example.org/r">the release</a>');
        expect(html).toContain('<li>one</li>');
    });

    it('drops a script block', () => {
        const html = renderMarkdown('hello\n\n<script>alert(1)</script>\n\nworld');
        expect(html).not.toContain('<script');
        expect(html).not.toContain('alert(1)');
        expect(html).toContain('hello');
        expect(html).toContain('world');
    });

    it('drops event handlers on inline HTML', () => {
        const html = renderMarkdown('<img src=x onerror="alert(1)"> <p onclick="alert(2)">text</p>');
        expect(html).not.toContain('onerror');
        expect(html).not.toContain('onclick');
        expect(html).toContain('text');
    });

    it('drops javascript: links, whether written as Markdown or as HTML', () => {
        const html = renderMarkdown('[click](javascript:alert(1)) <a href="javascript:alert(2)">me</a>');
        expect(html).not.toContain('javascript:');
        expect(html).toContain('click');
        expect(html).toContain('me');
    });

    it('drops iframes and forms', () => {
        const html = renderMarkdown('<iframe src="https://evil.example"></iframe><form action="https://evil.example"><input name="pw"></form>');
        expect(html).not.toContain('<iframe');
        expect(html).not.toContain('<form');
        expect(html).not.toContain('<input');
    });

    it('renders nothing for a missing announce rather than throwing', () => {
        expect(renderMarkdown(null)).toBe('');
        expect(renderMarkdown(undefined)).toBe('');
    });
});

describe('sanitizeHtml', () => {

    it('keeps the markup the translation bundles use', () => {
        const html = sanitizeHtml('<p>Use <strong>drag and drop</strong> <span class="glyphicon glyphicon-play"></span> <em>now</em></p><ul><li>a</li></ul>');
        expect(html).toBe('<p>Use <strong>drag and drop</strong> <span class="glyphicon glyphicon-play"></span> <em>now</em></p><ul><li>a</li></ul>');
    });

    it('drops script and event handlers', () => {
        const html = sanitizeHtml('<p onmouseover="alert(1)">x</p><script>alert(2)</script>');
        expect(html).toBe('<p>x</p>');
    });

    it('renders nothing for a missing string rather than throwing', () => {
        expect(sanitizeHtml(null)).toBe('');
        expect(sanitizeHtml(undefined)).toBe('');
    });
});
