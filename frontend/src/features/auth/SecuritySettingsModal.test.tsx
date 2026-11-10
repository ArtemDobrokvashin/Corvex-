// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
const mocks = vi.hoisted(() => ({ setup: vi.fn(), list: vi.fn() }));
vi.mock('../../api/authApi', () => ({
  totpSetup: mocks.setup, listAppPasswords: mocks.list,
  errorText: () => 'error', changePassword: vi.fn(), createAppPassword: vi.fn(), deleteAppPassword: vi.fn(), totpConfirm: vi.fn(), totpDisable: vi.fn(),
}));
import SecuritySettingsModal from './SecuritySettingsModal';

describe('TOTP setup', () => {
  it('renders a locally generated QR code after setup', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'en' });
    useSession.getState().setSession({ id: 'id', displayName: 'Test', email: 'test@example.test', groups: ['USER'], permissions: [] }, false);
    mocks.list.mockResolvedValue([]);
    mocks.setup.mockResolvedValue({ secret: 'TESTSECRET', otpauthUri: 'otpauth://totp/Corvex:test?secret=TESTSECRET' });
    const node = document.createElement('div'); document.body.appendChild(node);
    const root = createRoot(node);
    await act(async () => { root.render(<SecuritySettingsModal onClose={() => {}} />); await Promise.resolve(); });
    const button = Array.from(node.querySelectorAll('button')).find((b) => b.textContent?.includes('Set up an authenticator'));
    expect(button).toBeDefined();
    await act(async () => { button!.click(); await Promise.resolve(); });
    expect(mocks.setup).toHaveBeenCalledOnce();
    expect(node.querySelector('[aria-label="QR code for setting up 2FA"] svg')).not.toBeNull();
    expect(node.querySelector('img')).toBeNull();
    await act(async () => root.unmount()); node.remove(); useSession.getState().clearSession(); useLocaleStore.setState({ locale: 'ru' });
  });
});
