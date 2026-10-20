// @vitest-environment jsdom

import { describe, expect, it } from 'vitest';
import { renderChatMarkdown } from './markdown';

describe('renderChatMarkdown', () => {
  it('renders the Markdown used by chat messages', () => {
    const { __html } = renderChatMarkdown('**Важно:** [документ](https://example.com/doc) и `код`');

    expect(__html).toContain('<strong>Важно:</strong>');
    expect(__html).toContain('href="https://example.com/doc"');
    expect(__html).toContain('<code>код</code>');
  });

  it('removes scripts, event handlers and javascript URLs', () => {
    const { __html } = renderChatMarkdown(
      '<script>alert(1)</script><a href="javascript:alert(1)" onclick="alert(2)">link</a>',
    );

    expect(__html).not.toContain('<script');
    expect(__html).not.toContain('onclick');
    expect(__html).not.toContain('javascript:');
  });

  it('forbids external media and inline styles', () => {
    const { __html } = renderChatMarkdown(
      '![pixel](https://example.com/pixel.png)<iframe src="https://example.com"></iframe><p style="color:red">text</p>',
    );

    expect(__html).not.toContain('<img');
    expect(__html).not.toContain('<iframe');
    expect(__html).not.toContain('style=');
  });
});
