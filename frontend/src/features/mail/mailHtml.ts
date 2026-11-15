import DOMPurify from 'dompurify';

export interface MailHtmlOptions {
  /** Разрешение на внешние изображения выдаёт пользователь кнопкой; по умолчанию сеть закрыта. */
  allowRemoteImages?: boolean;
}

/** Есть ли в письме внешние картинки, которые требует отдельного разрешения. */
export function hasRemoteImages(html: string): boolean {
  if (!html) return false;
  const container = document.createElement('div');
  container.innerHTML = html;
  return Array.from(container.querySelectorAll('img')).some((img) => /^https?:\/\//i.test((img.getAttribute('src') ?? '').trim()));
}

/** Санитизация не разрешает исполнение кода письма, переходы и сеть без разрешения. */
export function sanitizeMailHtml(html: string, blockedImageLabel: string, options: MailHtmlOptions = {}): string {
  const allowRemote = options.allowRemoteImages === true;
  const clean = DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'base', 'meta', 'link', 'style'],
    FORBID_ATTR: ['href', 'srcset', 'background', 'action', 'formaction', 'target'],
  });
  const container = document.createElement('div');
  container.innerHTML = clean;
  for (const button of container.querySelectorAll('button')) {
    button.setAttribute('type', 'button');
    button.setAttribute('disabled', '');
  }
  for (const img of container.querySelectorAll('img')) {
    const src = (img.getAttribute('src') ?? '').trim();
    const embedded = /^data:image\/(?:png|jpeg|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(src);
    const remote = /^https?:\/\//i.test(src);
    if (embedded) continue;
    if (allowRemote && remote) {
      // Explicit consent only: the sender can see the reader's address.
      img.setAttribute('referrerpolicy', 'no-referrer');
      img.setAttribute('loading', 'lazy');
      img.removeAttribute('width');
      img.removeAttribute('height');
      continue;
    }
    const placeholder = document.createElement('span');
    placeholder.className = 'corvex-blocked-image';
    placeholder.textContent = img.getAttribute('alt')?.trim() || blockedImageLabel;
    img.replaceWith(placeholder);
  }
  return container.innerHTML;
}

/** Isolated opaque-origin frame; only the trusted nonce-bearing resize script runs. */
export function mailHtmlDocument(html: string, nonce: string, blockedImageLabel: string, options: MailHtmlOptions = {}): string {
  if (!/^[0-9a-f]{32}$/.test(nonce)) throw new Error('Invalid frame nonce');
  const clean = sanitizeMailHtml(html, blockedImageLabel, options);
  const imageSources = options.allowRemoteImages === true ? "data: https: http:" : 'data:';
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${imageSources}; style-src 'unsafe-inline'; script-src 'nonce-${nonce}'; base-uri 'none'; form-action 'none'"><style>html,body{margin:0;padding:0}body{font:15px/1.7 system-ui,sans-serif;overflow-wrap:anywhere;color:#333}img{max-width:100%;height:auto}table{max-width:100%}.corvex-blocked-image{display:inline-block;word-break:normal;overflow-wrap:normal;white-space:nowrap;border:1px solid #ddd;background:#f6f6f6;color:#555;padding:4px 8px;font:13px/1.4 system-ui,sans-serif}</style></head><body>${clean}<script nonce="${nonce}">(()=>{const send=()=>parent.postMessage({type:'corvex-mail-height',nonce:'${nonce}',height:Math.ceil(Math.max(document.body.scrollHeight,document.body.offsetHeight))},'*');new ResizeObserver(send).observe(document.body);window.addEventListener('load',send);send()})()</script></body></html>`;
}
