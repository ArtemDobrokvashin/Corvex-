/**
 * Определение способа предпросмотра по MIME и расширению.
 *
 * Логика намеренно отделена от UI: тип файла может прийти как
 * `application/octet-stream` (почтовые вложения), без MIME (чат) или с
 * обманчивым расширением, поэтому решение проверяется тестами.
 */

export type PreviewKind =
  | 'image'
  | 'pdf'
  | 'text'
  | 'table'
  | 'audio'
  | 'video'
  | 'office'
  | 'html'
  | 'unsupported';

/** Лимиты подобраны под Chrome 109 и разумное потребление памяти вкладки. */
export const PREVIEW_LIMITS = {
  text: 2 * 1024 * 1024,
  image: 25 * 1024 * 1024,
  pdf: 50 * 1024 * 1024,
  media: 100 * 1024 * 1024,
  /** Серверный конвертер Office не принимает файлы больше 25 МБ. */
  office: 25 * 1024 * 1024,
} as const;

const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'avif', 'svg', 'ico']);

const TEXT_EXT = new Set([
  'txt', 'log', 'json', 'xml', 'yaml', 'yml', 'md', 'markdown', 'ini', 'conf', 'cfg',
  'js', 'mjs', 'cjs', 'ts', 'tsx', 'jsx', 'css', 'scss', 'less', 'sh', 'bash', 'sql',
  'java', 'kt', 'kts', 'py', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cs', 'php', 'rb',
]);

const AUDIO_EXT = new Set(['mp3', 'wav', 'ogg', 'oga', 'm4a', 'aac', 'flac', 'opus']);

const VIDEO_EXT = new Set(['mp4', 'webm', 'ogv', 'mov', 'm4v']);
const HTML_EXT = new Set(['html', 'htm', 'xhtml', 'mhtml', 'mht']);
const OFFICE_EXT = new Set(['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'odt', 'ods', 'odp', 'rtf']);

const OFFICE_MIME = new Set([
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.ms-powerpoint',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/vnd.oasis.opendocument.text',
  'application/vnd.oasis.opendocument.spreadsheet',
  'application/vnd.oasis.opendocument.presentation',
  'application/rtf',
  'text/rtf',
]);

/** Коды type для тех случаев, когда сервер отдаёт только имя файла (чат). */
const MIME_BY_EXT: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  bmp: 'image/bmp',
  avif: 'image/avif',
  svg: 'image/svg+xml',
  ico: 'image/x-icon',
  pdf: 'application/pdf',
  txt: 'text/plain',
  log: 'text/plain',
  md: 'text/markdown',
  csv: 'text/csv',
  tsv: 'text/tab-separated-values',
  json: 'application/json',
  xml: 'application/xml',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  ogg: 'audio/ogg',
  m4a: 'audio/mp4',
  flac: 'audio/flac',
  mp4: 'video/mp4',
  webm: 'video/webm',
  mov: 'video/quicktime',
};

export function fileNameExtension(fileName: string): string {
  const base = fileName.trim().toLowerCase().split(/[\\/]/).pop() ?? '';
  const dot = base.lastIndexOf('.');
  return dot > 0 && dot < base.length - 1 ? base.slice(dot + 1) : '';
}

function normalizedMime(contentType: string | null | undefined): string {
  return (contentType ?? '').split(';')[0].trim().toLowerCase();
}

export function mimeFromFileName(fileName: string): string | null {
  return MIME_BY_EXT[fileNameExtension(fileName)] ?? null;
}

export function previewKindFor(
  contentType: string | null | undefined,
  fileName: string,
): PreviewKind {
  const mime = normalizedMime(contentType);
  const ext = fileNameExtension(fileName);

  // HTML не открываем никогда: это чужой исполняемый документ.
  if (mime === 'text/html' || mime === 'application/xhtml+xml' || HTML_EXT.has(ext)) return 'html';

  if (mime === 'application/pdf' || ext === 'pdf') return 'pdf';

  if (OFFICE_MIME.has(mime) || OFFICE_EXT.has(ext)) return 'office';

  // Таблица важнее общего text/*: CSV показывается сеткой, а не простынёй.
  if (mime === 'text/csv' || mime === 'text/tab-separated-values' || ext === 'csv' || ext === 'tsv') {
    return 'table';
  }

  if (mime.startsWith('image/') || IMAGE_EXT.has(ext)) return 'image';
  if (mime.startsWith('audio/') || AUDIO_EXT.has(ext)) return 'audio';
  if (mime.startsWith('video/') || VIDEO_EXT.has(ext)) return 'video';

  if (mime.startsWith('text/') || TEXT_EXT.has(ext)) return 'text';

  return 'unsupported';
}

/** `null` — у формата нет ограничения сверху (например, его всё равно не рендерим). */
export function previewSizeLimit(kind: PreviewKind): number | null {
  switch (kind) {
    case 'image':
      return PREVIEW_LIMITS.image;
    case 'pdf':
      return PREVIEW_LIMITS.pdf;
    case 'text':
    case 'table':
      return PREVIEW_LIMITS.text;
    case 'audio':
    case 'video':
      return PREVIEW_LIMITS.media;
    case 'office':
      return PREVIEW_LIMITS.office;
    default:
      return null;
  }
}

export function isPreviewTooLarge(kind: PreviewKind, sizeBytes: number | null | undefined): boolean {
  const limit = previewSizeLimit(kind);
  if (limit === null || sizeBytes == null || !Number.isFinite(sizeBytes)) return false;
  return sizeBytes > limit;
}

/** HTML и Office открывают диалог с объяснением, остальные неподдерживаемые — нет. */
export function opensPreviewDialog(kind: PreviewKind): boolean {
  return kind !== 'unsupported';
}

/** Форматы, для которых нужен blob и рендерер. */
export function needsBlob(kind: PreviewKind): boolean {
  return kind === 'image' || kind === 'pdf' || kind === 'text' || kind === 'table'
    || kind === 'audio' || kind === 'video';
}
