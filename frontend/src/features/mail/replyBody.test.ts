import { describe, expect, it } from 'vitest';
import { buildReplyBody, replySubject } from './replyBody';

describe('reply body', () => {
  it('quotes every line and keeps a blank line quoted', () => {
    const body = buildReplyBody(
      { id: '1', from: 'Anna <a@corp.test>', subject: 'Plan', date: '19.10', body: 'Line one\n\nLine two' },
      { senderLabel: 'From', wroteLabel: 'wrote' },
    );
    expect(body).toContain('Anna <a@corp.test> wrote');
    expect(body).toContain('> Line one\n>\n> Line two');
  });
  it('returns an empty quote for a message without text', () => {
    expect(buildReplyBody({ id: '1', from: 'a', subject: 's', date: 'd', body: '   ' }, { senderLabel: 'F', wroteLabel: 'w' })).toBe('');
  });
  it('does not stack Re: prefixes', () => {
    expect(replySubject('Plan')).toBe('Re: Plan');
    expect(replySubject('Re: Plan')).toBe('Re: Plan');
    expect(replySubject('  Re: Re: Plan')).toBe('Re: Re: Plan');
    expect(replySubject('RE: Plan')).toBe('RE: Plan');
  });
});
