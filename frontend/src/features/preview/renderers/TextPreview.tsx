import { useEffect, useState } from 'react';
import { WrapText } from 'lucide-react';
import { useI18n } from '../../../i18n';
import { decodeTextBlob } from '../previewText';

export default function TextPreview({ blob, fileName }: { blob: Blob; fileName: string }) {
  const { t } = useI18n();
  const [text, setText] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [wrap, setWrap] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setText(null);
    setFailed(false);
    decodeTextBlob(blob)
      .then((value) => {
        if (!cancelled) setText(value);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [blob]);

  if (failed) {
    return <p role="alert" className="px-4 py-6 text-sm text-danger">{t('Не удалось прочитать текст файла.')}</p>;
  }
  if (text === null) {
    return <p role="status" className="px-4 py-6 text-sm text-muted">{t('Читаем текст…')}</p>;
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex shrink-0 items-center gap-2 border-b border-line bg-chrome px-3 py-1.5">
        <button
          type="button"
          className={`inline-flex h-7 items-center gap-1.5 rounded-md px-2 text-[12px] font-medium transition ${
            wrap ? 'bg-accent-soft text-accent-dark' : 'text-muted hover:bg-canvas hover:text-ink'
          }`}
          aria-label={t('Перенос строк')}
          aria-pressed={wrap}
          title={t('Перенос строк')}
          onClick={() => setWrap((value) => !value)}
        >
          <WrapText size={14} />
          {t('Перенос строк')}
        </button>
      </div>
      <div className="min-h-0 flex-1 overflow-auto bg-surface">
        <pre
          aria-label={`${t('Текст файла')}: ${fileName}`}
          className={`min-h-full px-4 py-3 font-mono text-[12.5px] leading-relaxed text-ink ${
            wrap ? 'whitespace-pre-wrap break-words' : 'whitespace-pre'
          }`}
        >
          {text}
        </pre>
      </div>
    </div>
  );
}
