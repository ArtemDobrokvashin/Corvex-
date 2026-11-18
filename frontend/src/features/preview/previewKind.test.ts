import { describe, expect, it } from 'vitest';
import {
  PREVIEW_LIMITS,
  isPreviewTooLarge,
  mimeFromFileName,
  needsBlob,
  opensPreviewDialog,
  previewKindFor,
  previewSizeLimit,
} from './previewKind';

describe('previewKindFor', () => {
  it('recognizes PDF by MIME and by extension', () => {
    expect(previewKindFor('application/pdf', 'scan.bin')).toBe('pdf');
    expect(previewKindFor('application/octet-stream', 'Договор.PDF')).toBe('pdf');
  });

  it('keeps images inside an img renderer, including SVG', () => {
    expect(previewKindFor('image/png', 'photo.png')).toBe('image');
    expect(previewKindFor('image/svg+xml', 'logo.svg')).toBe('image');
    expect(previewKindFor(null, 'picture.avif')).toBe('image');
  });

  it('recognizes text, tables, audio and video', () => {
    expect(previewKindFor('text/plain', 'notes.txt')).toBe('text');
    expect(previewKindFor(null, 'script.ts')).toBe('text');
    expect(previewKindFor('text/csv', 'report.csv')).toBe('table');
    expect(previewKindFor(null, 'table.tsv')).toBe('table');
    expect(previewKindFor('audio/mpeg', 'voice.bin')).toBe('audio');
    expect(previewKindFor(null, 'clip.mp4')).toBe('video');
  });

  it('routes Office documents to the converter flow', () => {
    expect(previewKindFor('application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'a.docx')).toBe('office');
    expect(previewKindFor('application/octet-stream', 'sheet.xlsx')).toBe('office');
    expect(previewKindFor(null, 'slides.pptx')).toBe('office');
    expect(previewKindFor(null, 'old.rtf')).toBe('office');
  });

  it('never treats HTML as viewable content, even with a harmless MIME', () => {
    expect(previewKindFor('text/html', 'mail.html')).toBe('html');
    expect(previewKindFor('text/plain', 'invoice.htm')).toBe('html');
    expect(previewKindFor('application/xhtml+xml', 'page.bin')).toBe('html');
  });

  it('keeps archives and executables download-only', () => {
    expect(previewKindFor('application/zip', 'archive.zip')).toBe('unsupported');
    expect(previewKindFor(null, 'setup.exe')).toBe('unsupported');
    expect(previewKindFor(null, 'backup.tar.gz')).toBe('unsupported');
    expect(previewKindFor(null, 'README')).toBe('unsupported');
    expect(previewKindFor('', '')).toBe('unsupported');
  });

  it('ignores case and MIME parameters', () => {
    expect(previewKindFor('TEXT/PLAIN; charset=UTF-8', 'NOTES.TXT')).toBe('text');
    expect(previewKindFor(' APPLICATION/PDF ', 'file')).toBe('pdf');
  });
});

describe('preview limits', () => {
  it('uses a limit per renderer', () => {
    expect(previewSizeLimit('text')).toBe(PREVIEW_LIMITS.text);
    expect(previewSizeLimit('table')).toBe(PREVIEW_LIMITS.text);
    expect(previewSizeLimit('image')).toBe(PREVIEW_LIMITS.image);
    expect(previewSizeLimit('pdf')).toBe(PREVIEW_LIMITS.pdf);
    expect(previewSizeLimit('video')).toBe(PREVIEW_LIMITS.media);
    expect(previewSizeLimit('office')).toBe(PREVIEW_LIMITS.office);
  });

  it('accepts unknown sizes and rejects files above the limit', () => {
    expect(isPreviewTooLarge('pdf', null)).toBe(false);
    expect(isPreviewTooLarge('pdf', Number.NaN)).toBe(false);
    expect(isPreviewTooLarge('pdf', PREVIEW_LIMITS.pdf)).toBe(false);
    expect(isPreviewTooLarge('pdf', PREVIEW_LIMITS.pdf + 1)).toBe(true);
    expect(isPreviewTooLarge('video', PREVIEW_LIMITS.media + 1)).toBe(true);
    expect(isPreviewTooLarge('office', PREVIEW_LIMITS.office)).toBe(false);
    expect(isPreviewTooLarge('office', PREVIEW_LIMITS.office + 1)).toBe(true);
    expect(isPreviewTooLarge('unsupported', Number.MAX_SAFE_INTEGER)).toBe(false);
  });
});

describe('preview capabilities', () => {
  it('opens a dialog for every kind except unsupported', () => {
    expect(opensPreviewDialog('image')).toBe(true);
    expect(opensPreviewDialog('office')).toBe(true);
    expect(opensPreviewDialog('html')).toBe(true);
    expect(opensPreviewDialog('unsupported')).toBe(false);
  });

  it('only fetches blobs for rendered formats', () => {
    expect(needsBlob('pdf')).toBe(true);
    expect(needsBlob('text')).toBe(true);
    expect(needsBlob('audio')).toBe(true);
    expect(needsBlob('office')).toBe(false);
    expect(needsBlob('html')).toBe(false);
    expect(needsBlob('unsupported')).toBe(false);
  });

  it('derives MIME from names when chat has no content type', () => {
    expect(mimeFromFileName('photo.JPG')).toBe('image/jpeg');
    expect(mimeFromFileName('scan.pdf')).toBe('application/pdf');
    expect(mimeFromFileName('voice.m4a')).toBe('audio/mp4');
    expect(mimeFromFileName('unknown.bin')).toBeNull();
  });
});
