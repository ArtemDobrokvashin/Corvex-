// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { mailHtmlDocument, sanitizeMailHtml } from './mailHtml';

const nonce = '1234567890abcdef1234567890abcdef';
describe('mail HTML isolation', () => {
  it('keeps formatted text while removing active content and navigation', () => {
    const html = sanitizeMailHtml('<h2>Заголовок</h2><strong>Текст</strong><script>alert(1)</script><a href="javascript:alert(1)" onclick="alert(2)">link</a><form action="https://evil.test"><input></form>', 'Blocked');
    expect(html).toContain('<h2>Заголовок</h2>'); expect(html).toContain('<strong>Текст</strong>');
    expect(html).not.toContain('<script'); expect(html).not.toContain('onclick'); expect(html).not.toContain('href='); expect(html).not.toContain('<form');
  });
  it('replaces blocked remote and CID images with readable labels and retains embedded raster data', () => {
    const html = sanitizeMailHtml('<img width="800" height="700" src="https://evil.test/tracking" alt="Banner"><img src="cid:foo"><img src="data:image/png;base64,YWJj">', 'Blocked');
    expect(html).toContain('>Banner</span>'); expect(html).toContain('>Blocked</span>');
    expect(html).not.toContain('evil.test'); expect(html).not.toContain('height="700"'); expect(html).not.toContain('cid:foo');
    expect(html).toContain('data:image/png;base64,YWJj');
  });
  it('keeps the network offline and allows only its nonce-bearing resize script', () => {
    const html = mailHtmlDocument('<img src="https://evil.test/tracking"><style>@import url(https://evil.test)</style><iframe src="https://evil.test"></iframe><script>alert(1)</script>', nonce, 'Blocked');
    expect(html).toContain("default-src 'none'; img-src data:"); expect(html).toContain(`script-src 'nonce-${nonce}'`);
    expect(html).not.toContain('@import'); expect(html).not.toContain('alert(1)'); expect(html).not.toContain('<iframe');
    expect(() => mailHtmlDocument('', "x' unsafe-inline", 'Blocked')).toThrow();
  });
});
