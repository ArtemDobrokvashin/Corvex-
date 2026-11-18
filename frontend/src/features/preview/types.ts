/**
 * Описание файла для предпросмотра. Компоненты почты, диска и чата передают
 * свои загрузчики, поэтому диалог не знает про конкретные API.
 */
export interface PreviewSource {
  fileName: string;
  contentType?: string | null;
  sizeBytes?: number | null;
  /** Статус антивируса для вложений почты: CLEAN / PENDING / INFECTED / … */
  avStatus?: string | null;
  /** Загрузка исходника; signal отменяет запрос при закрытии диалога. */
  load?: (signal: AbortSignal) => Promise<Blob>;
  /**
   * PDF, подготовленный серверным конвертером (Office). Пока конвертера нет,
   * поле не передаётся и диалог показывает объяснение вместо просмотра.
   */
  loadPdf?: (signal: AbortSignal) => Promise<Blob>;
  /** Скачивание файла на устройство (не предпросмотр). */
  download?: () => void | Promise<void>;
}
