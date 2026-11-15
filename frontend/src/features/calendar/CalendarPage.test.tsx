// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import { buildEmptyEventDraft, formatIsoDate, useCalendarStore } from '../../store/calendarStore';
import { useSession } from '../../store/sessionStore';
import CalendarPage from './CalendarPage';
import EventModal from './EventModal';

let container: HTMLDivElement;
let root: Root;

async function click(element: Element | null | undefined) {
  expect(element).toBeTruthy();
  await act(async () => { (element as HTMLElement).click(); await Promise.resolve(); });
}

function setValue(element: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
  element.dispatchEvent(new Event('change', { bubbles: true }));
}

async function submit(form: HTMLFormElement) {
  await act(async () => { form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
  useCalendarStore.getState().setOwner(null);
  useCalendarStore.getState().setOwner('test');
  useCalendarStore.setState({ viewMode: 'month', layers: { high: true, medium: true, low: true, fromMail: true } });
  useSession.getState().setSession({ id: 'test', displayName: 'Test', email: 'test@example.com', groups: [], permissions: [] }, false);
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  useCalendarStore.getState().setOwner(null);
  useSession.getState().clearSession();
  localStorage.clear();
});

describe('calendar editing', () => {
  it('edits the original recurring series and asks for confirmation before deleting it', async () => {
    const series = useCalendarStore.getState().upsertEvent({
      ...buildEmptyEventDraft('2026-01-05'), title: 'Weekly series', recurrence: 'weekly',
    });
    await act(async () => root.render(<MemoryRouter initialEntries={['/calendar?date=2026-01-12']}><CalendarPage /></MemoryRouter>));
    const occurrenceButton = Array.from(container.querySelectorAll('button')).find((button) => button.textContent?.includes('Weekly series'));
    await click(occurrenceButton);
    const modal = container.querySelector('[role="dialog"][aria-label="Edit event"]')!;
    expect(modal).not.toBeNull();
    expect(modal.textContent).toContain('editing the entire series');
    expect((modal.querySelector('#event-date') as HTMLInputElement).value).toBe('2026-01-05');
    const title = modal.querySelector('#event-title') as HTMLInputElement;
    await act(async () => setValue(title, 'Updated series'));
    await submit(modal.querySelector('form')!);
    expect(useCalendarStore.getState().events.find((item) => item.id === series.id)).toMatchObject({ title: 'Updated series', date: '2026-01-05', recurrence: 'weekly' });

    const deleteButton = container.querySelector('button[aria-label="Delete: Updated series"]');
    await click(deleteButton);
    const confirm = container.querySelector('[role="alertdialog"]')!;
    expect(confirm.textContent).toContain('Delete the entire recurring series?');
    expect(useCalendarStore.getState().events).toHaveLength(1);
    await click(Array.from(confirm.querySelectorAll('button')).find((button) => button.textContent === 'Cancel'));
    expect(useCalendarStore.getState().events).toHaveLength(1);
    await click(deleteButton);
    await click(Array.from(container.querySelectorAll('[role="alertdialog"] button')).find((button) => button.textContent === 'Delete'));
    expect(useCalendarStore.getState().events).toHaveLength(0);
  });

  it('does not reset a new selection back to the date in a deep link', async () => {
    await act(async () => root.render(<MemoryRouter initialEntries={['/calendar?date=2026-01-12']}><CalendarPage /></MemoryRouter>));
    expect(useCalendarStore.getState().selectedDate).toBe('2026-01-12');
    await click(Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Today'));
    expect(useCalendarStore.getState().selectedDate).toBe(formatIsoDate(new Date()));
  });

  it('prevents invalid times and gives all-day events safe hidden times', async () => {
    const save = vi.fn();
    const draft = { ...buildEmptyEventDraft('2026-01-05'), title: 'Meeting', endTime: '09:00' };
    await act(async () => root.render(<EventModal initial={draft} onClose={() => undefined} onSave={save} />));
    const form = container.querySelector('form')!;
    const saveButton = Array.from(form.querySelectorAll('button')).find((button) => button.textContent === 'Save event')!;
    expect(saveButton.disabled).toBe(true);
    await submit(form);
    expect(save).not.toHaveBeenCalled();
    await act(async () => setValue(container.querySelector('#event-end') as HTMLInputElement, '11:00'));
    expect(saveButton.disabled).toBe(false);
    await submit(form);
    expect(save).toHaveBeenCalledWith(expect.objectContaining({ startTime: '10:00', endTime: '11:00' }));

    await act(async () => root.render(<EventModal key="all-day" initial={{ ...draft, allDay: true, startTime: '', endTime: '' }} onClose={() => undefined} onSave={save} />));
    await submit(container.querySelector('form')!);
    expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ allDay: true, startTime: '09:00', endTime: '10:00' }));
  });
});
