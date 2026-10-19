import { ApiError, apiFetch, setAccessToken } from './httpClient';
import type { SessionUser } from '../store/sessionStore';

/** Клиент auth-service по контракту docs/api/auth-api.yaml. */

export type LoginResult =
  | { mfaRequired: false }
  | { mfaRequired: true; mfaTicket: string };

interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const body = await apiFetch<TokenResponse & { mfaRequired?: boolean; mfaTicket?: string }>(
    '/api/v1/auth/login',
    { method: 'POST', body: JSON.stringify({ username, password }) },
  );
  if (body.mfaRequired && body.mfaTicket) {
    return { mfaRequired: true, mfaTicket: body.mfaTicket };
  }
  setAccessToken(body.accessToken);
  return { mfaRequired: false };
}

export async function verifyMfa(mfaTicket: string, code: string): Promise<void> {
  const body = await apiFetch<TokenResponse>('/api/v1/auth/mfa/verify', {
    method: 'POST',
    body: JSON.stringify({ mfaTicket, code }),
  });
  setAccessToken(body.accessToken);
}

export async function fetchMe(): Promise<SessionUser> {
  return apiFetch<SessionUser>('/api/v1/auth/me');
}

/** Тихая попытка восстановить сессию по refresh-cookie при загрузке приложения. */
export async function restoreSession(): Promise<SessionUser | null> {
  try {
    const tokens = await apiFetch<TokenResponse>('/api/v1/auth/refresh', { method: 'POST' });
    setAccessToken(tokens.accessToken);
    return await fetchMe();
  } catch {
    setAccessToken(null);
    return null;
  }
}

export async function logout(): Promise<void> {
  try {
    await apiFetch<void>('/api/v1/auth/logout', { method: 'POST' });
  } finally {
    setAccessToken(null);
  }
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await apiFetch<void>('/api/v1/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

export interface TotpSetup {
  secret: string;
  otpauthUri: string;
}

export async function totpSetup(): Promise<TotpSetup> {
  return apiFetch<TotpSetup>('/api/v1/auth/mfa/totp/setup', { method: 'POST' });
}

export async function totpConfirm(code: string): Promise<void> {
  await apiFetch<void>('/api/v1/auth/mfa/totp/confirm', {
    method: 'POST',
    body: JSON.stringify({ code }),
  });
}

export async function totpDisable(code: string): Promise<void> {
  await apiFetch<void>('/api/v1/auth/mfa/totp/disable', {
    method: 'POST',
    body: JSON.stringify({ code }),
  });
}

export interface AppPassword {
  id: string;
  label: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export async function listAppPasswords(): Promise<AppPassword[]> {
  return apiFetch<AppPassword[]>('/api/v1/auth/app-passwords');
}

export async function createAppPassword(label: string): Promise<{ id: string; password: string }> {
  return apiFetch<{ id: string; password: string }>('/api/v1/auth/app-passwords', {
    method: 'POST',
    body: JSON.stringify({ label }),
  });
}

export async function deleteAppPassword(id: string): Promise<void> {
  await apiFetch<void>(`/api/v1/auth/app-passwords/${id}`, { method: 'DELETE' });
}

/** Человекочитаемый текст ошибки для форм входа/админки. */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401) return e.detail || 'Неверный логин или пароль';
    if (e.status === 423) return e.detail || 'Слишком много неверных попыток, попробуйте позже';
    if (e.status === 403) return e.detail || 'Доступ запрещён';
    if (e.status >= 500) return 'Сервер недоступен или произошла ошибка. Попробуйте позже';
    return e.detail || e.message;
  }
  return 'Сеть недоступна: сервер не отвечает';
}

/** Истинная недоступность сервера (не путать с неверным паролем!) — основание предложить демо. */
export function isServerUnreachable(e: unknown): boolean {
  if (e instanceof ApiError) return e.status === 502 || e.status === 503 || e.status === 504;
  return true; // TypeError из fetch и прочие сетевые сбои
}
