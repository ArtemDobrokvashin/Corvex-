import { useI18n } from '../../../i18n';

/**
 * Встроенный просмотрщик Chrome рисует PDF из blob: URL. PDFium не исполняет
 * JavaScript из документа, а HTML-вложения мы вообще не открываем, поэтому
 * дополнительный sandbox здесь ломает просмотрщик сильнее, чем защищает.
 */
export default function PdfPreview({ url, fileName }: { url: string; fileName: string }) {
  const { t } = useI18n();
  return (
    <iframe
      title={`${t('Предпросмотр PDF')}: ${fileName}`}
      src={url}
      data-print-frame="pdf"
      className="h-full w-full border-0 bg-white"
      referrerPolicy="no-referrer"
    />
  );
}
