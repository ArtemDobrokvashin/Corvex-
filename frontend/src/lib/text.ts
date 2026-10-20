/**
 * Unicode-safe text helpers.
 *
 * JavaScript strings are UTF-16 sequences, so naive [0], slice() and length can
 * split surrogate pairs and ZWJ emoji (for example 👩‍💻 or 🇷🇺). UI code that
 * creates initials, previews and inserts emoji must work with grapheme
 * clusters instead of UTF-16 code units.
 */

interface GraphemeSegmenter {
  segment(input: string): Iterable<{ segment: string }>;
}

type SegmenterConstructor = new (
  locales?: string | string[],
  options?: { granularity: 'grapheme' },
) => GraphemeSegmenter;

const Segmenter = (Intl as unknown as { Segmenter?: SegmenterConstructor }).Segmenter;

export function graphemes(value: string): string[] {
  if (!value) return [];
  if (Segmenter) {
    try {
      return Array.from(new Segmenter(undefined, { granularity: 'grapheme' }).segment(value), (part) => part.segment);
    } catch {
      // Fall through to code-point iteration on runtimes with incomplete ICU data.
    }
  }
  return Array.from(value);
}

export function firstGraphemes(value: string, count: number): string {
  if (count <= 0) return '';
  return graphemes(value).slice(0, count).join('');
}

export function truncateGraphemes(value: string, maxGraphemes: number): string {
  if (maxGraphemes <= 0) return '';
  const parts = graphemes(value);
  return parts.length <= maxGraphemes ? value : parts.slice(0, maxGraphemes).join('');
}

export function insertTextAtSelection(
  value: string,
  insertion: string,
  selectionStart: number | null,
  selectionEnd: number | null,
): { value: string; cursor: number } {
  const start = selectionStart ?? value.length;
  const end = selectionEnd ?? start;
  const safeStart = Math.max(0, Math.min(start, value.length));
  const safeEnd = Math.max(safeStart, Math.min(end, value.length));
  return {
    value: `${value.slice(0, safeStart)}${insertion}${value.slice(safeEnd)}`,
    cursor: safeStart + insertion.length,
  };
}

/** Removes the small Markdown subset used in chat list previews without touching Unicode symbols. */
export function stripMarkdownPreview(value: string): string {
  return value.replace(/\*\*/g, '').replace(/[#*_`>]/g, '').trim();
}

export function containsEmoji(value: string): boolean {
  try {
    return /\p{Extended_Pictographic}/u.test(value);
  } catch {
    return /[\u{1F300}-\u{1FAFF}\u2600-\u27BF]/u.test(value);
  }
}

/** A compact torture string for automated tests and manual QA. */
export const EMOJI_TEST_STRING = '👋 🚀 ✅ 👩‍💻 🇷🇺 👍🏽 1️⃣ ❤️ ☕️ 📎 🎉 😀';
