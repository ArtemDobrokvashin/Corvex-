// @vitest-environment jsdom
import { act, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/httpClient';
import { useLocaleStore } from '../../i18n';
import FilePreviewDialog from './FilePreviewDialog';
import type { PreviewSource } from './types';

let container: HTMLDivElement;
let root: Root;
let createdBlobs: Blob[];

const flush = async () => {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
};

const mount = async (element: ReactElement) => {
  await act(async () => {
    root.render(element);
  });
  await flush();
};

const textFile = (overrides: Partial<PreviewSource> = {}): PreviewSource => ({
  fileName: 'заметка.txt',
  contentType: 'text/plain',
  sizeBytes: 12,
  load: async () => new Blob(['привет']),
  ...overrides,
});

const renderDialog = async (items: PreviewSource[], index = 0, handlers: {
  onClose?: () => void;
  onIndexChange?: (index: number) => void;
} = {}) => {
  const onClose = handlers.onClose ?? vi.fn();
  const onIndexChange = handlers.onIndexChange ?? vi.fn();
  await mount(
    <FilePreviewDialog items={items} index={index} onClose={onClose} onIndexChange={onIndexChange} />,
  );
  return { onClose, onIndexChange };
};

const button = (label: string) => document.querySelector<HTMLButtonElement>(`button[aria-label="${label}"]`);

const buttonByText = (text: string) => [...document.querySelectorAll<HTMLButtonElement>('button')]
  .find((element) => (element.textContent ?? '').trim() === text);

beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  useLocaleStore.setState({ locale: 'ru' });
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  createdBlobs = [];
  URL.createObjectURL = vi.fn((blob: Blob) => {
    createdBlobs.push(blob);
    return 'blob:preview';
  });
  URL.revokeObjectURL = vi.fn();
});

afterEach(async () => {
  await act(async () => root.unmount());
  container.remove();
  useLocaleStore.setState({ locale: 'ru' });
  vi.restoreAllMocks();
});

describe('FilePreviewDialog', () => {
  it('shows a text file inside the app and can download it', async () => {
    const download = vi.fn();
    await renderDialog([textFile({ download })]);

    expect(document.body.textContent).toContain('заметка.txt');
    const pre = document.querySelector('pre');
    expect(pre?.textContent).toBe('привет');

    await act(async () => button('Скачать')?.click());
    expect(download).toHaveBeenCalledTimes(1);
  });

  it('re-types an octet-stream PDF so the built-in viewer can render it', async () => {
    await renderDialog([{
      fileName: 'договор.pdf',
      contentType: 'application/pdf',
      load: async () => new Blob(['%PDF-1.7'], { type: 'application/octet-stream' }),
    }]);

    expect(createdBlobs[0]?.type).toBe('application/pdf');
    const frame = document.querySelector('iframe');
    expect(frame?.getAttribute('src')).toBe('blob:preview');
    expect(frame?.getAttribute('title')).toContain('договор.pdf');
    expect(frame?.getAttribute('referrerpolicy')).toBe('no-referrer');
  });

  it('renders CSV as a table instead of plain text', async () => {
    await renderDialog([{
      fileName: 'отчёт.csv',
      contentType: 'text/csv',
      load: async () => new Blob(['товар,цена\nяблоко,3\n']),
    }]);

    const table = document.querySelector('table');
    expect(table?.textContent).toContain('товар');
    expect(table?.textContent).toContain('яблоко');
    expect(document.querySelector('pre')).toBeNull();
  });

  it('renders images with zoom controls', async () => {
    await renderDialog([{
      fileName: 'фото.png',
      contentType: 'image/png',
      load: async () => new Blob(['png']),
    }]);

    const image = document.querySelector('img');
    expect(image?.getAttribute('src')).toBe('blob:preview');
    expect(image?.getAttribute('alt')).toBe('фото.png');
    expect(document.body.textContent).toContain('100%');

    await act(async () => button('Приблизить')?.click());
    expect(document.body.textContent).toContain('125%');

    await act(async () => button('Вписать')?.click());
    expect(document.body.textContent).toContain('100%');
  });

  it('plays audio and video in native controls', async () => {
    await renderDialog([{
      fileName: 'голос.mp3',
      contentType: 'audio/mpeg',
      load: async () => new Blob(['audio']),
    }]);
    expect(document.querySelector('audio')?.getAttribute('src')).toBe('blob:preview');

    await act(async () => root.render(
      <FilePreviewDialog
        items={[{ fileName: 'видео.mp4', contentType: 'video/mp4', load: async () => new Blob(['video']) }]}
        index={0}
        onClose={vi.fn()}
        onIndexChange={vi.fn()}
      />,
    ));
    await flush();
    expect(document.querySelector('video')?.getAttribute('src')).toBe('blob:preview');
  });

  it('closes on Escape and switches files with arrow keys', async () => {
    const { onClose, onIndexChange } = await renderDialog([textFile(), textFile({ fileName: 'второй.txt' })]);
    expect(document.body.textContent).toContain('1 / 2');

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    });
    expect(onIndexChange).toHaveBeenCalledWith(1);

    await act(async () => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on a backdrop click but not on a click inside the dialog', async () => {
    const { onClose } = await renderDialog([textFile()]);
    const overlay = container.firstElementChild as HTMLElement;
    const panel = container.querySelector('[role="dialog"]') as HTMLElement;

    await act(async () => {
      panel.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });
    expect(onClose).not.toHaveBeenCalled();

    await act(async () => {
      overlay.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('blocks infected files completely', async () => {
    await renderDialog([{
      fileName: 'заражён.pdf',
      contentType: 'application/pdf',
      avStatus: 'INFECTED',
      load: async () => {
        throw new ApiError(403, 'Antivirus threat detected');
      },
    }]);

    expect(document.body.textContent).toContain('Антивирус заблокировал файл');
    expect(button('Скачать')).toBeNull();
    expect(buttonByText('Повторить')).toBeUndefined();
  });

  it('explains an unavailable scanner and retries the request', async () => {
    const load = vi.fn()
      .mockRejectedValueOnce(new ApiError(503, 'Scanner offline'))
      .mockResolvedValueOnce(new Blob(['готово']));
    await renderDialog([{
      fileName: 'ожидает.txt',
      contentType: 'text/plain',
      avStatus: 'PENDING',
      load,
    }]);

    expect(document.body.textContent).toContain('Антивирус временно недоступен');
    expect(load).toHaveBeenCalledTimes(1);

    await act(async () => buttonByText('Повторить')?.click());
    await flush();
    expect(load).toHaveBeenCalledTimes(2);
    expect(document.querySelector('pre')?.textContent).toBe('готово');
  });

  it('never loads HTML, Office or unsupported attachments', async () => {
    const htmlLoad = vi.fn();
    await renderDialog([{ fileName: 'письмо.html', contentType: 'text/html', load: htmlLoad }]);
    expect(document.body.textContent).toContain('HTML-вложения не открываются внутри приложения');
    expect(htmlLoad).not.toHaveBeenCalled();

    await act(async () => root.render(
      <FilePreviewDialog
        items={[{ fileName: 'смета.xlsx', contentType: null, load: htmlLoad }]}
        index={0}
        onClose={vi.fn()}
        onIndexChange={vi.fn()}
      />,
    ));
    await flush();
    expect(document.body.textContent).toContain('Предпросмотр документов Office пока недоступен');
    expect(button('Скачать')).toBeNull();
    expect(htmlLoad).not.toHaveBeenCalled();

    await act(async () => root.render(
      <FilePreviewDialog
        items={[{ fileName: 'архив.zip', contentType: 'application/zip', load: htmlLoad }]}
        index={0}
        onClose={vi.fn()}
        onIndexChange={vi.fn()}
      />,
    ));
    await flush();
    expect(document.body.textContent).toContain('Этот формат нельзя открыть в браузере');
    expect(htmlLoad).not.toHaveBeenCalled();
  });

  it('shows an Office document through the server-side PDF converter', async () => {
    const loadPdf = vi.fn().mockResolvedValue(new Blob(['%PDF-1.7'], { type: 'application/octet-stream' }));
    const load = vi.fn();
    await renderDialog([{
      fileName: 'смета.xlsx',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      sizeBytes: 4096,
      load,
      loadPdf,
      download: vi.fn(),
    }]);

    expect(loadPdf).toHaveBeenCalledTimes(1);
    expect(load).not.toHaveBeenCalled();
    expect(createdBlobs[0]?.type).toBe('application/pdf');
    expect(document.querySelector('iframe')?.getAttribute('src')).toBe('blob:preview');
    expect(document.body.textContent).not.toContain('пока недоступен');
  });

  it('falls back to downloading when the converter is disabled or rejects the document', async () => {
    const download = vi.fn();
    await renderDialog([{
      fileName: 'смета.xlsx',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      sizeBytes: 4096,
      loadPdf: vi.fn().mockRejectedValue(new ApiError(501, 'Office preview is disabled')),
      download,
    }]);

    expect(document.body.textContent).toContain('Не удалось подготовить предпросмотр');
    expect(document.body.textContent).toContain('Скачайте файл');
    expect(buttonByText('Повторить')).toBeUndefined();
    expect(button('Скачать')).not.toBeNull();
  });

  it('retries a failed Office conversion from the dialog', async () => {
    const loadPdf = vi.fn()
      .mockRejectedValueOnce(new ApiError(503, 'Converter busy'))
      .mockResolvedValueOnce(new Blob(['%PDF-1.7']));
    await renderDialog([{
      fileName: 'договор.docx',
      contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      sizeBytes: 4096,
      avStatus: 'PENDING',
      loadPdf,
    }]);

    expect(document.body.textContent).toContain('Антивирус временно недоступен');
    await act(async () => buttonByText('Повторить')?.click());
    await flush();

    expect(loadPdf).toHaveBeenCalledTimes(2);
    expect(document.querySelector('iframe')?.getAttribute('src')).toBe('blob:preview');
  });

  it('does not send an oversized Office document to the converter', async () => {
    const loadPdf = vi.fn();
    await renderDialog([{
      fileName: 'огромная смета.xlsx',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      sizeBytes: 26 * 1024 * 1024,
      loadPdf,
    }]);

    expect(loadPdf).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('Файл слишком большой для предпросмотра');
  });

  it('refuses to preview a file above the size limit', async () => {
    const load = vi.fn();
    await renderDialog([{
      fileName: 'огромный.pdf',
      contentType: 'application/pdf',
      sizeBytes: 60 * 1024 * 1024,
      load,
    }]);

    expect(document.body.textContent).toContain('Файл слишком большой для предпросмотра');
    expect(load).not.toHaveBeenCalled();
  });

  it('reports a failed download without closing the preview', async () => {
    const download = vi.fn().mockRejectedValue(new Error('offline'));
    await renderDialog([textFile({ download })]);

    await act(async () => button('Скачать')?.click());
    await flush();

    expect(document.body.textContent).toContain('Не удалось скачать файл');
    expect(document.body.textContent).toContain('заметка.txt');
  });

  it('marks a clean attachment as scanned', async () => {
    await renderDialog([textFile({ avStatus: 'CLEAN' })]);
    expect(document.body.textContent).toContain('Проверено антивирусом');
  });
});
