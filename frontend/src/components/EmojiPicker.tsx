import { useEffect, useRef, useState, type RefObject } from 'react';
import { Smile } from 'lucide-react';
import { useI18n } from '../i18n';

export interface EmojiOption {
  emoji: string;
  ru: string;
  en: string;
}

export const EMOJI_OPTIONS: EmojiOption[] = [
  { emoji: '😀', ru: 'улыбка', en: 'grinning face' },
  { emoji: '😂', ru: 'смех до слёз', en: 'face with tears of joy' },
  { emoji: '☺️', ru: 'лёгкая улыбка', en: 'smiling face' },
  { emoji: '😉', ru: 'подмигивание', en: 'winking face' },
  { emoji: '💭', ru: 'задумчивость', en: 'thought balloon' },
  { emoji: '👍', ru: 'большой палец вверх', en: 'thumbs up' },
  { emoji: '👏', ru: 'аплодисменты', en: 'clapping hands' },
  { emoji: '🙏', ru: 'спасибо', en: 'folded hands' },
  { emoji: '👋', ru: 'приветствие', en: 'waving hand' },
  { emoji: '✅', ru: 'готово', en: 'check mark button' },
  { emoji: '🎉', ru: 'праздник', en: 'party popper' },
  { emoji: '🚀', ru: 'ракета', en: 'rocket' },
  { emoji: '📎', ru: 'скрепка', en: 'paperclip' },
  { emoji: '📅', ru: 'календарь', en: 'calendar' },
  { emoji: '⏰', ru: 'будильник', en: 'alarm clock' },
  { emoji: '💡', ru: 'идея', en: 'light bulb' },
  { emoji: '☕️', ru: 'кофе', en: 'hot beverage' },
  { emoji: '❤️', ru: 'красное сердце', en: 'red heart' },
  { emoji: '🔥', ru: 'огонь', en: 'fire' },
  { emoji: '⭐', ru: 'звезда', en: 'star' },
  { emoji: '💻', ru: 'ноутбук', en: 'laptop' },
  { emoji: '📌', ru: 'закрепить', en: 'pushpin' },
  { emoji: '📞', ru: 'телефон', en: 'telephone receiver' },
  { emoji: '✉️', ru: 'конверт', en: 'envelope' },
];

/** Короткий набор быстрых реакций для сообщений чата. */
export const REACTION_EMOJI_OPTIONS: EmojiOption[] = [
  '👍', '❤️', '😂', '🎉', '👏', '🙏', '✅', '🔥',
].flatMap((emoji) => {
  const option = EMOJI_OPTIONS.find((candidate) => candidate.emoji === emoji);
  return option ? [option] : [];
});

function useDismissOnOutsideOrEscape(
  open: boolean,
  rootRef: RefObject<HTMLDivElement | null>,
  close: () => void,
) {
  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) close();
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') close();
    };
    window.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [close, open, rootRef]);
}

/**
 * Small built-in picker. Emoji are native Unicode text, not images: they copy,
 * search and travel through JSON exactly like any other characters, while the
 * operating system supplies the familiar color glyphs.
 *
 * The catalogue deliberately uses older, mostly single-code-point emoji and
 * avoids flags, ZWJ professions and skin-tone sequences: stock Windows fonts
 * do not render country flags, and the mandatory Windows 7 fallback is much
 * smaller than modern emoji fonts. Complex emoji pasted by users are still
 * preserved safely by the grapheme utilities; only their glyph depends on OS.
 */
export function EmojiPickerButton({
  onPick,
  className = '',
  align = 'left',
}: {
  onPick: (emoji: string) => void;
  className?: string;
  align?: 'left' | 'right';
}) {
  const { locale, t } = useI18n();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useDismissOnOutsideOrEscape(open, rootRef, () => setOpen(false));

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className={`icon-btn ${className}`}
        title={t('Эмодзи')}
        aria-label={t('Эмодзи')}
        aria-expanded={open}
        aria-haspopup="dialog"
      >
        <Smile size={16} strokeWidth={1.8} />
      </button>
      {open && (
        <div
          role="dialog"
          aria-label={t('Эмодзи')}
          className={`absolute bottom-11 z-50 grid w-[272px] grid-cols-6 gap-1 rounded-2xl border border-line bg-surface p-2 shadow-pop ${align === 'right' ? 'right-0' : 'left-0'}`}
        >
          {EMOJI_OPTIONS.map((option) => (
            <button
              key={option.emoji}
              type="button"
              className="emoji-text flex h-10 items-center justify-center rounded-xl text-[22px] leading-none transition hover:bg-accent-soft"
              title={locale === 'en' ? option.en : option.ru}
              aria-label={locale === 'en' ? option.en : option.ru}
              onClick={() => {
                onPick(option.emoji);
                setOpen(false);
              }}
            >
              {option.emoji}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/** Компактный выбор реакции для панели действий над сообщением. */
export function ReactionPickerButton({
  onPick,
  disabled = false,
}: {
  onPick: (emoji: string) => void;
  disabled?: boolean;
}) {
  const { locale, t } = useI18n();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useDismissOnOutsideOrEscape(open, rootRef, () => setOpen(false));

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        disabled={disabled}
        className="icon-btn-sm disabled:opacity-40"
        title={t('Добавить реакцию')}
        aria-label={t('Добавить реакцию')}
        aria-expanded={open}
        aria-haspopup="dialog"
      >
        <Smile size={15} strokeWidth={1.8} />
      </button>
      {open && (
        <div
          role="dialog"
          aria-label={t('Выбрать реакцию')}
          className="absolute right-0 top-9 z-50 grid w-[184px] max-w-[calc(100vw-24px)] grid-cols-4 gap-1 rounded-2xl border border-line bg-surface p-2 shadow-pop"
        >
          {REACTION_EMOJI_OPTIONS.map((option) => (
            <button
              key={option.emoji}
              type="button"
              className="emoji-text flex h-10 items-center justify-center rounded-xl text-[21px] leading-none transition hover:bg-accent-soft"
              title={locale === 'en' ? option.en : option.ru}
              aria-label={`${t('Реакция')}: ${locale === 'en' ? option.en : option.ru}`}
              onClick={() => {
                onPick(option.emoji);
                setOpen(false);
              }}
            >
              {option.emoji}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
