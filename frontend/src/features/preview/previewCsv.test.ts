import { describe, expect, it } from 'vitest';
import { parseDelimited } from './previewCsv';

describe('parseDelimited', () => {
  it('splits simple rows', () => {
    expect(parseDelimited('a,b\n1,2\n', ',')).toEqual({
      rows: [['a', 'b'], ['1', '2']],
      truncated: false,
    });
  });

  it('supports quoted cells with delimiters, quotes and newlines', () => {
    const text = 'name,note\n"Иванов, И.","сказал ""привет""\nи ушёл"\n';
    expect(parseDelimited(text, ',')).toEqual({
      rows: [
        ['name', 'note'],
        ['Иванов, И.', 'сказал "привет"\nи ушёл'],
      ],
      truncated: false,
    });
  });

  it('handles CRLF and TSV', () => {
    expect(parseDelimited('a\tb\r\n1\t2\r\n', '\t').rows).toEqual([['a', 'b'], ['1', '2']]);
  });

  it('limits rows and reports truncation', () => {
    const result = parseDelimited('a\nb\nc\nd\n', ',', 3);
    expect(result.rows).toEqual([['a'], ['b'], ['c']]);
    expect(result.truncated).toBe(true);
  });

  it('does not report truncation exactly at the limit', () => {
    const result = parseDelimited('a\nb\nc\n', ',', 3);
    expect(result.rows).toEqual([['a'], ['b'], ['c']]);
    expect(result.truncated).toBe(false);
  });

  it('returns an empty table for empty input', () => {
    expect(parseDelimited('', ',')).toEqual({ rows: [], truncated: false });
  });
});
