import { describe, expect, it } from 'vitest';
import type { MailAttachmentView } from '../../api/mailApi';
import { buildForwardBody, forwardSubject, planForwardAttachments } from './forwardMail';

const file = (partIndex: number, avStatus: string, sizeBytes = 100, fileName = `file-${partIndex}.pdf`): MailAttachmentView => ({
  partIndex, avStatus, sizeBytes, fileName, sha256: `sha-${partIndex}`, contentType: 'application/pdf',
});

describe('group forward planning', () => {
  it('forwards only verified originals and explains every refusal', () => {
    const plan = planForwardAttachments([
      { id: 'm1', attachments: [file(0, 'CLEAN'), file(1, 'INFECTED'), file(2, 'UNVERIFIED')] },
      { id: 'm2', attachments: [file(3, 'CLEAN')] },
    ]);
    expect(plan.candidates.map((item) => `${item.messageId}:${item.attachment.fileName}`))
      .toEqual(['m1:file-0.pdf', 'm2:file-3.pdf']);
    expect(plan.blocked).toEqual([
      { messageId: 'm1', fileName: 'file-1.pdf', reason: 'infected' },
      { messageId: 'm1', fileName: 'file-2.pdf', reason: 'unverified' },
    ]);
  });

  it('stops at the attachment size, count and total limits instead of silently dropping files', () => {
    const oversized = file(0, 'CLEAN', 3 * 1024 * 1024 + 1, 'huge.zip');
    const many = Array.from({ length: 12 }, (_, index) => file(index + 1, 'CLEAN', 10, `f${index}.pdf`));
    const plan = planForwardAttachments([
      { id: 'm1', attachments: [oversized] },
      { id: 'm2', attachments: many },
    ]);
    expect(plan.candidates).toHaveLength(10);
    expect(plan.blocked.map((item) => item.reason)).toEqual(['too-large', 'too-many', 'too-many']);

    const total = planForwardAttachments([{
      id: 'm3',
      attachments: [0, 1, 2, 3].map((index) => file(index, 'CLEAN', 3 * 1024 * 1024)),
    }]);
    // 3 МБ проходят поштучно, но четвёртый файл превысил бы общий лимит 10 МБ.
    expect(total.candidates).toHaveLength(3);
    expect(total.blocked).toEqual([{ messageId: 'm3', fileName: 'file-3.pdf', reason: 'total-limit' }]);
  });
});

describe('forward body', () => {
  it('keeps sender, date, subject and the full original text', () => {
    const body = buildForwardBody([
      { sender: 'Alice <alice@example.com>', subject: 'Contract', date: '3 апреля 2026', bodyText: 'Полный текст письма.' },
      { sender: 'bob@example.com', subject: 'Re: Contract', date: '4 апреля 2026', bodyText: 'Второе письмо.' },
    ], 'Пересылаемое письмо');
    expect(body).toBe([
      '--- Пересылаемое письмо ---',
      'From: Alice <alice@example.com>',
      'Date: 3 апреля 2026',
      'Subject: Contract',
      '',
      'Полный текст письма.',
      '',
      '--- Пересылаемое письмо ---',
      'From: bob@example.com',
      'Date: 4 апреля 2026',
      'Subject: Re: Contract',
      '',
      'Второе письмо.',
    ].join('\n'));
  });

  it('marks a batch by count and keeps the single prefix for one message', () => {
    expect(forwardSubject([{ subject: 'Contract' }], 'Fwd:')).toBe('Fwd: Contract');
    expect(forwardSubject([{ subject: 'Contract' }, { subject: 'Invoice' }], 'Fwd:')).toBe('Fwd: (2) Contract');
    expect(forwardSubject([], 'Fwd:')).toBe('');
  });
});
