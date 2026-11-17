/**
 * Текстовые вложения часто приходят в windows-1251 (старые почтовые клиенты и
 * выгрузки 1С). Поэтому UTF-8 декодируется строго, а при ошибке берётся
 * windows-1251, и только затем — мягкий UTF-8.
 */
export async function decodeTextBlob(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) {
    return new TextDecoder('utf-16le').decode(bytes.subarray(2));
  }
  if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) {
    return new TextDecoder('utf-16be').decode(bytes.subarray(2));
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    try {
      return new TextDecoder('windows-1251').decode(bytes);
    } catch {
      return new TextDecoder().decode(bytes);
    }
  }
}
