import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/httpClient';
import { previewFailure } from './previewFailure';

describe('previewFailure', () => {
  it('treats a rejected scan as an antivirus block, not a generic error', () => {
    expect(previewFailure(new ApiError(403, 'Antivirus scan pending'), true).kind).toBe('blocked');
    expect(previewFailure(new ApiError(403, 'Threat detected'), true).kind).toBe('blocked');
  });

  it('keeps permission errors separate when the scan was confirmed', () => {
    expect(previewFailure(new ApiError(403, 'Forbidden'), false).kind).toBe('forbidden');
  });

  it('treats a server-side size rejection as a conversion fallback', () => {
    expect(previewFailure(new ApiError(413, 'Файл больше лимита')).kind).toBe('conversion-unavailable');
  });

  it('treats a locked chat file as an antivirus block', () => {
    expect(previewFailure(new ApiError(423, 'Файл недоступен до проверки')).kind).toBe('blocked');
  });

  it('reports an unavailable scanner only for unconfirmed files', () => {
    expect(previewFailure(new ApiError(503, 'Scanner offline'), true).kind).toBe('scan-unavailable');
    expect(previewFailure(new ApiError(503, 'Scanner offline'), false).kind).toBe('network');
  });

  it('separates a missing converter or unsupported conversion from a network error', () => {
    expect(previewFailure(new ApiError(501, 'Converter disabled')).kind).toBe('conversion-unavailable');
    expect(previewFailure(new ApiError(415, 'Unsupported format')).kind).toBe('conversion-unavailable');
    expect(previewFailure(new ApiError(422, 'Conversion failed')).kind).toBe('conversion-unavailable');
  });

  it('maps missing files and network failures', () => {
    expect(previewFailure(new ApiError(404, 'Not found')).kind).toBe('not-found');
    expect(previewFailure(new ApiError(500, 'Boom')).kind).toBe('network');
    expect(previewFailure(new Error('Failed to fetch')).kind).toBe('network');
    expect(previewFailure(null).kind).toBe('network');
  });

  it('keeps the server detail for display', () => {
    expect(previewFailure(new ApiError(404, 'File deleted')).detail).toBe('File deleted');
  });
});
