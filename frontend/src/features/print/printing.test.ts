// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PRINT_TIMEOUT_MS, openPrintWindow, printHtmlDocument } from './printing';

const frames = () => Array.from(document.querySelectorAll('iframe'));

const printSpy = vi.fn();
const focusSpy = vi.fn();

// jsdom выдаёт каждому окну фрейма собственную заглушку `print`/`focus`, которая
// только пишет «Not implemented». Подменяем их в момент вставки фрейма в документ:
// `printHtmlDocument` вызывает `print()` сразу после `appendChild`.
function stubFramePrinting() {
  const appendChild = document.body.appendChild.bind(document.body);
  vi.spyOn(document.body, 'appendChild').mockImplementation(((node: Node) => {
    const appended = appendChild(node as never);
    // У окна фрейма вне DOM нет contentWindow, поэтому подменяем после вставки —
    // и до возврата из appendChild, то есть раньше, чем код печати вызовет print().
    if (node instanceof HTMLIFrameElement && node.contentWindow) {
      Object.defineProperty(node.contentWindow, 'print', { value: printSpy, configurable: true });
      Object.defineProperty(node.contentWindow, 'focus', { value: focusSpy, configurable: true });
    }
    return appended;
  }) as typeof document.body.appendChild);
}

describe('printHtmlDocument', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    stubFramePrinting();
    printSpy.mockClear();
    focusSpy.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    for (const frame of frames()) frame.remove();
  });

  it('создаёт изолированный фрейм, пишет в него документ и вызывает print', async () => {
    const html = '<!doctype html><html><body><h1>Печать</h1></body></html>';
    const result = printHtmlDocument(html, 10);

    const [frame] = frames();
    expect(frame).toBeTruthy();
    expect(frame.getAttribute('sandbox')).toBe('allow-same-origin');
    expect(frame.getAttribute('aria-hidden')).toBe('true');
    expect(frame.contentDocument?.body.innerHTML ?? '').toContain('Печать');
    expect(printSpy).toHaveBeenCalledTimes(1);
    expect(focusSpy).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(10);
    await expect(result).resolves.toEqual({ printed: true, html });
  });

  it('не считает печать проваленной, если afterprint не пришёл: по таймауту printed=true', async () => {
    const pending = printHtmlDocument('<p>x</p>', 20);
    await vi.advanceTimersByTimeAsync(20);
    await expect(pending).resolves.toMatchObject({ printed: true });
    expect(PRINT_TIMEOUT_MS).toBeGreaterThan(0);
  });

  it('убирает фрейм после печати, а не оставляет его в DOM', async () => {
    const pending = printHtmlDocument('<p>x</p>', 5);
    await vi.advanceTimersByTimeAsync(5);
    await pending;
    expect(frames().length).toBeGreaterThan(0);
    await vi.advanceTimersByTimeAsync(1000);
    expect(frames().length).toBe(0);
  });

  it('закрывает предыдущий фрейм, если печать вызвали повторно', async () => {
    const first = printHtmlDocument('<p>1</p>', 5);
    await vi.advanceTimersByTimeAsync(0);
    expect(frames().length).toBe(1);
    const second = printHtmlDocument('<p>2</p>', 5);
    await vi.advanceTimersByTimeAsync(0);
    expect(frames().length).toBe(1);
    await vi.advanceTimersByTimeAsync(5);
    await Promise.all([first, second]);
    await vi.advanceTimersByTimeAsync(1000);
    expect(frames().length).toBe(0);
  });

  it('возвращает printed=false, если браузер не дал документ фрейма, и не падает', async () => {
    const create = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation(((tag: string) => {
      const element = create(tag);
      if (tag === 'iframe') {
        Object.defineProperty(element, 'contentWindow', { value: null, configurable: true });
        Object.defineProperty(element, 'contentDocument', { value: null, configurable: true });
      }
      return element;
    }) as typeof document.createElement);

    await expect(printHtmlDocument('<p>x</p>', 5)).resolves.toMatchObject({ printed: false });
  });
});

describe('openPrintWindow', () => {
  afterEach(() => vi.restoreAllMocks());

  it('открывает новое окно и пишет в него документ — запасной путь для iOS Safari', () => {
    const written: string[] = [];
    const fakeWin = {
      opener: window,
      document: {
        open: vi.fn(),
        write: (value: string) => written.push(value),
        close: vi.fn(),
      },
    };
    const open = vi.spyOn(window, 'open').mockReturnValue(fakeWin as unknown as Window);

    expect(openPrintWindow('<p>запасной путь</p>')).toBe(true);
    // `noopener` в третьем аргументе вернул бы null — тогда писать было бы некуда.
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(written.join('')).toContain('запасной путь');
    // Ссылка на исходное окно обнулена вручную вместо `noopener`.
    expect(fakeWin.opener).toBeNull();
  });

  it('возвращает false, если всплывающее окно заблокировано', () => {
    vi.spyOn(window, 'open').mockReturnValue(null);
    expect(openPrintWindow('<p>x</p>')).toBe(false);
  });
});
