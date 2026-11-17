import { ApiError } from '../../api/httpClient';

export type PreviewFailureKind = 'blocked' | 'scan-unavailable' | 'forbidden' | 'not-found' | 'network';

export interface PreviewFailure {
  kind: PreviewFailureKind;
  detail: string;
}

/**
 * Ошибки выдачи файла. Для писем сервер сам сканирует вложение, поэтому 403/503
 * при непроверенном статусе означают антивирус, а не поломку интерфейса.
 */
export function previewFailure(error: unknown, avScanUnconfirmed = false): PreviewFailure {
  if (error instanceof ApiError) {
    const detail = error.detail?.trim() ?? '';
    if (error.status === 403) {
      return { kind: avScanUnconfirmed ? 'blocked' : 'forbidden', detail };
    }
    if (error.status === 503 && avScanUnconfirmed) {
      return { kind: 'scan-unavailable', detail };
    }
    if (error.status === 404) return { kind: 'not-found', detail };
    return {
      kind: 'network',
      detail: detail || '',
    };
  }
  const detail = error instanceof Error ? error.message : '';
  return { kind: 'network', detail };
}
