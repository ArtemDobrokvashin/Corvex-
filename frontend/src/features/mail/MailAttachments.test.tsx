// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import MailAttachments from './MailAttachments';

const attachment = (avStatus: string, partIndex: number) => ({
  partIndex, avStatus, fileName: `файл-${partIndex}.txt`, sha256: 'hash', sizeBytes: 12, contentType: 'text/plain',
});

afterEach(() => { useLocaleStore.setState({ locale: 'ru' }); document.body.innerHTML = ''; });
describe('mail attachment display', () => {
  it('shows each real filename and allows retrying a scan without claiming the file is clean', () => {
    useLocaleStore.setState({ locale: 'ru' });
    document.body.innerHTML = renderToStaticMarkup(<MailAttachments demoMode={false} busy={false}
      attachments={['CLEAN', 'INFECTED', 'ERROR', 'PENDING', 'UNVERIFIED'].map(attachment)} onDownload={() => undefined} />);
    const buttons = [...document.querySelectorAll('button')];
    expect(buttons.map((b) => b.disabled)).toEqual([false, true, false, false, false]);
    expect(document.body.textContent).toContain('файл-0.txt');
    expect(document.body.textContent).toContain('Antivirus scan not verified');
    expect(document.body.textContent).not.toContain('safe to open');
  });
  it('derives the icon from the scan verdict, never from the file extension', () => {
    useLocaleStore.setState({ locale: 'en' });
    const misleading = ['report.PDF', 'photo.png', 'archive.zip', 'code.ts'];
    document.body.innerHTML = renderToStaticMarkup(<MailAttachments demoMode={false} busy={false}
      attachments={misleading.map((fileName, index) => ({ ...attachment('CLEAN', index), fileName }))} onDownload={() => undefined} />);
    const icons = [...document.querySelectorAll('svg')];
    // Расширение не считается вердиктом: тип файла не угадывается по имени,
    // а значок одинаков для всех чистых вложений.
    expect(icons.every((icon) => icon.classList.contains('lucide-file-check-2')
      || icon.classList.contains('lucide-shield-check')
      || icon.classList.contains('lucide-download'))).toBe(true);
    expect(icons.some((icon) => icon.classList.contains('lucide-file-text'))).toBe(false);

    document.body.innerHTML = renderToStaticMarkup(<MailAttachments demoMode={false} busy={false}
      attachments={[attachment('INFECTED', 0), attachment('PENDING', 1), attachment('ERROR', 2)]} onDownload={() => undefined} />);
    const alert = document.querySelector('.lucide-shield-alert');
    const question = document.querySelectorAll('.lucide-shield-question');
    expect(alert).not.toBeNull();
    expect(question.length).toBe(2);
    expect(document.body.textContent).not.toContain('safe to open');
  });
  it('opens a preview from the card but keeps download as a separate action', async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    useLocaleStore.setState({ locale: 'ru' });
    const onPreview = vi.fn();
    const onDownload = vi.fn();
    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    await act(async () => {
      root.render(<MailAttachments demoMode={false} busy={false} attachments={[attachment('CLEAN', 0)]}
        onDownload={onDownload} onPreview={onPreview} />);
    });

    const previewButton = host.querySelector('button[aria-label^="Открыть предпросмотр"]') as HTMLButtonElement;
    expect(previewButton).not.toBeNull();
    await act(async () => previewButton.click());
    expect(onPreview).toHaveBeenCalledWith(expect.objectContaining({ fileName: 'файл-0.txt' }));
    expect(onDownload).not.toHaveBeenCalled();

    const downloadButton = [...host.querySelectorAll('button')].find((b) => b.textContent?.includes('Скачать')) as HTMLButtonElement;
    await act(async () => downloadButton.click());
    expect(onDownload).toHaveBeenCalledWith(expect.objectContaining({ fileName: 'файл-0.txt' }));

    await act(async () => root.unmount());
  });

  it('does not add a preview button when the page has no preview handler', () => {
    document.body.innerHTML = renderToStaticMarkup(<MailAttachments demoMode={false} busy={false}
      attachments={[attachment('CLEAN', 0)]} onDownload={() => undefined} />);
    expect(document.querySelectorAll('button')).toHaveLength(1);
    expect(document.querySelector('button[aria-label^="Открыть предпросмотр"]')).toBeNull();
  });

  it('supports English and unknown status fails closed', () => {
    useLocaleStore.setState({ locale: 'en' });
    document.body.innerHTML = renderToStaticMarkup(<MailAttachments demoMode={false} busy={false}
      attachments={[attachment('OTHER', 0)]} onDownload={() => undefined} />);
    expect(document.querySelector('button')?.disabled).toBe(false);
    expect(document.querySelector('button')?.textContent).toContain('Scan and download');
    expect(document.body.textContent).toContain('Antivirus scan not verified');
  });
});
