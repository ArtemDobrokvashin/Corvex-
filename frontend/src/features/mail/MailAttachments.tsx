import { Download, FileCheck2, ShieldAlert, ShieldCheck, ShieldQuestion } from 'lucide-react';
import type { MailAttachmentView } from '../../api/mailApi';
import { StatusBadge } from '../../components/ui';
import { useI18n } from '../../i18n';

function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function MailAttachments({
  demoMode,
  attachments,
  busy,
  onDownload,
  onDownloadAll,
  onPreview,
}: {
  demoMode: boolean;
  attachments?: MailAttachmentView[];
  busy: boolean;
  onDownload: (attachment: MailAttachmentView) => void;
  /** Пакетное скачивание: по одному файлу, если ZIP на сервере недоступен. */
  onDownloadAll?: () => void;
  /** Без колбэка карточка остаётся только для скачивания. */
  onPreview?: (attachment: MailAttachmentView) => void;
}) {
  const { t } = useI18n();
  if (demoMode) return <p className="mt-6 text-sm text-muted">{t('Вложение недоступно в демонстрационном режиме')}</p>;
  if (!attachments) return <p className="mt-6 text-sm text-muted">{t('Загрузка вложений…')}</p>;
  if (!attachments.length) return <p className="mt-6 text-sm text-muted">{t('Вложения не найдены')}</p>;

  return (
    <div className="mt-6">
      {attachments.length > 1 && onDownloadAll && (
        <div className="mb-2 flex items-center justify-between gap-2">
          <span className="text-[11px] font-semibold uppercase tracking-[0.04em] text-faint">
            {t('Вложений: {count}', { count: attachments.length })}
          </span>
          <button
            type="button"
            onClick={onDownloadAll}
            disabled={busy}
            className="inline-flex items-center gap-1.5 rounded-md border border-line-control bg-surface px-2 py-1 text-xs font-medium text-ink-soft transition hover:bg-canvas disabled:opacity-50"
          >
            <Download size={13} /> {t('Скачать все')}
          </button>
        </div>
      )}
    <div className="grid gap-2 sm:grid-cols-2">
      {attachments.map((attachment) => {
        const statusText = t(
          attachment.avStatus === 'CLEAN'
            ? 'Письмо проверено антивирусом: угрозы не обнаружены'
            : attachment.avStatus === 'INFECTED'
              ? 'Антивирус обнаружил угрозу'
              : attachment.avStatus === 'PENDING'
                ? 'Ожидает проверки антивирусом'
                : attachment.avStatus === 'ERROR'
                  ? 'Ошибка антивирусной проверки'
                  : 'Антивирусная проверка не подтверждена',
        );
        // На узком экране длинный вердикт антивируса нельзя прочитать целиком,
        // поэтому показываем короткую формулировку, а полную — от 640px.
        const shortStatusText = t(
          attachment.avStatus === 'CLEAN'
            ? 'Проверено'
            : attachment.avStatus === 'INFECTED'
              ? 'Угроза'
              : attachment.avStatus === 'PENDING'
                ? 'Ожидает проверки'
                : attachment.avStatus === 'ERROR'
                  ? 'Ошибка проверки'
                  : 'Не проверено',
        );
        const tone =
          attachment.avStatus === 'CLEAN'
            ? 'positive'
            : attachment.avStatus === 'INFECTED'
              ? 'critical'
              : 'warning';
        const StatusIcon =
          attachment.avStatus === 'CLEAN'
            ? ShieldCheck
            : attachment.avStatus === 'INFECTED'
              ? ShieldAlert
              : ShieldQuestion;

        const card = (
          <>
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent-dark">
              <FileCheck2 size={17} />
            </span>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[13px] font-medium text-ink" title={attachment.fileName}>
                {attachment.fileName}
              </div>
              <div className="mt-0.5 flex min-w-0 max-w-full flex-wrap items-center gap-1.5 text-xs text-muted tabular-nums">
                <span>{fileSize(attachment.sizeBytes)}</span>
                <StatusBadge tone={tone} className="min-w-0 max-w-full">
                  <StatusIcon size={11} />
                  <span className="truncate sm:hidden">{shortStatusText}</span>
                  <span className="hidden truncate sm:inline">{statusText}</span>
                </StatusBadge>
              </div>
            </div>
          </>
        );

        return (
          <div
            key={attachment.partIndex}
            className={`flex min-w-0 items-center rounded-xl border border-line-control bg-surface px-3 py-2 transition hover:border-[#CBD5E1] ${
              onPreview ? 'gap-2' : 'gap-3'
            }`}
          >
            {onPreview ? (
              <button
                type="button"
                className="flex min-w-0 flex-1 items-center gap-3 rounded-lg px-0.5 py-0.5 text-left transition hover:bg-canvas focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                aria-label={`${t('Открыть предпросмотр')}: ${attachment.fileName}`}
                title={t('Открыть предпросмотр')}
                onClick={() => onPreview(attachment)}
              >
                {card}
              </button>
            ) : card}
            <button
              type="button"
              className="btn-secondary h-8 shrink-0 px-2.5 text-xs disabled:cursor-not-allowed disabled:opacity-45"
              disabled={attachment.avStatus === 'INFECTED' || busy}
              aria-label={t(attachment.avStatus === 'CLEAN' ? 'Скачать' : 'Проверить и скачать')}
              onClick={() => onDownload(attachment)}
            >
              <Download size={13.5} />
              <span className="hidden sm:inline">{t(attachment.avStatus === 'CLEAN' ? 'Скачать' : 'Проверить и скачать')}</span>
            </button>
          </div>
        );
      })}
    </div>
    </div>
  );
}
