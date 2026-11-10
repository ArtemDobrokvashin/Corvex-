// @vitest-environment jsdom
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it } from 'vitest';
import { useLocaleStore } from '../../i18n';
import MailAttachments from './MailAttachments';

const attachment = (avStatus: string, partIndex: number) => ({
  partIndex, avStatus, fileName: `файл-${partIndex}.txt`, sha256: 'hash', sizeBytes: 12, contentType: 'text/plain',
});

afterEach(() => { useLocaleStore.setState({ locale: 'ru' }); document.body.innerHTML = ''; });
describe('mail attachment display', () => {
  it('shows each real filename and enables only verified clean files', () => {
    useLocaleStore.setState({ locale: 'ru' });
    document.body.innerHTML = renderToStaticMarkup(<MailAttachments demoMode={false} busy={false}
      attachments={['CLEAN', 'INFECTED', 'ERROR', 'PENDING', 'UNVERIFIED'].map(attachment)} onDownload={() => undefined} />);
    const buttons = [...document.querySelectorAll('button')];
    expect(buttons.map((b) => b.disabled)).toEqual([false, true, true, true, true]);
    expect(document.body.textContent).toContain('файл-0.txt');
    expect(document.body.textContent).toContain('Antivirus scan not verified');
    expect(document.body.textContent).not.toContain('safe to open');
  });
  it('supports English and unknown status fails closed', () => {
    useLocaleStore.setState({ locale: 'en' });
    document.body.innerHTML = renderToStaticMarkup(<MailAttachments demoMode={false} busy={false}
      attachments={[attachment('OTHER', 0)]} onDownload={() => undefined} />);
    expect(document.querySelector('button')?.disabled).toBe(true);
    expect(document.body.textContent).toContain('Antivirus scan not verified');
  });
});
