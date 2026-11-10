import { FileCheck2 } from 'lucide-react';
import type { MailAttachmentView } from '../../api/mailApi';
import { useI18n } from '../../i18n';

function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function MailAttachments({ demoMode, attachments, busy, onDownload }: {
  demoMode: boolean;
  attachments?: MailAttachmentView[];
  busy: boolean;
  onDownload: (attachment: MailAttachmentView) => void;
}) {
  const { t } = useI18n();
  if (demoMode) return <p className="mt-6 text-sm text-muted">{t('Вложение недоступно в демонстрационном режиме')}</p>;
  if (!attachments) return <p className="mt-6 text-sm text-muted">{t('Загрузка вложений…')}</p>;
  if (!attachments.length) return <p className="mt-6 text-sm text-muted">{t('Вложения не найдены')}</p>;
  return <div className="mt-6 space-y-2">{attachments.map((attachment) => (
    <div key={attachment.partIndex} className="flex items-center gap-3 rounded-xl border border-line bg-chrome px-4 py-3">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent-dark"><FileCheck2 size={19} /></span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium" title={attachment.fileName}>{attachment.fileName}</div>
        <div className="text-xs text-muted">{fileSize(attachment.sizeBytes)} · {t(attachment.avStatus === 'CLEAN' ? 'Письмо проверено антивирусом: угрозы не обнаружены'
          : attachment.avStatus === 'INFECTED' ? 'Антивирус обнаружил угрозу'
          : attachment.avStatus === 'PENDING' ? 'Ожидает проверки антивирусом'
          : attachment.avStatus === 'ERROR' ? 'Ошибка антивирусной проверки'
          : 'Антивирусная проверка не подтверждена')}</div>
      </div>
      <button type="button" className="btn-secondary h-9 px-3 text-[13px]"
        disabled={attachment.avStatus !== 'CLEAN' || busy}
        onClick={() => onDownload(attachment)}>{t('Скачать')}</button>
    </div>
  ))}</div>;
}
