import { getCurrentLocale, localeTag } from '../i18n';

/**
 * Единый HTTP-клиент к web-gateway. Access-токен живёт только в памяти,
 * refresh — HttpOnly cookie, поэтому каждый запрос идёт с credentials: 'include'.
 * На 401 выполняется один прозрачный refresh и повтор запроса (кроме самих
 * auth-эндпоинтов входа: там 401 — содержательный ответ, а не истёкший access).
 *
 * Refresh намеренно single-flight: refresh-токен ротируется сервером, поэтому
 * несколько параллельных refresh с одной старой cookie выглядели бы как
 * повторное использование отозванного токена и могли погасить все сессии.
 */
let accessToken: string | null = null;
let refreshInFlight: Promise<boolean> | null = null;
let sessionExpiredHandler: (() => void) | null = null;

export const setAccessToken = (t: string | null) => { accessToken = t; };
export const getAccessToken = () => accessToken;

/**
 * Оболочка приложения подписывается здесь, чтобы показать экран входа, когда
 * refresh больше не работает: молчаливый 401 на каждом действии выглядит как
 * «кнопка не нажимается», а пользователь так и не узнаёт, что сессия кончилась.
 */
export const setSessionExpiredHandler = (handler: (() => void) | null) => {
  sessionExpiredHandler = handler;
};

/** Ошибка API с HTTP-статусом и detail из Problem Details (RFC 7807). */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly detail: string,
    public readonly body: unknown = null,
  ) {
    super(detail || `HTTP ${status}`);
    this.name = 'ApiError';
  }
}

const NO_AUTO_REFRESH = ['/auth/login', '/auth/refresh', '/auth/mfa/', '/auth/logout'];

/**
 * Запросы, которые аутентифицируются cookie или телом, но НЕ access-токеном.
 * Просроченный Bearer в заголовке Gateway отклоняет ещё до маршрутизации
 * (401 с пустым телом), поэтому refresh с ним не мог сработать в принципе:
 * пока в памяти лежал истёкший токен, обновить его было нельзя. Ровно этот
 * тупик ломал календарь через 15 минут после входа.
 */
const NO_BEARER = ['/auth/login', '/auth/refresh', '/auth/mfa/', '/auth/logout'];

const requiresBearer = (path: string) => !NO_BEARER.some((p) => path.includes(p));

async function raw(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (init.body != null && !(typeof FormData !== 'undefined' && init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (accessToken && requiresBearer(path) && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  if (!headers.has('Accept-Language')) {
    headers.set('Accept-Language', localeTag(getCurrentLocale()));
  }

  return fetch(path, {
    ...init,
    credentials: 'include',
    headers,
  });
}

async function toApiError(res: Response, path: string): Promise<ApiError> {
  let detail = '';
  let body: unknown = null;
  try {
    body = await res.json();
    const b = body as { detail?: string; message?: string };
    detail = b.detail || b.message || '';
  } catch {
    // тело не JSON — detail останется пустым, статус несёт смысл
  }
  return new ApiError(res.status, detail || `HTTP ${res.status} ${path}`, body);
}

async function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = (async () => {
    try {
      const refresh = await raw('/api/v1/auth/refresh', { method: 'POST' });
      if (!refresh.ok) return false;
      const tokens = (await refresh.json()) as { accessToken: string };
      setAccessToken(tokens.accessToken);
      return true;
    } catch {
      return false;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res = await raw(path, init);
  if (res.status === 401 && !NO_AUTO_REFRESH.some((p) => path.includes(p))) {
    if (await refreshAccessToken()) {
      res = await raw(path, init);
    } else {
      // Сессию не восстановить: приложение обязано выйти на экран входа,
      // а не показывать «сохранено» после отказа.
      sessionExpiredHandler?.();
    }
  }
  if (!res.ok) throw await toApiError(res, path);
  if (res.status === 204) return undefined as T;

  const text = await res.text();
  if (!text.trim()) return undefined as T;
  const contentType = res.headers.get('content-type') ?? '';
  return (contentType.includes('json') ? JSON.parse(text) : text) as T;
}

/**
 * Бинарный GET/POST с той же single-flight refresh-логикой, что apiFetch.
 * Нужен файловым вложениям чата: прямая ссылка без Bearer не подходит, потому что
 * chat-file-service проверяет JWT и членство в комнате на каждую выдачу файла.
 */
export async function apiBlob(path: string, init: RequestInit = {}): Promise<Blob> {
  let res = await raw(path, init);
  if (res.status === 401 && !NO_AUTO_REFRESH.some((p) => path.includes(p))) {
    if (await refreshAccessToken()) {
      res = await raw(path, init);
    } else {
      sessionExpiredHandler?.();
    }
  }
  if (!res.ok) throw await toApiError(res, path);
  return res.blob();
}
