// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';

const apiMocks = vi.hoisted(() => ({ settings: vi.fn(), patchSettings: vi.fn() }));

vi.mock('../../api/adminApi', () => ({
  adminApi: { settings: apiMocks.settings, patchSettings: apiMocks.patchSettings },
}));

import SettingsTab from './SettingsTab';

const values = {
  'mail.maxMessageSizeMb': '25',
  'mail.defaultQuotaBytes': '5368709120',
  'mail.spamThreshold': '5.0',
  'mail.pop3.enabled': 'true',
  'security.passwordMinLength': '10',
  'security.requireTotp': 'false',
  'chat.maxFileSizeMb': '25',
};

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

describe('admin settings registry', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    apiMocks.settings.mockReset().mockResolvedValue(values);
    apiMocks.patchSettings.mockReset().mockImplementation(async (patch) => ({ ...values, ...patch }));
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    useLocaleStore.setState({ locale: 'ru' });
  });

  it('loads existing keys and saves only changes with an explicit non-runtime notice', async () => {
    await act(async () => {
      root.render(<SettingsTab demoMode={false} />);
      await Promise.resolve();
    });

    expect(apiMocks.settings).toHaveBeenCalledTimes(1);
    expect(container.textContent).toContain('Server settings registry');
    expect(container.textContent).toContain('running services do not currently read these keys automatically');

    const input = container.querySelector<HTMLInputElement>('input[aria-label="Maximum message size"]')!;
    await act(async () => setInput(input, '50'));
    const save = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Save to registry')!;
    expect(save.disabled).toBe(false);

    await act(async () => {
      save.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await Promise.resolve();
    });

    expect(apiMocks.patchSettings).toHaveBeenCalledWith({ 'mail.maxMessageSizeMb': '50' });
    expect(container.textContent).toContain('This action did not change service runtime configuration.');
    expect(apiMocks.patchSettings.mock.calls[0][0]).not.toHaveProperty('security.requireTotp');
  });
});
