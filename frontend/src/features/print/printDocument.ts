/**
 * Сборка документов для печати. Два независимых сценария:
 * - `mailPrintDocument` — письмо целиком: шапка (от/кому/копия/дата/тема) + тело;
 * - `attachmentPrintDocument` — один файл из предпросмотра.
 *
 * Всё, что приходит из письма, экранируется или проходит через `sanitizeMailHtml`.
 * Печать не должна становиться обходом защиты от трекинга: внешние картинки
 * остаются заблокированными, пока пользователь не разрешил их на экране.
 */

import { sanitizeMailHtml } from '../mail/mailHtml';

export interface MailPrintInput {
  subject: string;
  from: string;
  to: string;
  cc?: string;
  date: string;
  /** HTML-тело письма. Если пусто — печатается `text`. */
  html?: string;
  text?: string;
  /** Имена и размеры вложений: печатаем перечень, а не сами файлы. */
  attachments?: string[];
  /** Разрешение на внешние картинки, выданное пользователем на экране письма. */
  allowRemoteImages?: boolean;
  /** Подписи, уже переведённые вызывающим кодом. */
  labels: { from: string; to: string; cc: string; date: string; attachments: string };
}

/** Минимальная печатная типографика: без внешних ресурсов и без скриптов. */
const PRINT_CSS = `
  @page { margin: 18mm 15mm; }
  html, body { margin: 0; padding: 0; background: #fff; }
  body { font: 12pt/1.55 Georgia, 'Times New Roman', serif; color: #111; }
  .meta { border-bottom: 1px solid #ccc; padding-bottom: 8mm; margin-bottom: 8mm; }
  .meta h1 { font: 600 15pt/1.3 system-ui, sans-serif; margin: 0 0 4mm; overflow-wrap: anywhere; }
  .meta dl { display: grid; grid-template-columns: max-content 1fr; gap: 1mm 4mm; margin: 0; }
  .meta dt { font: 500 9pt/1.4 system-ui, sans-serif; color: #555; }
  .meta dd { margin: 0; overflow-wrap: anywhere; }
  .body img { max-width: 100%; height: auto; }
  .body table { max-width: 100%; border-collapse: collapse; }
  .body pre { white-space: pre-wrap; overflow-wrap: anywhere; }
  .corvex-blocked-image { border: 1px solid #ddd; padding: 1mm 2mm; font: 9pt system-ui, sans-serif; color: #555; }
  .attachments { margin-top: 8mm; border-top: 1px solid #ccc; padding-top: 3mm; font: 9pt/1.5 system-ui, sans-serif; color: #333; }
  .attachments ul { margin: 1mm 0 0; padding-left: 5mm; }
  .footer { margin-top: 10mm; font: 8pt/1.4 system-ui, sans-serif; color: #777; }
  @media print { a { color: inherit; text-decoration: none; } }
`;

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function textToHtml(text: string): string {
  return text
    .split('\n')
    .map((line) => (line.trim() ? `<p>${escapeHtml(line)}</p>` : ''))
    .join('');
}

function documentShell(title: string, inner: string): string {
  return `<!doctype html><html><head><meta charset="utf-8">`
    + `<title>${escapeHtml(title)}</title><style>${PRINT_CSS}</style></head><body>${inner}</body></html>`;
}

/** Документ «письмо целиком»: то, чего не даёт кнопка печати у просмотрщика PDF. */
export function mailPrintDocument(input: MailPrintInput): string {
  const blockedLabel = 'Изображение заблокировано';
  const body = input.html && input.html.trim()
    ? sanitizeMailHtml(input.html, blockedLabel, { allowRemoteImages: input.allowRemoteImages === true })
    : textToHtml(input.text ?? '');
  const rows: string[] = [
    `<dt>${escapeHtml(input.labels.from)}</dt><dd>${escapeHtml(input.from)}</dd>`,
    `<dt>${escapeHtml(input.labels.to)}</dt><dd>${escapeHtml(input.to)}</dd>`,
  ];
  if (input.cc && input.cc.trim()) {
    rows.push(`<dt>${escapeHtml(input.labels.cc)}</dt><dd>${escapeHtml(input.cc)}</dd>`);
  }
  rows.push(`<dt>${escapeHtml(input.labels.date)}</dt><dd>${escapeHtml(input.date)}</dd>`);
  const attachments = input.attachments?.length
    ? `<section class="attachments"><div>${escapeHtml(input.labels.attachments)}</div><ul>`
      + input.attachments.map((name) => `<li>${escapeHtml(name)}</li>`).join('')
      + '</ul></section>'
    : '';
  return documentShell(input.subject || 'mail', `<article>`
    + `<header class="meta"><h1>${escapeHtml(input.subject || '')}</h1><dl>${rows.join('')}</dl></header>`
    + `<div class="body">${body}</div>${attachments}`
    + '</article>');
}

export interface AttachmentPrintInput {
  /**
   * Имя файла: идёт только в `alt` картинки и в `<title>` документа (заголовок
   * вкладки и имя PDF по умолчанию), но не в тело листа. На бумаге имя файла
   * ничего не добавляет: пользователь и так знает, что открывал.
   */
  fileName: string;
  /** Готовый HTML: текст в `<pre>`, таблица, и так далее. Уже экранирован вызывающим кодом. */
  html?: string;
  /** Картинка как data: URL — печатается без повторной загрузки из сети. */
  imageDataUrl?: string;
  /** Абзацы обычного текста. Экранируются здесь. */
  text?: string;
  /** Подпись внизу листа: размер. Имени файла здесь быть не должно — это дубль шапки. */
  footer?: string;
}

/**
 * Документ «файл из предпросмотра»: PDF, картинка, текст, таблица.
 *
 * Без шапки с именем файла. Раньше имя печаталось дважды — заголовком сверху и
 * подписью снизу, — и это выглядело как ошибка вёрстки. Теперь имени в теле
 * листа нет вовсе.
 */
export function attachmentPrintDocument(input: AttachmentPrintInput): string {
  const parts: string[] = [];
  if (input.imageDataUrl) {
    parts.push(`<img src="${escapeHtml(input.imageDataUrl)}" alt="${escapeHtml(input.fileName)}" style="max-width:100%;height:auto">`);
  } else if (input.html) {
    parts.push(`<div class="body">${input.html}</div>`);
  } else if (input.text !== undefined) {
    parts.push(`<div class="body">${textToHtml(input.text)}</div>`);
  }
  if (input.footer) parts.push(`<div class="footer">${escapeHtml(input.footer)}</div>`);
  return documentShell(input.fileName, parts.join(''));
}

/** `<pre>` для текстовых файлов: переносы сохраняются, длинные строки не уезжают за лист. */
export function textPrintHtml(text: string): string {
  return `<pre style="white-space:pre-wrap;overflow-wrap:anywhere;font:10pt/1.45 ui-monospace,SFMono-Regular,Menlo,monospace;margin:0">${escapeHtml(text)}</pre>`;
}

/** Таблица CSV/TSV для печати: рамки нужны на бумаге, в отличие от экрана. */
export function tablePrintHtml(rows: string[][], truncated: boolean): string {
  const head = rows[0] ?? [];
  const tail = rows.slice(1);
  const cell = (tag: 'th' | 'td', value: string) =>
    `<${tag} style="border:1px solid #bbb;padding:1mm 2mm;text-align:left;vertical-align:top">${escapeHtml(value)}</${tag}>`;
  const headRow = head.length ? `<thead><tr>${head.map((value) => cell('th', value)).join('')}</tr></thead>` : '';
  const bodyRows = tail.map((row) => `<tr>${row.map((value) => cell('td', value)).join('')}</tr>`).join('');
  const note = truncated ? '<p style="font:8pt system-ui,sans-serif;color:#777">Показаны не все строки.</p>' : '';
  return `<table style="border-collapse:collapse;font:9pt/1.4 system-ui,sans-serif;width:100%">${headRow}<tbody>${bodyRows}</tbody></table>${note}`;
}
