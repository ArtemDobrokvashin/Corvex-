// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { BLOCKING_PRINT_MS, openPrintWindow, printHtmlDocument } from './printing';

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
    if (node instanceof HTMLIFrameElement && node.contentWindow) {
      Object.defineProperty(node.contentWindow, 'print', { value: printSpy, configurable: true });
      Object.defineProperty(node.contentWindow, 'focus', { value: focusSpy, configurable: true });
    }
    return appended;
  }) as typeof document.body.appendChild);
}

/**
 * `print()` в настоящем браузере блокирует JS, пока пользователь не закроет
 * диалог. Здесь это изображается подменой `Date.now()`: первый вызов — старт,
 * второй — конец. `vi.useFakeTimers()` тоже подменяет `Date.now()`, поэтому
 * такой способ единственный, который не превращает проверку в вечный цикл.
 */
/**
 * Настоящий `print()` в браузере блокирует JS, пока пользователь не закроет
 * диалог. Здесь это изображается сдвигом часов ровно на время вызова: `now`
 * читается перед `print()` и после него, а сдвигает его сам мок.
 *
 * Часы задаются одной переменной, а не очередью значений: `Date.now()` зовут и
 * посторонние механизмы, и очередь «первый вызов — старт» ломалась бы от них.
 */
let now = 1_000_000;

function printTakes(ms: number) {
  vi.spyOn(Date, 'now').mockImplementation(() => now);
  printSpy.mockImplementation(() => {
    now += ms;
  });
}

describe('printHtmlDocument', () => {
  beforeEach(() => {
    now = 1_000_000;
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
    printTakes(BLOCKING_PRINT_MS + 200);
    const result = printHtmlDocument(html);

    const [frame] = frames();
    expect(frame).toBeTruthy();
    // Без `allow-modals` Chrome молча игнорирует `print()`: печать не открывается,
    // и именно так она «не работала» в собранном приложении.
    expect(frame.getAttribute('sandbox')).toContain('allow-modals');
    expect(frame.getAttribute('sandbox')).toContain('allow-same-origin');
    expect(frame.getAttribute('sandbox')).not.toContain('allow-scripts');
    // `visibility:hidden` убран: браузер может выкинуть такой фрейм из печати.
    expect(frame.style.visibility).not.toBe('hidden');
    expect(frame.contentDocument?.body.innerHTML ?? '').toContain('Печать');
    expect(printSpy).toHaveBeenCalledTimes(1);
    expect(focusSpy).toHaveBeenCalledTimes(1);

    await expect(result).resolves.toEqual({ printed: true, html });
  });

  it('мгновенный print() считаем проигнорированным: printed=false, а не ложный успех', async () => {
    // Так выглядит sandbox без `allow-modals`: Chrome выбрасывает вызов без
    // ошибки и без диалога. Раньше по таймауту здесь возвращалось `true`, и
    // запасной путь не вызывался никогда — пользователь видел «ничего не происходит».
    printTakes(0);
    await expect(printHtmlDocument('<p>x</p>')).resolves.toMatchObject({ printed: false });
    expect(BLOCKING_PRINT_MS).toBeGreaterThan(0);
  });

  it('считает печать состоявшейся, если фрейм прислал afterprint во время печати', async () => {
    printSpy.mockImplementation(() => {
      document.querySelector('iframe')?.contentWindow?.dispatchEvent(new Event('afterprint'));
    });
    await expect(printHtmlDocument('<p>x</p>')).resolves.toMatchObject({ printed: true });
  });

  it('убирает фрейм после печати, а не оставляет его в DOM', async () => {
    printTakes(0);
    await printHtmlDocument('<p>x</p>');
    expect(frames().length).toBeGreaterThan(0);
    await vi.advanceTimersByTimeAsync(1000);
    expect(frames().length).toBe(0);
  });

  it('закрывает предыдущий фрейм, если печать вызвали повторно', async () => {
    printTakes(0);
    const first = printHtmlDocument('<p>1</p>');
    expect(frames().length).toBe(1);
    const second = printHtmlDocument('<p>2</p>');
    expect(frames().length).toBe(1);
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

    await expect(printHtmlDocument('<p>x</p>')).resolves.toMatchObject({ printed: false });
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
