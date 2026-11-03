// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useLocaleStore } from '../../i18n';
import MailboxUsage from './MailboxUsage';

describe('MailboxUsage', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('renders API values and supports unlimited quota without a fake percentage', async () => {
    await act(async () => root.render(<MailboxUsage
      quota={{ usedBytes: 2 * 1024 ** 3, quotaBytes: 4 * 1024 ** 3, messageCount: 8 }}
      unavailable={false}
    />));
    expect(container.textContent).toContain('2.0 GB of 4.0 GB used');
    expect(container.querySelector('[role="progressbar"]')?.getAttribute('aria-valuenow')).toBe('50');

    await act(async () => root.render(<MailboxUsage
      quota={{ usedBytes: 1024 ** 3, quotaBytes: 0, messageCount: 3 }}
      unavailable={false}
    />));
    expect(container.textContent).toContain('1.0 GB used · unlimited');
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
  });

  it('does not invent zero or demo values when usage is unavailable', async () => {
    await act(async () => root.render(<MailboxUsage quota={null} unavailable />));

    expect(container.textContent).toContain('Could not load actual mailbox usage.');
    expect(container.textContent).not.toContain('5.0 GB');
    expect(container.querySelector('[role="progressbar"]')).toBeNull();
  });
});
