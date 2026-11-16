import { apiFetch } from './httpClient';

/** Состояние предложения «Перенести почту» (gateway: /api/v1/onboarding). */
export interface OnboardingAccountRef {
  id: string;
  emailAddress: string;
  provider: string;
}

export interface OnboardingView {
  migrationState: 'PENDING' | 'DISMISSED' | 'NOT_NEEDED' | 'DONE';
  migrationSuggested: boolean;
  activeJobId: string | null;
  lastJobStatus: string | null;
  dismissedAt: string | null;
  accounts: OnboardingAccountRef[];
}

const BASE = '/api/v1/onboarding';

export const onboardingApi = {
  state: () => apiFetch<OnboardingView>(BASE),
  dismiss: () => apiFetch<OnboardingView>(`${BASE}/migration/dismiss`, { method: 'POST' }),
  notNeeded: () => apiFetch<OnboardingView>(`${BASE}/migration/not-needed`, { method: 'POST' }),
  reset: () => apiFetch<OnboardingView>(`${BASE}/migration/reset`, { method: 'POST' }),
};
