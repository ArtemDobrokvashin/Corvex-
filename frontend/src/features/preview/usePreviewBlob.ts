import { useCallback, useEffect, useState } from 'react';
import { isPreviewTooLarge, needsBlob, type PreviewKind } from './previewKind';
import type { PreviewSource } from './types';

export type PreviewBlobState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ready'; url: string; blob: Blob }
  | { status: 'error'; error: unknown };

/**
 * Сервер отдаёт вложения как application/octet-stream (так безопаснее для
 * скачивания), но встроенный просмотрщик PDF выбирается именно по MIME,
 * поэтому blob пере-типизируется по заявленному contentType.
 */
export function previewBlobType(
  kind: PreviewKind,
  contentType: string | null | undefined,
  blobType: string,
): string {
  if (kind === 'pdf') return 'application/pdf';
  if (kind === 'text' || kind === 'table') return 'text/plain;charset=utf-8';
  const declared = (contentType ?? '').split(';')[0].trim();
  if (declared && declared !== 'application/octet-stream') return declared;
  if (blobType && blobType !== 'application/octet-stream') return blobType;
  return '';
}

function retype(blob: Blob, type: string): Blob {
  if (!type || blob.type === type) return blob;
  return new Blob([blob], { type });
}

export function usePreviewBlob(source: PreviewSource, kind: PreviewKind) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<PreviewBlobState>({ status: 'idle' });

  const reload = useCallback(() => setAttempt((value) => value + 1), []);

  useEffect(() => {
    if (!needsBlob(kind) || isPreviewTooLarge(kind, source.sizeBytes) || !source.load) {
      setState({ status: 'idle' });
      return undefined;
    }
    const controller = new AbortController();
    let cancelled = false;
    let objectUrl = '';
    setState({ status: 'loading' });

    source.load(controller.signal)
      .then((raw) => {
        if (cancelled) return;
        const typed = retype(raw, previewBlobType(kind, source.contentType, raw.type));
        objectUrl = URL.createObjectURL(typed);
        setState({ status: 'ready', url: objectUrl, blob: typed });
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        if ((error as { name?: string } | null)?.name === 'AbortError') return;
        setState({ status: 'error', error });
      });

    return () => {
      cancelled = true;
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [source, kind, attempt]);

  return { state, reload };
}
