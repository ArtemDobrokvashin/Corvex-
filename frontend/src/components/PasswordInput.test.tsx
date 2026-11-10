// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it } from 'vitest';
import PasswordInput from './PasswordInput';

describe('password visibility', () => {
  it('starts hidden and toggles without submitting the form', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);
    await act(async () => root.render(<form><PasswordInput name="password" defaultValue="secret" /></form>));
    const input = container.querySelector('input')!;
    const button = container.querySelector('button')!;
    expect(input.type).toBe('password');
    expect(button.type).toBe('button');
    await act(async () => button.click());
    expect(input.type).toBe('text');
    expect(input.value).toBe('secret');
    await act(async () => button.click());
    expect(input.type).toBe('password');
    await act(async () => root.unmount());
    container.remove();
  });
});
