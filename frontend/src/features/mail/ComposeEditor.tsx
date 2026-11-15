import { forwardRef, useImperativeHandle, useState } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { Color, TextStyle } from '@tiptap/extension-text-style';
import DOMPurify from 'dompurify';
import { Bold, Italic, Underline, Strikethrough, Link as LinkIcon, List, ListOrdered, Code2 } from 'lucide-react';
import { useI18n } from '../../i18n';
import MailHtmlFrame from './MailHtmlFrame';

export interface ComposeEditorHandle { insertText: (text: string) => void }

// Only a small email-safe subset survives source editing/paste. No images or remote resources.
export function cleanComposeHtml(html: string, sourceMode = false): string {
  const cleaned = DOMPurify.sanitize(html, {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'b', 'em', 'i', 'u', 's', 'strike', 'del', 'span', 'a', 'ul', 'ol', 'li', 'blockquote', 'h1', 'h2', 'h3', ...(sourceMode ? ['div', 'button', 'table', 'thead', 'tbody', 'tr', 'th', 'td'] : [])],
    ALLOWED_ATTR: ['href', 'style'],
    ALLOW_DATA_ATTR: false,
  });
  const container = document.createElement('div');
  container.innerHTML = cleaned;
  container.querySelectorAll('*').forEach((element) => {
    const color = (element as HTMLElement).style.color;
    element.removeAttribute('style');
    if (element.tagName === 'SPAN' && /^#[0-9a-f]{6}$/i.test(color)) (element as HTMLElement).style.color = color;
    // CSSOM may canonicalize hex to rgb; accept only numeric RGB and convert it to hex.
    else if (element.tagName === 'SPAN' && /^rgb\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*\)$/i.test(color)) {
      const parts = color.match(/\d+/g)!.map(Number);
      if (parts.every((n) => n <= 255)) (element as HTMLElement).style.color = '#' + parts.map((n) => n.toString(16).padStart(2, '0')).join('');
    }
    if (element.hasAttribute('href') && !/^(https?:\/\/|mailto:)/i.test(element.getAttribute('href')!.trim())) element.removeAttribute('href');
  });
  return container.innerHTML;
}

export function composePlainText(html: string): string {
  const root = document.createElement('div');
  root.innerHTML = cleanComposeHtml(html, true);
  const walk = (node: Node): string => {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent || '';
    if (!(node instanceof HTMLElement)) return '';
    if (node.tagName === 'BR') return '\n';
    const content = Array.from(node.childNodes).map(walk).join('');
    if (node.tagName === 'A' && node.getAttribute('href')) {
      const href = node.getAttribute('href')!;
      return content === href ? content : `${content} (${href})`;
    }
    return /^(P|DIV|LI|BLOCKQUOTE|H[1-6])$/.test(node.tagName) ? content + '\n' : content;
  };
  return Array.from(root.childNodes).map(walk).join('').replace(/\n+$/, '');
}

const ComposeEditor = forwardRef<ComposeEditorHandle, { initialText: string; onChange: (text: string, html: string) => void }>(function ComposeEditor({ initialText, onChange }, ref) {
  const { t } = useI18n();
  const [source, setSource] = useState<string | null>(null);
  const [sourceEdited, setSourceEdited] = useState(false);
  const [preview, setPreview] = useState(false);
  const editor = useEditor({
    extensions: [StarterKit.configure({ link: { openOnClick: false, autolink: false, linkOnPaste: false }, codeBlock: false, code: false, horizontalRule: false, heading: { levels: [1, 2, 3] } }), TextStyle, Color],
    content: { type: 'doc', content: initialText.split('\n').map((line) => ({ type: 'paragraph', ...(line ? { content: [{ type: 'text', text: line }] } : {}) })) },
    editorProps: {
      attributes: { role: 'textbox', 'aria-multiline': 'true', 'aria-label': t('Текст письма'), class: 'compose-editor min-h-[180px] h-full px-4 py-4 text-[15px] leading-relaxed outline-none' },
      transformPastedHTML: (html) => cleanComposeHtml(html),
    },
    onUpdate: ({ editor: current }) => { const html = cleanComposeHtml(current.getHTML()); onChange(composePlainText(html), html); },
  });
  useImperativeHandle(ref, () => ({ insertText: (text) => { if (source === null) editor?.chain().focus().insertContent(text).run(); } }), [editor, source]);
  if (!editor) return null;
  const toggleSource = () => {
    if (source === null) { setSource(editor.getHTML()); setSourceEdited(false); setPreview(false); }
    else {
      if (sourceEdited && !window.confirm(t('При возврате в визуальный редактор неподдерживаемая разметка будет удалена. Продолжить?'))) return;
      editor.commands.setContent(cleanComposeHtml(source));
      const html = cleanComposeHtml(editor.getHTML());
      onChange(composePlainText(html), html);
      setSource(null); setPreview(false);
    }
  };
  const updateSource = (value: string) => {
    setSource(value); setSourceEdited(true);
    const html = cleanComposeHtml(value, true);
    onChange(composePlainText(html), html);
  };
  const link = () => {
    const value = window.prompt(t('Адрес ссылки (https://, http:// или mailto:)'), editor.getAttributes('link').href || 'https://');
    if (value === null) return;
    if (!value.trim()) { editor.chain().focus().extendMarkRange('link').unsetLink().run(); return; }
    if (!/^(https?:\/\/|mailto:)[^\s]+$/i.test(value.trim())) { window.alert(t('Укажите безопасный адрес ссылки.')); return; }
    editor.chain().focus().extendMarkRange('link').setLink({ href: value.trim() }).run();
  };
  const tools = [
    { label: t('Жирный'), icon: Bold, active: editor.isActive('bold'), run: () => editor.chain().focus().toggleBold().run() },
    { label: t('Курсив'), icon: Italic, active: editor.isActive('italic'), run: () => editor.chain().focus().toggleItalic().run() },
    { label: t('Подчёркнутый'), icon: Underline, active: editor.isActive('underline'), run: () => editor.chain().focus().toggleUnderline().run() },
    { label: t('Зачёркнутый'), icon: Strikethrough, active: editor.isActive('strike'), run: () => editor.chain().focus().toggleStrike().run() },
    { label: t('Маркированный список'), icon: List, active: editor.isActive('bulletList'), run: () => editor.chain().focus().toggleBulletList().run() },
    { label: t('Нумерованный список'), icon: ListOrdered, active: editor.isActive('orderedList'), run: () => editor.chain().focus().toggleOrderedList().run() },
    { label: t('Вставить или изменить ссылку'), icon: LinkIcon, active: editor.isActive('link'), run: link },
  ];
  return <>
    <div className="flex shrink-0 flex-wrap items-center gap-1 border-b border-line px-3 py-1" aria-label={t('Форматирование письма')}>
      {tools.map(({ label, icon: Icon, active, run }) => <button key={label} type="button" className={`icon-btn-sm ${active ? 'bg-accent-soft text-accent' : ''}`} title={label} aria-label={label} aria-pressed={active} disabled={source !== null} onMouseDown={(e) => e.preventDefault()} onClick={run}><Icon size={16} /></button>)}
      <label className="flex items-center gap-1 px-2 text-xs text-muted">{t('Цвет')}<input type="color" aria-label={t('Цвет текста')} value={/^#[0-9a-f]{6}$/i.test(editor.getAttributes('textStyle').color || '') ? editor.getAttributes('textStyle').color : '#222222'} disabled={source !== null} onChange={(e) => editor.chain().focus().setColor(e.target.value).run()} className="h-7 w-8 cursor-pointer border-0 bg-transparent" /></label>
      <button type="button" className={`icon-btn-sm ${source !== null ? 'bg-accent-soft text-accent' : ''}`} title={t('HTML-код')} aria-label={t('HTML-код')} aria-pressed={source !== null} onClick={toggleSource}><Code2 size={16} /></button>
    </div>
    {source !== null && <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-2 border-b border-line px-4 py-2">
      <button type="button" className="btn-secondary min-h-9 h-auto max-w-full shrink-0 whitespace-nowrap px-3 py-1.5 text-xs" aria-pressed={preview} onClick={() => setPreview((value) => !value)}>{t(preview ? 'Вернуться к HTML-коду' : 'Предпросмотр HTML')}</button>
      <span className="min-w-0 flex-1 basis-48 text-xs leading-5 text-muted">{t('Предпросмотр изолирован; внешние изображения и ссылки отключены.')}</span>
    </div>}
    <div className="min-h-0 flex-1 overflow-y-auto">
      {source === null ? <EditorContent editor={editor} className="h-full" /> : preview ? <div className="px-4"><MailHtmlFrame html={cleanComposeHtml(source, true)} /></div> : <textarea aria-label={t('HTML-код письма')} className="min-h-[180px] h-full w-full resize-none border-0 bg-transparent px-4 py-4 font-mono text-sm outline-none" value={source} onChange={(e) => updateSource(e.target.value)} />}
    </div>
    {source !== null && <p className="shrink-0 px-4 py-1 text-xs text-muted">{t('Скрипты, изображения и неподдерживаемые стили удаляются при отправке; некоторые почтовые клиенты не поддерживают кнопки.')}</p>}
  </>;
});
export default ComposeEditor;
