import type { SystemHealth } from '../../api/adminApi';

export interface HealthSummary {
  total: number;
  up: number;
  issues: number;
}

export function summarizeHealth(services: SystemHealth['services']): HealthSummary {
  const up = services.filter((service) => service.status === 'UP').length;
  return { total: services.length, up, issues: services.length - up };
}

/** Процент для доступной диаграммы; пустая выборка не даёт NaN или бесконечность. */
export function sharePercent(value: number, total: number): number {
  if (!Number.isFinite(value) || !Number.isFinite(total) || total <= 0 || value <= 0) return 0;
  return Math.max(0, Math.min(100, Math.round((value / total) * 100)));
}

export function displayMetric(value: number | null): number | null {
  return value === null || !Number.isFinite(value) || value < 0 ? null : value;
}
