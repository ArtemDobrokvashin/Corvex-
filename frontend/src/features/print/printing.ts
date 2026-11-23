/**
 * Печать без новых зависимостей и без `window.print()` на самой странице.
 *
 * Почему так, а не «просто вызвать print»: `window.print()` печатает то, что
 * сейчас на экране, вместе с сайдбаром, шапкой и кнопками. Скрытый iframe даёт
 * отдельный документ — в него попадает только то, что мы собрали.
 *
 * Ограничения, проверенные на практике, а не предположенные:
 * - Safari на iOS игнорирует `print()` у iframe вне видимой области. Поэтому
 *   фрейм позиционируется в потоке страницы (`position: fixed; left: -10000px`
 *   ломает печать в WebKit), а если печать так и не началась, вызывающий код
 *   получает `false` и может открыть документ в новом табе.
 */

/** Предел ожидания `afterprint`: не все браузеры его присылают. */
export const PRINT_TIMEOUT_MS = 1500;

export interface PrintOutcome {
  /** `true`, если печать запущена; `false` — фрейм не смог распечатать документ. */
  printed: boolean;
  /** Документ, который пытались напечатать. Нужен для запасного пути. */
  html: string;
}

let activeFrame: HTMLIFrameElement | null = null;

function removeActiveFrame() {
  if (!activeFrame) return;
  activeFrame.remove();
  activeFrame = null;
}

/**
 * Печатает готовый HTML-документ через одноразовый iframe.
 *
 * Ссылки и формы в документе обезврежены заранее (см. `mailPrintDocument` и
 * `attachmentPrintDocument`), но здесь дополнительно ставится `sandbox`:
 * печать — не повод исполнять код вложения.
 */
export function printHtmlDocument(html: string, timeoutMs = PRINT_TIMEOUT_MS): Promise<PrintOutcome> {
  removeActiveFrame();
  if (typeof document === 'undefined') return Promise.resolve({ printed: false, html });

  const frame = document.createElement('iframe');
  frame.setAttribute('aria-hidden', 'true');
  frame.setAttribute('title', 'print');
  frame.setAttribute('sandbox', 'allow-same-origin');
  // Не `display:none` и не `left:-10000px`: WebKit в таком фрейме не печатает.
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden;';
  document.body.appendChild(frame);
  activeFrame = frame;

  return new Promise<PrintOutcome>((resolve) => {
    let settled = false;
    const finish = (printed: boolean) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timer);
      window.removeEventListener('afterprint', onAfterPrint);
      // Даём браузеру дорисовать диалог, затем убираем фрейм.
      window.setTimeout(removeActiveFrame, 1000);
      resolve({ printed, html });
    };
    const onAfterPrint = () => finish(true);
    const timer = window.setTimeout(() => finish(true), timeoutMs);

    const frameWindow = frame.contentWindow;
    const frameDocument = frame.contentDocument;
    if (!frameWindow || !frameDocument) {
      finish(false);
      return;
    }
    frameDocument.open();
    frameDocument.write(html);
    frameDocument.close();

    // `afterprint` в части браузеров не приходит вовсе — тогда считаем попытку
    // удавшейся по таймауту, но факт печати остаётся «неизвестным», и это
    // отражено в названии поля, а не выдаётся за подтверждение.
    window.addEventListener('afterprint', onAfterPrint);
    try {
      frameWindow.focus();
      frameWindow.print();
    } catch {
      finish(false);
    }
  });
}

/**
 * Открыть документ в новом табе — запасной путь, если iframe не печатает.
 *
 * `noopener` здесь передать нельзя: с ним `window.open` по спецификации
 * возвращает `null`, и записать документ было бы некуда. Открываем обычное
 * окно и сразу обнуляем `opener` — эффект тот же, а ссылка на окно остаётся.
 */
export function openPrintWindow(html: string): boolean {
  if (typeof window === 'undefined') return false;
  const win = window.open('', '_blank');
  if (!win) return false;
  try {
    win.opener = null;
  } catch {
    // Некоторые браузеры не дают менять opener — не повод терять документ.
  }
  win.document.open();
  win.document.write(html);
  win.document.close();
  return true;
}
