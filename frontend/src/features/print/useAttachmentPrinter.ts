import { useCallback } from 'react';
import { parseDelimited } from '../preview/previewCsv';
import { decodeTextBlob } from '../preview/previewText';
import type { PreviewKind } from '../preview/previewKind';
import { openPrintWindow, printHtmlDocument } from './printing';
import { attachmentPrintDocument, tablePrintHtml, textPrintHtml } from './printDocument';

/**
 * Сколько строк CSV пускаем на бумагу. Экранный лимит (`parseDelimited`, 1000
 * строк) — защита вкладки от тормозов, а не свойство файла: печать обрывалась
 * на 1000-й строке, и последняя страница выглядела полной. Лимит на печать
 * свой, заметно выше, и обрезка честно пишется на листе.
 */
export const PRINT_TABLE_MAX_ROWS = 20_000;

/** Что печатать: `frame` — PDF уже открыт в iframe и печатает себя сам. */
export type PrintSource =
  | { kind: 'pdf-frame' }
  | { kind: 'html'; html: string }
  | { kind: 'unsupported' };

/**
 * Готовит печатный документ для файла из предпросмотра.
 *
 * PDF печатает сам встроенный просмотрщик Chrome — у него уже есть разметка
 * страниц. Картинки и текст разворачиваются в отдельный документ: иначе в
 * печать уедет модальное окно с интерфейсом.
 */
export function useAttachmentPrinter() {
  return useCallback(async (params: {
    kind: PreviewKind;
    fileName: string;
    blob?: Blob;
    imageUrl?: string;
    sizeLabel?: string;
  }): Promise<PrintSource> => {
    const { kind, fileName, blob, imageUrl, sizeLabel } = params;
    // Только размер: имя файла на листе не печатается ни в каком виде.
    const footer = sizeLabel;

    // Office уже приходит как PDF от серверного конвертера — печатается так же,
    // как обычный PDF: через встроенный просмотрщик.
    if (kind === 'pdf' || (kind === 'office' && blob)) return { kind: 'pdf-frame' };

    if (kind === 'image' && imageUrl) {
      // Картинка уже в blob: URL, повторно из сети её не тянем.
      const dataUrl = await blobToDataUrl(blob);
      const html = attachmentPrintDocument({
        fileName,
        imageDataUrl: dataUrl ?? imageUrl,
        footer,
      });
      const outcome = await printHtmlDocument(html);
      if (!outcome.printed) openPrintWindow(html);
      return { kind: 'html', html };
    }

    if ((kind === 'text' || kind === 'table') && blob) {
      const text = await decodeTextBlob(blob);
      let html: string;
      if (kind === 'table') {
        const table = parseDelimited(
          text,
          fileName.toLowerCase().endsWith('.tsv') ? '\t' : ',',
          PRINT_TABLE_MAX_ROWS,
        );
        html = attachmentPrintDocument({
          fileName,
          html: tablePrintHtml(table.rows, table.truncated),
          footer,
        });
      } else {
        html = attachmentPrintDocument({ fileName, html: textPrintHtml(text), footer });
      }
      const outcome = await printHtmlDocument(html);
      if (!outcome.printed) openPrintWindow(html);
      return { kind: 'html', html };
    }

    return { kind: 'unsupported' };
  }, []);
}

async function blobToDataUrl(blob?: Blob): Promise<string | null> {
  if (!blob) return null;
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : null);
    reader.onerror = () => resolve(null);
    reader.readAsDataURL(blob);
  });
}
