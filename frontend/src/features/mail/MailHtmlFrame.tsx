import { useEffect, useMemo, useRef, useState } from 'react';
import { useI18n } from '../../i18n';
import { mailHtmlDocument } from './mailHtml';

export default function MailHtmlFrame({ html }: { html: string }) {
  const { t } = useI18n();
  const frame = useRef<HTMLIFrameElement>(null);
  const [height, setHeight] = useState(120);
  const nonce = useMemo(() => {
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
  }, [html]);
  const doc = useMemo(() => mailHtmlDocument(html, nonce, t('Изображение заблокировано')), [html, nonce, t]);
  useEffect(() => {
    setHeight(120);
    const receive = (event: MessageEvent) => {
      if (event.source !== frame.current?.contentWindow || event.data?.type !== 'corvex-mail-height' || event.data?.nonce !== nonce) return;
      if (typeof event.data.height !== 'number' || !Number.isFinite(event.data.height)) return;
      setHeight(Math.min(20000, Math.max(80, Math.ceil(event.data.height))));
    };
    window.addEventListener('message', receive);
    return () => window.removeEventListener('message', receive);
  }, [nonce]);
  return <iframe ref={frame} title={t('Содержимое HTML-письма')} sandbox="allow-scripts" referrerPolicy="no-referrer" srcDoc={doc} style={{ height }} className="mt-4 w-full border-0 bg-white" />;
}
