// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';

const mocks = vi.hoisted(() => ({ changePassword: vi.fn(), errorText: vi.fn() }));

vi.mock('../../api/authApi', () => ({
  changePassword: mocks.changePassword,
  errorText: mocks.errorText,
}));

import ChangePasswordModal from './ChangePasswordModal';

function setInput(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/**
 * Принудительная смена временного пароля не должна требовать его повторно:
 * человек ввёл его на экране входа минуту назад. Сервер принимает такую смену,
 * пока аккаунт в состоянии mustChangePassword.
 */
describe('ChangePasswordModal', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'ru' });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    mocks.changePassword.mockReset().mockResolvedValue(undefined);
    mocks.errorText.mockReset().mockReturnValue('error');
  });

  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
  });

  it('does not ask for the temporary password', async () => {
    await act(async () => {
      root.render(<ChangePasswordModal onChanged={vi.fn()} />);
    });

    expect(container.textContent).not.toContain('Временный пароль');
    const labels = Array.from(container.querySelectorAll('label')).map((label) => label.textContent);
    expect(labels).toEqual(['Новый пароль', 'Повторите пароль']);
  });

  it('sends the new password without the current one', async () => {
    const onChanged = vi.fn();
    await act(async () => {
      root.render(<ChangePasswordModal onChanged={onChanged} />);
    });

    const [next, repeat] = Array.from(container.querySelectorAll<HTMLInputElement>('input'));
    await act(async () => {
      setInput(next, 'NewPassword2');
      setInput(repeat, 'NewPassword2');
    });
    await act(async () => {
      container.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      await Promise.resolve();
    });

    expect(mocks.changePassword).toHaveBeenCalledWith('', 'NewPassword2');
    expect(onChanged).toHaveBeenCalled();
  });

  it('reports mismatched repeats without calling the API', async () => {
    await act(async () => {
      root.render(<ChangePasswordModal onChanged={vi.fn()} />);
    });
    const [next, repeat] = Array.from(container.querySelectorAll<HTMLInputElement>('input'));
    await act(async () => {
      setInput(next, 'NewPassword2');
      setInput(repeat, 'Different3');
    });
    await act(async () => {
      container.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      await Promise.resolve();
    });

    expect(mocks.changePassword).not.toHaveBeenCalled();
    expect(container.textContent).toContain('Новые пароли не совпадают');
  });
});
