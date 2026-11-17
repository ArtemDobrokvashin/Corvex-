import { useEffect, useState } from 'react';
import { useI18n } from '../../../i18n';
import { parseDelimited } from '../previewCsv';
import { decodeTextBlob } from '../previewText';

export default function TablePreview({ blob, fileName, contentType }: {
  blob: Blob;
  fileName: string;
  contentType?: string | null;
}) {
  const { t } = useI18n();
  const [table, setTable] = useState<{ rows: string[][]; truncated: boolean } | null>(null);
  const [failed, setFailed] = useState(false);
  const tabSeparated = fileName.toLowerCase().endsWith('.tsv')
    || (contentType ?? '').toLowerCase().includes('tab-separated');
  const delimiter = tabSeparated ? '\t' : ',';

  useEffect(() => {
    let cancelled = false;
    setTable(null);
    setFailed(false);
    decodeTextBlob(blob)
      .then((text) => {
        if (!cancelled) setTable(parseDelimited(text, delimiter));
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [blob, delimiter]);

  if (failed) {
    return <p role="alert" className="px-4 py-6 text-sm text-danger">{t('Не удалось прочитать таблицу.')}</p>;
  }
  if (table === null) {
    return <p role="status" className="px-4 py-6 text-sm text-muted">{t('Читаем таблицу…')}</p>;
  }
  if (table.rows.length === 0) {
    return <p className="px-4 py-6 text-sm text-muted">{t('В файле нет данных.')}</p>;
  }

  const [head, ...body] = table.rows;

  return (
    <div className="flex h-full flex-col">
      <div className="min-h-0 flex-1 overflow-auto bg-surface">
        <table className="w-full border-collapse text-left text-[12.5px]" aria-label={`${t('Таблица файла')}: ${fileName}`}>
          <thead className="sticky top-0 bg-chrome">
            <tr>
              {head.map((cell, index) => (
                <th key={index} scope="col" className="whitespace-nowrap border-b border-line px-3 py-2 font-semibold text-ink">
                  {cell}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {body.map((row, rowIndex) => (
              <tr key={rowIndex} className="hover:bg-canvas">
                {row.map((cell, cellIndex) => (
                  <td key={cellIndex} className="max-w-[320px] truncate border-b border-line-subtle px-3 py-1.5 text-ink-soft" title={cell}>
                    {cell}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {table.truncated && (
        <p role="status" className="shrink-0 border-t border-line bg-chrome px-3 py-1.5 text-[12px] text-muted">
          {t('Показаны первые {count} строк.', { count: table.rows.length })}
        </p>
      )}
    </div>
  );
}
