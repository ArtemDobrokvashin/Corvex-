/**
 * Ссылка вида `?demo=1` открывает витрину без сервера.
 *
 * Зачем это нужно отдельно от кнопки «Посмотреть демо»: демо-режим хранится в
 * `sessionStorage`, а он не переживает запуск нового процесса браузера. Без
 * параметра нельзя воспроизводимо снять скриншоты в headless-браузере или
 * открыть конкретный экран витрины по ссылке.
 *
 * Параметр никогда не перекрывает настоящую сессию: демо включается только
 * после того, как восстановление сессии по refresh-cookie не удалось.
 */
export const DEMO_QUERY_PARAM = 'demo';

export function isDemoRequested(search: string): boolean {
  try {
    return new URLSearchParams(search).get(DEMO_QUERY_PARAM) === '1';
  } catch {
    return false;
  }
}
