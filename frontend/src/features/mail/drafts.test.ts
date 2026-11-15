// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { buildDraftPayload, DRAFT_LIMITS, draftSizeProblem, draftStatusKey, fileToAttachment } from './drafts';

function fileOf(size: number, name = 'a.bin', type = 'application/octet-stream') {
  return new File([new Uint8Array(size)], name, { type });
}

describe('draft payloads', () => {
  it('encodes attachments as base64 without losing bytes', async () => {
    const attachment = await fileToAttachment(new File(['abc'], 'note.txt', { type: 'text/plain' }));
    expect(attachment.fileName).toBe('note.txt');
    expect(attachment.contentType).toBe('text/plain');
    expect(attachment.contentBase64).toBe('YWJj');
  });

  it('falls back to a generic content type', async () => {
    const attachment = await fileToAttachment(new File(['x'], 'blob'));
    expect(attachment.contentType).toBe('application/octet-stream');
  });

  it('applies the same limits as the server', () => {
    expect(draftSizeProblem([])).toBeNull();
    expect(draftSizeProblem(Array.from({ length: DRAFT_LIMITS.maxFiles + 1 }, (_, i) => fileOf(1, `${i}.bin`)))).toBe('count');
    expect(draftSizeProblem([fileOf(DRAFT_LIMITS.maxFileBytes + 1)])).toBe('file');
    expect(draftSizeProblem(Array.from({ length: 4 }, (_, i) => fileOf(3 * 1024 * 1024, `${i}.bin`)))).toBe('total');
  });

  it('builds a payload that mirrors the server contract', async () => {
    const payload = await buildDraftPayload(
      { to: 'a@b.test', cc: 'c@d.test', subject: 'Hi', body: 'Text', bodyHtml: '<p>Text</p>', externalAccountId: null },
      [new File(['abc'], 'note.txt', { type: 'text/plain' })],
    );
    expect(payload).toMatchObject({ to: 'a@b.test', cc: 'c@d.test', subject: 'Hi', bodyText: 'Text', bodyHtml: '<p>Text</p>', externalAccountId: null });
    expect(payload.attachments).toHaveLength(1);
  });

  it('returns translation keys for the autosave state', () => {
    expect(draftStatusKey('saving')).toBe('Сохранение черновика…');
    expect(draftStatusKey('saved')).toBe('Черновик сохранён');
    expect(draftStatusKey('error')).toContain('не сохранён');
    expect(draftStatusKey('idle')).toBeNull();
  });
});
