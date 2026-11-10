// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { mailHtmlDocument } from './mailHtml';

describe('mail HTML isolation', () => {
  it('keeps formatted text while removing active content and navigation', () => {
    const html = mailHtmlDocument('<h2>Заголовок</h2><strong>Текст</strong><script>alert(1)</script><a href="javascript:alert(1)" onclick="alert(2)">link</a><form action="https://evil.test"><input></form>');
    expect(html).toContain('<h2>Заголовок</h2>');
    expect(html).toContain('<strong>Текст</strong>');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('onclick');
    expect(html).not.toContain('href=');
    expect(html).not.toContain('<form');
  });
  it('enforces an offline CSP even if the message contains a tracking image or stylesheet', () => {
    const html = mailHtmlDocument('<img src="https://evil.test/tracking"><style>@import url(https://evil.test)</style><iframe src="https://evil.test"></iframe>');
    expect(html).toContain("default-src 'none'; img-src data:");
    expect(html).not.toContain('@import');
    expect(html).not.toContain('<iframe');
  });
});
