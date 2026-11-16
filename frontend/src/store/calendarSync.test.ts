// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { ApiError } from '../api/httpClient';
import type { CalendarEventSave, CalendarEventView } from '../api/calendarApi';
import {
  buildEmptyEventDraft, CALENDAR_STORAGE_KEY, setCalendarTransportForTests, useCalendarStore,
} from './calendarStore';

const SERVER_ID = '8f3a3f14-9a1c-4f3c-9a4e-0d7c1f0a6b21';

const view = (patch: Partial<CalendarEventView> = {}): CalendarEventView => ({
  id: SERVER_ID,
  revision: 2,
  title: 'Созвон',
  description: '',
  location: '',
  startsAt: '2026-10-19T09:00:00.000Z',
  endsAt: '2026-10-19T10:00:00.000Z',
  allDay: false,
  timeZone: 'UTC',
  priority: 'medium',
  busyStatus: 'busy',
  recurrence: 'none',
  reminderMinutes: 15,
  attendees: [],
  source: { kind: 'NONE', mailId: null, subject: null, sender: null },
  createdAt: '2026-10-01T00:00:00.000Z',
  updatedAt: '2026-10-01T00:00:00.000Z',
  ...patch,
});

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

let removeMock: Mock<(id: string, revision?: number) => Promise<void>>;
let createMock: Mock<(body: CalendarEventSave) => Promise<CalendarEventView>>;
let updateMock: Mock<(id: string, body: CalendarEventSave) => Promise<CalendarEventView>>;
let listMock: Mock<(from: string, to: string) => Promise<CalendarEventView[]>>;

beforeEach(() => {
  localStorage.clear();
  listMock = vi.fn().mockResolvedValue([]);
  createMock = vi.fn().mockImplementation(async () => view());
  updateMock = vi.fn().mockImplementation(async () => view({ revision: 3 }));
  removeMock = vi.fn().mockResolvedValue(undefined);
  setCalendarTransportForTests({
    list: listMock, create: createMock, update: updateMock, remove: removeMock,
  });
  useCalendarStore.setState({ events: [], syncError: null, serverSynced: false });
});

afterEach(() => {
  useCalendarStore.getState().setOwner(null);
  setCalendarTransportForTests(null);
  localStorage.clear();
});

describe('календарь: серверная синхронизация', () => {
  it('загружает серверные события и держит их вместе с несинхронизированными локальными', async () => {
    listMock.mockResolvedValue([view()]);
    // Несинхронизированное событие лежит в кэше браузера — как после работы без сети.
    localStorage.setItem(
      `${CALENDAR_STORAGE_KEY}:user:user-1`,
      JSON.stringify([{ ...buildEmptyEventDraft('2026-10-20'), id: 'ev-local', title: 'Локальное' }]),
    );
    useCalendarStore.getState().setOwner('user-1', false, true);
    await flush();
    const events = useCalendarStore.getState().events;
    expect(events.map((event) => event.id).sort()).toEqual([SERVER_ID, 'ev-local'].sort());
    expect(useCalendarStore.getState().serverSynced).toBe(true);
  });

  it('создаёт событие на сервере и заменяет локальный id серверным', async () => {
    useCalendarStore.getState().setOwner('user-1', false, true);
    await flush();
    useCalendarStore.getState().upsertEvent({ ...buildEmptyEventDraft('2026-10-19'), title: 'Новое' });
    await flush();
    expect(createMock).toHaveBeenCalledTimes(1);
    expect(useCalendarStore.getState().events.map((event) => event.id)).toEqual([SERVER_ID]);
  });

  it('изменяет серверное событие с ревизией', async () => {
    listMock.mockResolvedValue([view()]);
    useCalendarStore.getState().setOwner('user-1', false, true);
    await flush();
    const existing = useCalendarStore.getState().events[0];
    useCalendarStore.getState().upsertEvent({ ...existing, title: 'Новое имя' });
    await flush();
    expect(updateMock).toHaveBeenCalledTimes(1);
    expect(updateMock.mock.calls[0][1].revision).toBe(2);
    expect(useCalendarStore.getState().events[0].revision).toBe(3);
  });

  it('показывает конфликт вместо молчаливой перезаписи', async () => {
    listMock.mockResolvedValue([view()]);
    useCalendarStore.getState().setOwner('user-1', false, true);
    await flush();
    updateMock.mockRejectedValue(new ApiError(409, 'conflict'));
    useCalendarStore.getState().upsertEvent({ ...useCalendarStore.getState().events[0], title: 'Устаревшая правка' });
    await flush();
    expect(useCalendarStore.getState().syncError).toBe('Событие изменено в другом окне. Обновите календарь.');
  });

  it('не теряет событие, если сервер недоступен, и честно об этом сообщает', async () => {
    useCalendarStore.getState().setOwner('user-1', false, true);
    await flush();
    createMock.mockRejectedValue(new Error('offline'));
    useCalendarStore.getState().upsertEvent({ ...buildEmptyEventDraft('2026-10-19'), title: 'Без сети' });
    await flush();
    expect(useCalendarStore.getState().events.map((event) => event.title)).toEqual(['Без сети']);
    expect(useCalendarStore.getState().syncError).toBe('Событие сохранено только в этом браузере: сервер календаря недоступен.');
  });

  it('удаляет серверное событие и не отправляет запрос для локального', async () => {
    listMock.mockResolvedValue([view()]);
    useCalendarStore.getState().setOwner('user-1', false, true);
    await flush();
    useCalendarStore.getState().deleteEvent(SERVER_ID);
    await flush();
    expect(removeMock).toHaveBeenCalledWith(SERVER_ID, 2);

    useCalendarStore.setState({
      events: [{ ...buildEmptyEventDraft('2026-10-20'), id: 'ev-local', createdAt: '2026-10-01T00:00:00.000Z' }],
    });
    useCalendarStore.getState().deleteEvent('ev-local');
    await flush();
    expect(removeMock).toHaveBeenCalledTimes(1);
  });

  it('демо-режим не обращается к серверу', async () => {
    useCalendarStore.getState().setOwner('demo-user', true, true);
    await flush();
    expect(listMock).not.toHaveBeenCalled();
    expect(useCalendarStore.getState().serverSynced).toBe(false);
  });
});
