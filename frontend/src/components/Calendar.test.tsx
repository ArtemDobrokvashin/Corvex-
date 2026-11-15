// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { describe, expect, it } from 'vitest';
import { useLocaleStore } from '../i18n';
import Calendar from './Calendar';

describe('Calendar month grid invariants', () => {
  it('renders a table with td cells, highlights today with bg-accent-soft, switches months, and avoids forbidden words', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'ru' });
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    await act(async () => {
      root.render(<Calendar />);
    });

    expect(container.querySelector('table')).not.toBeNull();
    expect(container.querySelectorAll('td').length).toBeGreaterThanOrEqual(28);
    expect(container.querySelector('.bg-accent-soft')).not.toBeNull();

    const prev = container.querySelector('button[aria-label="Предыдущий месяц"]') as HTMLButtonElement;
    const next = container.querySelector('button[aria-label="Следующий месяц"]') as HTMLButtonElement;
    expect(prev).not.toBeNull();
    expect(next).not.toBeNull();

    const initialHeading = container.textContent;
    await act(async () => next.click());
    expect(container.textContent).not.toBe(initialHeading);
    await act(async () => prev.click());

    expect(container.textContent).not.toMatch(/уведом|server|API|Сервер/i);

    await act(async () => root.unmount());
    container.remove();
  });
});
