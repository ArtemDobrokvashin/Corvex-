import DOMPurify from 'dompurify';
import { marked } from 'marked';

/**
 * Единственный разрешённый путь превращения пользовательского Markdown в HTML.
 *
 * Внешние медиа-теги запрещены политикой приватности: tracking pixel не должен
 * сообщать третьим лицам факт и время прочтения или IP сотрудника. Файловые
 * вложения рендерятся отдельными доверенными компонентами, а не Markdown-HTML.
 */
export function renderChatMarkdown(markdown: string): { __html: string } {
  return {
    __html: DOMPurify.sanitize(marked.parse(markdown) as string, {
      FORBID_TAGS: ['img', 'iframe', 'video', 'audio', 'source', 'style'],
      FORBID_ATTR: ['style'],
    }),
  };
}
