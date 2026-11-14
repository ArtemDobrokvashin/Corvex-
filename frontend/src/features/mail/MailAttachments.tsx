import { File, FileArchive, FileAudio, FileCode, FileImage, FileSpreadsheet, FileText, FileVideo } from 'lucide-react';
import type { MailAttachmentView } from '../../api/mailApi';
import { useI18n } from '../../i18n';

function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

// Icons are decorative hints based on the filename, never an AV verdict or MIME validation.
function fileIcon(fileName: string) {
  const extension = fileName.includes('.') ? fileName.split('.').pop()?.toLowerCase() : '';
  if (['pdf', 'doc', 'docx', 'txt'].includes(extension ?? '')) return FileText;
  if (['xls', 'xlsx', 'csv'].includes(extension ?? '')) return FileSpreadsheet;
  if (['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(extension ?? '')) return FileImage;
  if (['zip', 'rar', '7z', 'tar', 'gz'].includes(extension ?? '')) return FileArchive;
  if (['mp3', 'wav', 'ogg'].includes(extension ?? '')) return FileAudio;
  if (['mp4', 'mov', 'avi'].includes(extension ?? '')) return FileVideo;
  if (['js', 'ts', 'json', 'py', 'html', 'css'].includes(extension ?? '')) return FileCode;
  return File;
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
  return <div className="mt-6 space-y-2">{attachments.map((attachment) => {
    const Icon = fileIcon(attachment.fileName);
    return <div key={attachment.partIndex} className="flex items-center gap-3 rounded-xl border border-line bg-chrome px-4 py-3">
      <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent-dark"><Icon size={19} aria-hidden="true" /></span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium" title={attachment.fileName}>{attachment.fileName}</div>
        <div className="text-xs text-muted">{fileSize(attachment.sizeBytes)} · {t(attachment.avStatus === 'CLEAN' ? 'Письмо проверено антивирусом: угрозы не обнаружены'
          : attachment.avStatus === 'INFECTED' ? 'Антивирус обнаружил угрозу'
          : attachment.avStatus === 'PENDING' ? 'Ожидает проверки антивирусом'
          : attachment.avStatus === 'ERROR' ? 'Ошибка антивирусной проверки'
          : 'Антивирусная проверка не подтверждена')}</div>
      </div>
      <button type="button" className="btn-secondary h-9 px-3 text-[13px]"
        disabled={attachment.avStatus === 'INFECTED' || busy}
        onClick={() => onDownload(attachment)}>{t(attachment.avStatus === 'CLEAN' ? 'Скачать' : 'Проверить и скачать')}</button>
    </div>;
  })}</div>;
}
