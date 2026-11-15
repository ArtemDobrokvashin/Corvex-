// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../i18n';
import { useCalendarStore } from '../store/calendarStore';
import { useSession } from '../store/sessionStore';
import CalendarSidebarWidget, { CORVEX_MAIL_DND_MIME } from './CalendarSidebarWidget';

let container: HTMLDivElement;
let root: Root;

function fireDrag(element: Element, type: string, payload: string) {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'dataTransfer', { value: {
    types: [CORVEX_MAIL_DND_MIME],
    getData: () => payload,
    dropEffect: 'none',
  } });
  element.dispatchEvent(event);
}

function setValue(element: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
  element.dispatchEvent(new Event('change', { bubbles: true }));
}

beforeEach(async () => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  localStorage.clear();
  useLocaleStore.setState({ locale: 'en' });
  useCalendarStore.getState().setOwner(null);
  useCalendarStore.getState().setOwner('test');
  useCalendarStore.getState().setSelectedDate('2026-01-12');
  useSession.getState().setSession({ id: 'test', displayName: 'Test', email: 'test@example.com', groups: [], permissions: [] }, false);
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => root.render(<MemoryRouter><CalendarSidebarWidget sidebarOffsetPx={0} onClose={vi.fn()} /></MemoryRouter>));
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  useCalendarStore.getState().setOwner(null);
  useSession.getState().clearSession();
  localStorage.clear();
});

describe('My day email drop and quick add', () => {
  it('opens an editable draft linked to the email on the selected day', async () => {
    const widget = container.querySelector('aside[aria-label="Calendar widget"]')!;
    const payload = JSON.stringify({ id: 'm1', subject: 'Re: Budget', from: 'Alice <alice@example.com>', body: 'Please meet tomorrow at 14:30', flagged: true });
    await act(async () => fireDrag(widget, 'dragover', payload));
    const hint = Array.from(widget.querySelectorAll('div')).find((item) => item.textContent?.includes('Drop the email to schedule an event') && item.className.includes('pointer-events-none'));
    expect(hint).toBeTruthy();
    await act(async () => fireDrag(widget, 'drop', payload));
    expect((container.querySelector('#event-title') as HTMLInputElement).value).toBe('Budget');
    expect((container.querySelector('#event-date') as HTMLInputElement).value).toBe('2026-01-12');
    expect((container.querySelector('#event-start') as HTMLInputElement).value).toBe('14:30');
    expect(container.querySelector('[role="dialog"]')?.textContent).toContain('alice@example.com');
    expect(useCalendarStore.getState().events).toHaveLength(0);
  });

  it('keeps one drop target while crossing children and removes it only when leaving the widget', async () => {
    const widget = container.querySelector('aside[aria-label="Calendar widget"]')!;
    vi.spyOn(widget, 'getBoundingClientRect').mockReturnValue({ left: 0, top: 0, right: 100, bottom: 100, width: 100, height: 100, x: 0, y: 0, toJSON: () => ({}) });
    const payload = JSON.stringify({ id: 'm1', subject: 'Budget', from: 'alice@example.com', body: '' });
    await act(async () => fireDrag(widget, 'dragenter', payload));
    const overlay = () => widget.querySelector('[aria-hidden="true"].absolute.inset-0');
    expect(overlay()).not.toBeNull();
    const leave = (x: number, y: number) => {
      const event = new MouseEvent('dragleave', { bubbles: true, clientX: x, clientY: y });
      overlay()!.dispatchEvent(event);
    };
    await act(async () => leave(50, 50));
    expect(overlay()).not.toBeNull();
    await act(async () => leave(101, 50));
    expect(overlay()).toBeNull();
    await act(async () => fireDrag(widget, 'dragenter', payload));
    await act(async () => fireDrag(overlay()!, 'drop', payload));
    expect((container.querySelector('#event-title') as HTMLInputElement).value).toBe('Budget');
  });

  it('clears the drop overlay when a native drag is cancelled over the widget', async () => {
    const widget = container.querySelector('aside[aria-label="Calendar widget"]')!;
    const payload = JSON.stringify({ id: 'm1', subject: 'Budget', from: 'alice@example.com', body: '' });
    await act(async () => fireDrag(widget, 'dragenter', payload));
    expect(widget.querySelector('[aria-hidden="true"].absolute.inset-0')).not.toBeNull();
    // dragend originates on the mail row, not inside the widget.
    await act(async () => document.body.dispatchEvent(new Event('dragend', { bubbles: true })));
    expect(widget.querySelector('[aria-hidden="true"].absolute.inset-0')).toBeNull();
    expect(container.querySelector('[role="dialog"]')).toBeNull();
  });

  it('rejects malformed payloads and an empty quick-add time', async () => {
    const widget = container.querySelector('aside[aria-label="Calendar widget"]')!;
    await act(async () => fireDrag(widget, 'drop', '{bad json'));
    expect(container.querySelector('[role="dialog"]')).toBeNull();
    const title = container.querySelector('input[placeholder="Meeting or task title…"]') as HTMLInputElement;
    const time = container.querySelector('input[aria-label="Start time"]') as HTMLInputElement;
    await act(async () => { setValue(title, 'Quick meeting'); setValue(time, ''); });
    expect((container.querySelector('button[aria-label="Add event"]') as HTMLButtonElement).disabled).toBe(true);
    expect(container.textContent).toContain('Choose a start time before 23:59.');
    expect(useCalendarStore.getState().events).toHaveLength(0);
  });
});
