/**
 * Условие для `Ctrl/Cmd+P` в почте.
 *
 * Печатать интерфейс письма бессмысленно, поэтому сочетание перехватывается только
 * когда есть что печатать и пользователь не вводит текст. В остальных случаях
 * событие отдаётся браузеру: печать страницы — его законное дело.
 */
export interface PrintShortcutEvent {
  key: string;
  /** Физическая клавиша: на русской раскладке `Ctrl+P` даёт `key === 'з'`. */
  code?: string;
  ctrlKey: boolean;
  metaKey: boolean;
  altKey: boolean;
  shiftKey: boolean;
}

export interface PrintShortcutContext {
  /** Есть открытое письмо. */
  hasActiveMail: boolean;
  /** Открыт редактор черновика. */
  composing: boolean;
  /** Фокус в поле ввода: там Ctrl+P не должен навязывать печать письма. */
  typing: boolean;
}

export function isMailPrintShortcut(event: PrintShortcutEvent, context: PrintShortcutContext): boolean {
  if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return false;
  const isP = event.key.toLowerCase() === 'p' || event.code === 'KeyP';
  if (!isP) return false;
  return context.hasActiveMail && !context.composing && !context.typing;
}
