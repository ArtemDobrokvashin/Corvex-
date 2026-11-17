import { useEffect, useId, useRef, useState } from 'react';
import {
  ChevronLeft,
  ChevronRight,
  Download,
  FileAudio,
  FileImage,
  FileQuestion,
  FileSpreadsheet,
  FileText,
  FileVideo,
  FileWarning,
  Loader2,
  RotateCcw,
  ShieldAlert,
  ShieldCheck,
  ShieldQuestion,
  X,
  type LucideIcon,
} from 'lucide-react';
import { formatBytes } from '../../api/driveApi';
import { localizeApiDetail, useI18n } from '../../i18n';
import { useModalFocus } from '../../components/useModalFocus';
import PreviewMessage from './PreviewMessage';
import ImagePreview from './renderers/ImagePreview';
import MediaPreview from './renderers/MediaPreview';
import PdfPreview from './renderers/PdfPreview';
import TablePreview from './renderers/TablePreview';
import TextPreview from './renderers/TextPreview';
import { previewFailure } from './previewFailure';
import { isPreviewTooLarge, previewKindFor, previewSizeLimit, type PreviewKind } from './previewKind';
import type { PreviewSource } from './types';
import { usePreviewBlob } from './usePreviewBlob';

function iconForKind(kind: PreviewKind): LucideIcon {
  switch (kind) {
    case 'image':
      return FileImage;
    case 'table':
      return FileSpreadsheet;
    case 'audio':
      return FileAudio;
    case 'video':
      return FileVideo;
    case 'html':
      return FileWarning;
    case 'office':
      return FileText;
    case 'text':
    case 'pdf':
      return FileText;
    default:
      return FileQuestion;
  }
}

/**
 * Полноэкранный просмотр файла «как в Gmail»: Esc или клик по фону закрывают,
 * ←/→ переключают вложения письма, скачивание остаётся отдельным действием.
 * Файл не открывается напрямую по URL: сервер отдаёт его как octet-stream и
 * только с авторизацией, поэтому blob получает загрузчик источника.
 */
export default function FilePreviewDialog({ items, index, onIndexChange, onClose }: {
  items: PreviewSource[];
  index: number;
  onIndexChange: (index: number) => void;
  onClose: () => void;
}) {
  const { t, locale } = useI18n();
  const source = items[index];
  const kind = previewKindFor(source.contentType, source.fileName);
  const tooLarge = isPreviewTooLarge(kind, source.sizeBytes);
  const { state, reload } = usePreviewBlob(source, kind);
  const panelRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const [downloadError, setDownloadError] = useState(false);
  const [downloading, setDownloading] = useState(false);
  useModalFocus(panelRef);

  // Диалог занимает весь экран: фон не должен прокручиваться под ним.
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  useEffect(() => {
    setDownloadError(false);
    setDownloading(false);
  }, [index]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        onClose();
      } else if (event.key === 'ArrowLeft' && index > 0) {
        event.preventDefault();
        onIndexChange(index - 1);
      } else if (event.key === 'ArrowRight' && index < items.length - 1) {
        event.preventDefault();
        onIndexChange(index + 1);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [index, items.length, onClose, onIndexChange]);

  const scanUnconfirmed = Boolean(source.avStatus) && source.avStatus !== 'CLEAN';
  const failure = state.status === 'error' ? previewFailure(state.error, scanUnconfirmed) : null;
  const failureDetail = failure?.detail ? localizeApiDetail(failure.detail, locale) : '';
  const blocked = failure?.kind === 'blocked' || failure?.kind === 'forbidden' || failure?.kind === 'not-found';

  const startDownload = async () => {
    if (!source.download || downloading) return;
    setDownloadError(false);
    setDownloading(true);
    try {
      await source.download();
    } catch {
      setDownloadError(true);
    } finally {
      setDownloading(false);
    }
  };

  let body;
  if (kind === 'html') {
    body = (
      <PreviewMessage icon={FileWarning} title={t('HTML-вложения не открываются внутри приложения')}>
        {t('Это чужой интерактивный документ, поэтому мы не запускаем его в Corvex. Скачайте файл, чтобы открыть его в отдельной программе или песочнице.')}
      </PreviewMessage>
    );
  } else if (kind === 'office') {
    body = (
      <PreviewMessage icon={FileText} title={t('Предпросмотр документов Office пока недоступен')}>
        {t('Word, Excel и PowerPoint требуют серверного конвертера. Пока такой документ можно только скачать — откройте его в своей программе.')}
      </PreviewMessage>
    );
  } else if (kind === 'unsupported') {
    body = (
      <PreviewMessage icon={FileQuestion} title={t('Этот формат нельзя открыть в браузере')}>
        {t('Скачайте файл, чтобы открыть его в подходящей программе.')}
      </PreviewMessage>
    );
  } else if (tooLarge) {
    const limit = previewSizeLimit(kind);
    body = (
      <PreviewMessage icon={FileWarning} title={t('Файл слишком большой для предпросмотра')}>
        {limit !== null
          ? t('Максимум для просмотра: {size}. Скачайте файл, чтобы открыть его.', { size: formatBytes(limit, locale) })
          : t('Скачайте файл, чтобы открыть его.')}
      </PreviewMessage>
    );
  } else if (failure) {
    if (failure.kind === 'blocked') {
      body = (
        <PreviewMessage icon={ShieldAlert} title={t('Антивирус заблокировал файл')}>
          {failureDetail || t('Файл не прошёл антивирусную проверку. Просмотр и скачивание заблокированы.')}
        </PreviewMessage>
      );
    } else if (failure.kind === 'scan-unavailable') {
      body = (
        <PreviewMessage icon={ShieldQuestion} title={t('Антивирус временно недоступен')}>
          {t('Не удалось проверить файл перед открытием. Повторите попытку через минуту.')}
          <div className="mt-3">
            <button type="button" className="btn-secondary h-8 px-3 text-xs" onClick={reload}>
              <RotateCcw size={14} />{t('Повторить')}
            </button>
          </div>
        </PreviewMessage>
      );
    } else if (failure.kind === 'forbidden') {
      body = (
        <PreviewMessage icon={ShieldAlert} title={t('Нет доступа к файлу')}>
          {failureDetail || t('У вас нет прав на этот файл.')}
        </PreviewMessage>
      );
    } else if (failure.kind === 'not-found') {
      body = (
        <PreviewMessage icon={FileQuestion} title={t('Файл не найден или уже удалён')} />
      );
    } else {
      body = (
        <PreviewMessage icon={FileWarning} title={t('Не удалось открыть файл')}>
          {failureDetail || t('Проверьте соединение и попробуйте ещё раз.')}
          <div className="mt-3">
            <button type="button" className="btn-secondary h-8 px-3 text-xs" onClick={reload}>
              <RotateCcw size={14} />{t('Повторить')}
            </button>
          </div>
        </PreviewMessage>
      );
    }
  } else if (state.status === 'ready') {
    if (kind === 'image') body = <ImagePreview url={state.url} fileName={source.fileName} />;
    else if (kind === 'pdf') body = <PdfPreview url={state.url} fileName={source.fileName} />;
    else if (kind === 'text') body = <TextPreview blob={state.blob} fileName={source.fileName} />;
    else if (kind === 'table') {
      body = <TablePreview blob={state.blob} fileName={source.fileName} contentType={source.contentType} />;
    }
    else if (kind === 'audio' || kind === 'video') {
      body = <MediaPreview url={state.url} kind={kind} fileName={source.fileName} />;
    } else body = null;
  } else {
    body = (
      <div role="status" className="flex h-full items-center justify-center gap-2 px-4 text-sm text-muted">
        <Loader2 size={16} className="animate-spin" aria-hidden="true" />
        {scanUnconfirmed ? t('Проверяем антивирусом и загружаем…') : t('Загружаем файл…')}
      </div>
    );
  }

  const KindIcon = iconForKind(kind);

  return (
    <div
      className="fixed inset-0 z-[80] flex items-stretch justify-center bg-ink/60 backdrop-blur-[2px] sm:items-center sm:p-4"
      onMouseDown={(event) => {
        if (event.button === 0 && event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        data-autofocus
        className="flex h-dvh w-full flex-col overflow-hidden bg-surface outline-none sm:h-[min(92dvh,900px)] sm:max-w-6xl sm:rounded-2xl sm:border sm:border-line sm:shadow-pop"
      >
        <header className="flex shrink-0 items-center gap-2 border-b border-line px-3 py-2.5 sm:px-4">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent-dark" aria-hidden="true">
            <KindIcon size={18} />
          </span>
          <div className="min-w-0 flex-1">
            <h2 id={titleId} className="truncate text-[14px] font-semibold text-ink" title={source.fileName}>
              {source.fileName}
            </h2>
            <p className="mt-0.5 flex min-w-0 items-center gap-x-2 text-[11.5px] text-faint">
              {source.sizeBytes != null && <span>{formatBytes(source.sizeBytes, locale)}</span>}
              {items.length > 1 && (
                <span aria-live="polite" className="tabular-nums">{index + 1} / {items.length}</span>
              )}
              {source.avStatus === 'CLEAN' && (
                <span
                  className="inline-flex min-w-0 items-center gap-1 text-success"
                  role="img"
                  aria-label={t('Проверено антивирусом')}
                  title={t('Проверено антивирусом')}
                >
                  <ShieldCheck size={13} aria-hidden="true" />
                  <span className="hidden truncate sm:inline">{t('Проверено антивирусом')}</span>
                </span>
              )}
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-1">
            {items.length > 1 && (
              <>
                <button
                  type="button"
                  className="icon-btn-sm"
                  aria-label={t('Предыдущий файл')}
                  disabled={index === 0}
                  onClick={() => onIndexChange(index - 1)}
                >
                  <ChevronLeft size={17} />
                </button>
                <button
                  type="button"
                  className="icon-btn-sm"
                  aria-label={t('Следующий файл')}
                  disabled={index === items.length - 1}
                  onClick={() => onIndexChange(index + 1)}
                >
                  <ChevronRight size={17} />
                </button>
              </>
            )}
            {source.download && !blocked && (
              <button
                type="button"
                className="btn-secondary h-8 px-2.5 text-xs"
                aria-label={t('Скачать')}
                title={t('Скачать')}
                disabled={downloading}
                onClick={() => void startDownload()}
              >
                {downloading ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} />}
                <span className="hidden sm:inline">{t('Скачать')}</span>
              </button>
            )}
            <button
              type="button"
              className="icon-btn"
              aria-label={t('Закрыть')}
              title={t('Закрыть')}
              onClick={onClose}
            >
              <X size={18} />
            </button>
          </div>
        </header>

        <div className="relative min-h-0 flex-1 bg-canvas">{body}</div>

        {downloadError && (
          <p role="alert" className="shrink-0 border-t border-line bg-surface px-3 py-2 text-[12px] text-danger sm:px-4">
            {t('Не удалось скачать файл. Проверьте соединение и попробуйте ещё раз.')}
          </p>
        )}
      </div>
    </div>
  );
}
