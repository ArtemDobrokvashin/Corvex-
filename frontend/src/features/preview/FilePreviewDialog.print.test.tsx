// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useLocaleStore } from '../../i18n';
import FilePreviewDialog from './FilePreviewDialog';
import type { PreviewSource } from './types';

/**
 * Проверяем именно то, что пользователь видит и нажимает: есть ли кнопка
 * «Печать», печатает ли CSV все строки (а не первые 1000) и не появляется ли
 * печать там, где печатать нечего.
 */

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;

function render(items: PreviewSource[], index = 0, onIndexChange = () => {}, onClose = () => {}) {
  act(() => {
    root.render(
      <FilePreviewDialog items={items} index={index} onIndexChange={onIndexChange} onClose={onClose} />,
    );
  });
}

const printButton = () => Array.from(host.querySelectorAll('button'))
  .find((button) => (button.getAttribute('aria-label') ?? '') === 'Печать');

const flush = async () => {
  await act(async () => {
    await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
};

beforeEach(() => {
  useLocaleStore.setState({ locale: 'ru' });
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.restoreAllMocks();
});

describe('FilePreviewDialog: печать файла', () => {
  it('CSV печатается целиком, а не первыми 1000 строками, как на экране', async () => {
    const rows = ['номер,значение'];
    for (let index = 0; index < 1500; index += 1) rows.push(`${index},v${index}`);
    const csv = rows.join('\n');
    const blob = new Blob([csv], { type: 'text/csv' });

    const printed: string[] = [];
    const appendChild = document.body.appendChild.bind(document.body);
    vi.spyOn(document.body, 'appendChild').mockImplementation(((node: Node) => {
      const appended = appendChild(node as never);
      if (node instanceof HTMLIFrameElement && node.contentWindow) {
        Object.defineProperty(node.contentWindow, 'print', {
          value: () => printed.push(node.contentDocument?.body.innerHTML ?? ''),
          configurable: true,
        });
        Object.defineProperty(node.contentWindow, 'focus', { value: () => {}, configurable: true });
      }
      return appended;
    }) as typeof document.body.appendChild);

    render([{ fileName: 'выгрузка.csv', contentType: 'text/csv', sizeBytes: 20_000, load: async () => blob }]);
    await flush();
    await flush();

    const button = printButton();
    expect(button).toBeTruthy();
    await act(async () => {
      button!.click();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    expect(printed.length).toBe(1);
    expect(printed[0]).toContain('v1499');
    // На экране предупреждение об обрезке остаётся честным: там по-прежнему 1000 строк.
    expect(printed[0]).not.toContain('Показаны не все строки');
  });

  it('если встроенный просмотрщик PDF не печатает, документ открывается в новом табе', async () => {
    const fakeWindow = { document: { open: vi.fn(), write: vi.fn(), close: vi.fn() }, opener: null };
    const open = vi.spyOn(window, 'open').mockReturnValue(fakeWindow as unknown as Window);

    render([{
      fileName: 'договор.pdf',
      contentType: 'application/pdf',
      sizeBytes: 4096,
      load: async () => new Blob(['%PDF-1.4'], { type: 'application/pdf' }),
    }]);
    await flush();
    await flush();

    // Стенд: просмотрщик PDF на месте, но печать из него не идёт.
    const frame = host.querySelector<HTMLIFrameElement>('iframe[data-print-frame="pdf"]');
    expect(frame).toBeTruthy();
    Object.defineProperty(frame!.contentWindow!, 'print', {
      value: () => { throw new Error('viewer cannot print'); },
      configurable: true,
    });
    Object.defineProperty(frame!.contentWindow!, 'focus', { value: () => {}, configurable: true });

    const button = printButton();
    expect(button).toBeTruthy();
    await act(async () => {
      button!.click();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    // Просмотрщик отказал — значит мы обязаны открыть запасной документ, а не молчать.
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(fakeWindow.document.write).toHaveBeenCalled();
    expect(fakeWindow.document.write.mock.calls[0][0]).toContain('договор.pdf');
    // Ошибки на экране нет: запасной путь сработал.
    expect(host.textContent ?? '').not.toContain('Не удалось открыть печать');
  });

  it('у заблокированного файла кнопки печати нет', async () => {
    render([{
      fileName: 'отчёт.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1000,
      load: async () => {
        throw Object.assign(new Error('blocked'), { status: 403 });
      },
    }]);
    await flush();
    await flush();
    expect(printButton()).toBeUndefined();
  });

  it('у файла без предпросмотра (HTML-вложение) кнопки печати нет', async () => {
    render([{ fileName: 'письмо.html', contentType: 'text/html', sizeBytes: 900 }]);
    await flush();
    expect(printButton()).toBeUndefined();
  });

  it('пока антивирус не подтвердил файл, печать недоступна', async () => {
    render([{
      fileName: 'смета.pdf',
      contentType: 'application/pdf',
      sizeBytes: 1000,
      avStatus: 'PENDING',
      load: async () => new Blob(['%PDF-1.4'], { type: 'application/pdf' }),
    }]);
    await flush();
    await flush();
    expect(printButton()).toBeUndefined();
  });
});
