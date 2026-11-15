// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { chatApi, type ChatMessageView, type MessagePage, type RoomSummaryView } from '../../api/chatApi';
import * as chatSocket from '../../api/chatSocket';
import { useLocaleStore } from '../../i18n';
import { useSession } from '../../store/sessionStore';
import RealChatPage from './RealChatPage';

function message(id: string, overrides: Partial<ChatMessageView> = {}): ChatMessageView {
  return {
    id,
    roomId: 'room-1',
    authorId: 'peer',
    kind: 'TEXT',
    bodyMarkdown: id,
    threadRootId: null,
    fileRef: null,
    pinned: false,
    editedAt: null,
    deleted: false,
    clientMessageId: null,
    mentionedUserIds: [],
    reactions: [],
    createdAt: '2026-01-01T10:00:00.000Z',
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail; });
  return { promise, resolve, reject };
}

const room: RoomSummaryView = {
  id: 'room-1', kind: 'GROUP', title: 'Team', ownerId: 'me', readOnly: false,
  memberIds: ['me', 'peer'], myRole: 'MEMBER', memberCount: 2, unreadCount: 0,
  mentionCount: 0, lastMessage: null, createdAt: '2026-01-01T09:00:00Z', lastMessageAt: '2026-01-01T09:00:00Z',
};
const rootA = message('root-a', { bodyMarkdown: 'First discussion' });
const rootB = message('root-b', { bodyMarkdown: 'Second discussion', createdAt: '2026-01-01T10:01:00Z' });
const page = (items: ChatMessageView[], nextBefore: string | null = null): MessagePage => ({ items, hasMore: Boolean(nextBefore), nextBefore });

let container: HTMLDivElement;
let root: Root;
let onSocketMessage: (payload: unknown) => void;
let history: MockInstance<typeof chatApi.history>;
let send: MockInstance<typeof chatApi.send>;

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
}

async function click(element: Element | null) {
  expect(element).not.toBeNull();
  await act(async () => { (element as HTMLElement).click(); await Promise.resolve(); });
}

function threadButton(id: string) {
  return container.querySelector(`#chat-message-${id} button[aria-label="Reply in thread"]`);
}

function setTextareaValue(element: HTMLTextAreaElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(element, value);
  element.dispatchEvent(new Event('input', { bubbles: true }));
}

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  HTMLElement.prototype.scrollIntoView = vi.fn();
  useLocaleStore.setState({ locale: 'en' });
  useSession.getState().setSession({ id: 'me', displayName: 'Me', email: 'me@example.com', groups: [], permissions: ['CHAT_READ', 'CHAT_WRITE'] }, false);
  vi.spyOn(chatApi, 'rooms').mockResolvedValue([room]);
  vi.spyOn(chatApi, 'directory').mockResolvedValue([{ id: 'peer', username: 'peer', email: 'peer@example.com', displayName: 'Peer', department: null, status: 'ACTIVE', presence: 'ONLINE' }]);
  vi.spyOn(chatApi, 'members').mockResolvedValue([]);
  vi.spyOn(chatApi, 'read').mockResolvedValue(undefined);
  history = vi.spyOn(chatApi, 'history');
  send = vi.spyOn(chatApi, 'send');
  vi.spyOn(chatSocket, 'connectChat').mockImplementation((_token, _roomId, onMessage) => {
    onSocketMessage = onMessage;
    return { connected: false, deactivate: vi.fn().mockResolvedValue(undefined) } as unknown as ReturnType<typeof chatSocket.connectChat>;
  });
  container = document.createElement('div');
  document.body.appendChild(container);
  root = createRoot(container);
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  useSession.getState().clearSession();
  vi.restoreAllMocks();
});

describe('real chat threads', () => {
  it('loads history, sends optimistically, retries with the same id and merges the server response', async () => {
    history.mockImplementation(async (_roomId, options = {}) => options.threadRootId
      ? page([message('reply-history', { threadRootId: options.threadRootId, bodyMarkdown: 'Existing reply' })])
      : page([rootA, rootB]));
    const firstSend = deferred<ChatMessageView>();
    send.mockImplementationOnce(() => firstSend.promise);
    send.mockImplementationOnce(async (_roomId, request) => message('saved-reply', {
      authorId: 'me', bodyMarkdown: request.bodyMarkdown ?? null,
      threadRootId: request.threadRootId ?? null, clientMessageId: request.clientMessageId ?? null,
    }));

    await act(async () => { root.render(<MemoryRouter><RealChatPage /></MemoryRouter>); });
    await flush();
    await click(threadButton('root-a'));
    await flush();
    expect(history).toHaveBeenCalledWith('room-1', { threadRootId: 'root-a', limit: 50 });
    expect(container.querySelector('aside[aria-label="Thread"]')?.textContent).toContain('Existing reply');
    expect(container.querySelector('aside[aria-label="Thread"]')?.className).toContain('absolute inset-0');

    const textarea = container.querySelector('textarea[aria-label="Thread reply"]') as HTMLTextAreaElement;
    await act(async () => setTextareaValue(textarea, 'A new reply'));
    await click(container.querySelector('button[aria-label="Send reply"]'));
    expect(send).toHaveBeenCalledTimes(1);
    expect(container.querySelector('aside[aria-label="Thread"]')?.textContent).toContain('A new reply');
    expect(container.querySelector('aside[aria-label="Thread"]')?.textContent).toContain('Sending');
    await act(async () => { firstSend.reject(new Error('connection failed')); await Promise.resolve(); });
    const retry = Array.from(container.querySelectorAll('aside[aria-label="Thread"] button')).find((button) => button.textContent?.includes('Retry'));
    expect(retry).toBeDefined();
    await click(retry!);
    await flush();
    const firstRequest = send.mock.calls[0][1];
    const retryRequest = send.mock.calls[1][1];
    expect(firstRequest.threadRootId).toBe('root-a');
    expect(retryRequest.clientMessageId).toBe(firstRequest.clientMessageId);
    const thread = container.querySelector('aside[aria-label="Thread"]')!;
    expect(thread.textContent?.match(/A new reply/g)).toHaveLength(1);
    expect(thread.textContent).not.toContain('Not sent');
  });

  it('keeps thread pagination isolated when switching rooms', async () => {
    const olderA = deferred<MessagePage>();
    const olderB = deferred<MessagePage>();
    const roomB: RoomSummaryView = { ...room, id: 'room-2', title: 'Other team', lastMessageAt: '2025-01-01T00:00:00Z' };
    const rootC = message('root-c', { roomId: 'room-2', bodyMarkdown: 'Other discussion' });
    vi.mocked(chatApi.rooms).mockResolvedValue([room, roomB]);
    history.mockImplementation((_roomId, options = {}) => {
      if (options.threadRootId === 'root-a' && options.before) return olderA.promise;
      if (options.threadRootId === 'root-c' && options.before) return olderB.promise;
      if (options.threadRootId) return Promise.resolve(page([], `cursor-${options.threadRootId}`));
      return Promise.resolve(page([_roomId === 'room-1' ? rootA : rootC]));
    });

    await act(async () => { root.render(<MemoryRouter><RealChatPage /></MemoryRouter>); });
    await flush();
    await click(threadButton('root-a'));
    await flush();
    await click(Array.from(container.querySelectorAll('aside[aria-label="Thread"] button')).find((button) => button.textContent === 'Show earlier messages') ?? null);
    await click(container.querySelector('button[title="Other team"]'));
    await flush();
    expect(container.querySelector('aside[aria-label="Thread"]')).toBeNull();
    await click(threadButton('root-c'));
    await flush();
    const thread = container.querySelector('aside[aria-label="Thread"]')!;
    await click(Array.from(thread.querySelectorAll('button')).find((button) => button.textContent === 'Show earlier messages') ?? null);
    expect(history).toHaveBeenCalledWith('room-2', { threadRootId: 'root-c', before: 'cursor-root-c', limit: 50 });
    await act(async () => { olderA.reject(new Error('old room failure')); await Promise.resolve(); });
    expect(thread.textContent).not.toContain('old room failure');
    expect(thread.querySelector('[role="status"]')?.textContent).toContain('Loading');
    await act(async () => { olderB.resolve(page([message('older-other-reply', { roomId: 'room-2', threadRootId: 'root-c' })])); await Promise.resolve(); });
    await flush();
    expect(thread.textContent).toContain('older-other-reply');
  });

  it('isolates timeline pagination while switching between rooms', async () => {
    const olderA = deferred<MessagePage>();
    const olderB = deferred<MessagePage>();
    const roomB: RoomSummaryView = { ...room, id: 'room-2', title: 'Other team', lastMessageAt: '2025-01-01T00:00:00Z' };
    const rootC = message('root-c', { roomId: 'room-2', bodyMarkdown: 'Other discussion' });
    vi.mocked(chatApi.rooms).mockResolvedValue([room, roomB]);
    history.mockImplementation((roomId, options = {}) => {
      if (options.before) return roomId === 'room-1' ? olderA.promise : olderB.promise;
      return Promise.resolve(page([roomId === 'room-1' ? rootA : rootC], `cursor-${roomId}`));
    });

    await act(async () => { root.render(<MemoryRouter><RealChatPage /></MemoryRouter>); });
    await flush();
    const olderButton = () => Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Show earlier messages') ?? null;
    await click(olderButton());
    await click(container.querySelector('button[title="Other team"]'));
    await flush();
    expect(olderButton()?.disabled).toBe(false);
    await click(olderButton());
    expect(history).toHaveBeenCalledWith('room-2', { before: 'cursor-room-2', limit: 50 });
    await act(async () => { olderA.reject(new Error('old room failure')); await Promise.resolve(); });
    expect(container.textContent).not.toContain('old room failure');
    expect(olderButton()?.disabled).toBe(true);
    await act(async () => { olderB.resolve(page([message('older-other-message', { roomId: 'room-2' })])); await Promise.resolve(); });
    await flush();
    expect(container.textContent).toContain('older-other-message');
  });

  it('ignores stale pagination after switching threads and displays realtime replies', async () => {
    const olderA = deferred<MessagePage>();
    history.mockImplementation(async (_roomId, options = {}) => {
      if (!options.threadRootId) return page([rootA, rootB]);
      if (options.threadRootId === 'root-a' && options.before) return olderA.promise;
      if (options.threadRootId === 'root-a') return page([message('a-reply', { threadRootId: 'root-a' })], 'cursor-a');
      return page([message('b-reply', { threadRootId: 'root-b' })], 'cursor-b');
    });

    await act(async () => { root.render(<MemoryRouter><RealChatPage /></MemoryRouter>); });
    await flush();
    await click(threadButton('root-a'));
    await flush();
    const older = Array.from(container.querySelectorAll('button')).find((button) => button.textContent === 'Show earlier messages');
    await click(older ?? null);
    await click(container.querySelector('button[aria-label="Close thread"]'));
    await click(threadButton('root-b'));
    await flush();
    await act(async () => { olderA.reject(new Error('old request failed')); await Promise.resolve(); });
    const thread = container.querySelector('aside[aria-label="Thread"]')!;
    expect(thread.textContent).toContain('Second discussion');
    expect(thread.textContent).toContain('b-reply');
    expect(thread.textContent).not.toContain('old request failed');
    expect(thread.textContent).toContain('Show earlier messages');

    await act(async () => onSocketMessage({ type: 'MESSAGE_CREATED', roomId: 'room-1', message: message('live', { threadRootId: 'root-b', bodyMarkdown: 'Live reply' }) }));
    expect(thread.textContent).toContain('Live reply');
    const olderB = Array.from(thread.querySelectorAll('button')).find((button) => button.textContent === 'Show earlier messages');
    await click(olderB ?? null);
    expect(history).toHaveBeenCalledWith('room-1', { threadRootId: 'root-b', before: 'cursor-b', limit: 50 });
  });
});
