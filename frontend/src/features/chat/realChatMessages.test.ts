import { describe, expect, it } from 'vitest';
import { mergeMessages, type LocalMessage } from './realChatMessages';

function message(overrides: Partial<LocalMessage> & Pick<LocalMessage, 'id'>): LocalMessage {
  return {
    roomId: 'room-1',
    authorId: 'user-1',
    kind: 'TEXT',
    bodyMarkdown: 'hello',
    threadRootId: null,
    fileRef: null,
    pinned: false,
    editedAt: null,
    deleted: false,
    clientMessageId: 'client-1',
    mentionedUserIds: [],
    reactions: [],
    createdAt: '2026-10-26T12:00:00.000Z',
    ...overrides,
  };
}

describe('real chat message merge', () => {
  it('clears optimistic sending status when the server confirms by clientMessageId', () => {
    const optimistic = message({
      id: 'local-client-1',
      localStatus: 'sending',
      localFileName: 'note.txt',
    });
    const saved = message({
      id: '20000000-0000-4000-8000-000000000001',
      localStatus: undefined,
    });

    const result = mergeMessages([optimistic], [saved]);

    expect(result).toHaveLength(1);
    expect(result[0].id).toBe(saved.id);
    expect(result[0].localStatus).toBeUndefined();
    expect(result[0].localFileName).toBe('note.txt');
  });

  it('clears a stale local status when an authoritative update replaces the same server id', () => {
    const existing = message({
      id: '20000000-0000-4000-8000-000000000001',
      localStatus: 'failed',
    });
    const saved = message({
      id: existing.id,
      bodyMarkdown: 'edited',
    });

    const result = mergeMessages([existing], [saved]);

    expect(result[0].bodyMarkdown).toBe('edited');
    expect(result[0].localStatus).toBeUndefined();
  });

  it('keeps sending status only for a temporary local message', () => {
    const optimistic = message({
      id: 'local-client-1',
      localStatus: 'sending',
    });

    const result = mergeMessages([], [optimistic]);

    expect(result[0].localStatus).toBe('sending');
  });
});
