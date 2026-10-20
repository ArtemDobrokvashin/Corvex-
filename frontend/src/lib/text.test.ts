import { describe, expect, it } from 'vitest';
import {
  EMOJI_TEST_STRING,
  containsEmoji,
  firstGraphemes,
  graphemes,
  insertTextAtSelection,
  stripMarkdownPreview,
  truncateGraphemes,
} from './text';

describe('Unicode and emoji text helpers', () => {
  it('keeps ZWJ sequences, flags, skin tones and keycaps as single graphemes', () => {
    const parts = graphemes(EMOJI_TEST_STRING);

    expect(parts.join('')).toBe(EMOJI_TEST_STRING);
    expect(parts.filter((part) => part !== ' ')).toHaveLength(12);
    expect(parts).toContain('👩‍💻');
    expect(parts).toContain('🇷🇺');
    expect(parts).toContain('👍🏽');
    expect(parts).toContain('1️⃣');
  });

  it('does not split surrogate pairs when building initials or previews', () => {
    expect(firstGraphemes('👩‍💻Anna', 2)).toBe('👩‍💻A');
    expect(truncateGraphemes('a🇷🇺b👍🏽c', 4)).toBe('a🇷🇺b👍🏽');
  });

  it('inserts an emoji at the text cursor and returns the following UTF-16 offset', () => {
    const result = insertTextAtSelection('Привет ', '🚀', 7, 7);

    expect(result).toEqual({ value: 'Привет 🚀', cursor: 9 });
  });

  it('preserves emoji through Markdown preview stripping and JSON transport', () => {
    const value = '**Готово** ✅ 👩‍💻';

    expect(stripMarkdownPreview(value)).toBe('Готово ✅ 👩‍💻');
    expect(JSON.parse(JSON.stringify({ value })).value).toBe(value);
    expect(containsEmoji(value)).toBe(true);
    expect(containsEmoji('plain text')).toBe(false);
  });

  it('encodes emoji as valid UTF-8 for JSON and WebSocket payloads', () => {
    expect(new TextEncoder().encode('👍🏽')).toHaveLength(8);
    expect(new TextDecoder().decode(new TextEncoder().encode(EMOJI_TEST_STRING))).toBe(EMOJI_TEST_STRING);
  });
});
