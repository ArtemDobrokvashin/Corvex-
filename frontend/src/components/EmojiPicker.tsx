import { useEffect, useRef, useState } from 'react';
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
  { emoji: '🙂', ru: 'лёгкая улыбка', en: 'slightly smiling face' },
  { emoji: '😉', ru: 'подмигивание', en: 'winking face' },
  { emoji: '🤔', ru: 'задумчивость', en: 'thinking face' },
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
  { emoji: '👩‍💻', ru: 'женщина-технолог', en: 'woman technologist' },
  { emoji: '👨‍💻', ru: 'мужчина-технолог', en: 'man technologist' },
  { emoji: '🇷🇺', ru: 'флаг России', en: 'flag of Russia' },
  { emoji: '🇬🇧', ru: 'флаг Великобритании', en: 'flag of the United Kingdom' },
];

/**
 * Small built-in picker. Emoji are native Unicode text, not images: they copy,
 * search and travel through JSON exactly like any other characters, while the
 * operating system supplies the familiar color glyphs.
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

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

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
