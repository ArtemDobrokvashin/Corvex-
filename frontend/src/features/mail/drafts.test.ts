// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import {
  buildDraftPayload,
  DRAFT_LIMITS,
  draftSizeProblem,
  draftStatusKey,
  fileToAttachment,
  remainingAttachmentBytes,
  sendSizeProblem,
  SEND_LIMITS,
} from './drafts';

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

describe('send size limits', () => {
  it('counts base64 overhead, not just raw bytes', () => {
    // 20 МиБ «как есть» после base64 занимают ~26.7 МиБ и не пройдут nginx,
    // поэтому бюджет считается по закодированному размеру.
    const big = fileOf(15 * 1024 * 1024, 'big.bin');
    expect(sendSizeProblem([big])).toBeNull();
    const tooBig = fileOf(20 * 1024 * 1024, 'too-big.bin');
    expect(sendSizeProblem([tooBig])).toBe('total');
  });

  it('rejects more files than the server accepts', () => {
    const files = Array.from({ length: SEND_LIMITS.maxFiles + 1 }, (_, i) => fileOf(1, `${i}.bin`));
    expect(sendSizeProblem(files)).toBe('count');
    expect(remainingAttachmentBytes(files)).toBeGreaterThan(0);
  });

  it('reports remaining budget so the composer can show it', () => {
    expect(remainingAttachmentBytes([])).toBe(SEND_LIMITS.requestBudgetBytes);
    // base64 от 3 МиБ — это 4 МиБ.
    expect(remainingAttachmentBytes([fileOf(3 * 1024 * 1024, 'a.bin')]))
      .toBe(SEND_LIMITS.requestBudgetBytes - 4 * 1024 * 1024);
  });

  it('keeps the scheduled time and the reply branch in the draft payload', async () => {
    // Без этих двух полей черновик ответа терял ветку, а отложенное письмо —
    // время: после перезагрузки оно уходило бы сразу.
    const payload = await buildDraftPayload(
      {
        to: 'alice@example.com',
        subject: 'Re: Budget',
        body: 'Approved',
        sendAt: '2026-11-02T08:00:00.000Z',
        inReplyToMessageId: 'm-42',
      },
      [],
    );

    expect(payload.sendAt).toBe('2026-11-02T08:00:00.000Z');
    expect(payload.inReplyToMessageId).toBe('m-42');
  });
});
