import DOMPurify from 'dompurify';

/** Недоверенное письмо остаётся в sandbox iframe без сети, скриптов и навигации. */
export function mailHtmlDocument(html: string): string {
  const clean = DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'base', 'meta', 'link', 'style'],
    FORBID_ATTR: ['href', 'srcset', 'background', 'action', 'formaction', 'target'],
  });
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><style>body{font:15px/1.7 system-ui,sans-serif;overflow-wrap:anywhere;color:#333}img{max-width:100%;height:auto}table{max-width:100%}</style></head><body>${clean}</body></html>`;
}
