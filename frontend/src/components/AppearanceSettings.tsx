import { useCallback } from 'react';
import { Sun, Moon, Monitor, Layout, RotateCw, Maximize2 } from 'lucide-react';
import {
  useUIStore,
  accentDefinitions,
  accentValue,
  accentLabel,
  type Theme,
  type Accent,
} from '../store/uiStore';
import { useI18n } from '../i18n';

/**
 * Панель кастомизации интерфейса Corvex.
 * Позволяет выбрать тему (светлую / тёмную / системную), акцентный цвет и плотность.
 * Не меняет backend, не отправляет данные на сервер — только локальные настройки UI.
 */
export function AppearanceSettings({ onClose }: { onClose?: () => void }) {
  const { t } = useI18n();
  // Отдельные селекторы на примитивы — избегаем создания нового объекта
  // при каждом рендере ( Zustand v5 иначе будет вызывать бесконечные ре-рендеры).
  const theme = useUIStore((s) => s.theme);
  const accent = useUIStore((s) => s.accent);
  const density = useUIStore((s) => s.density);
  const setTheme = useUIStore((s) => s.setTheme);
  const setAccent = useUIStore((s) => s.setAccent);
  const setDensity = useUIStore((s) => s.setDensity);

  const accents = accentDefinitions();

  const themeLabel = useCallback(
    (value: Theme) => {
      if (value === 'light') return t('Светлая');
      if (value === 'dark') return t('Тёмная');
      return t('По системе');
    },
    [t],
  );

  const isAccentActive = (value: Accent) => accent === value;
  const isThemeActive = (value: Theme) => theme === value;
  const isDensityActive = (value: typeof density) => density === value;

  return (
    <section className="panel p-5 sm:p-6 space-y-6" aria-labelledby="appearance-title">
      <div>
        <h2 id="appearance-title" className="text-[17px] font-semibold tracking-[-0.02em]">
          {t('Внешний вид')}
        </h2>
        <p className="mt-1 text-sm text-muted">
          {t('Настройки применяются только в этом браузере и не отправляются на сервер.')}
        </p>
      </div>

      <div className="space-y-5">
        {/* Тема */}
        <div className="space-y-3">
          <label className="field-label">{t('Тема оформления')}</label>
          <div className="grid gap-2 sm:grid-cols-3">
            {(['light', 'dark', 'system'] as const).map((value) => {
              const Icon = value === 'light' ? Sun : value === 'dark' ? Moon : Monitor;
              const active = isThemeActive(value);
              return (
                <button
                  key={value}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  onClick={() => setTheme(value)}
                  className={`flex flex-col items-center justify-center gap-2 rounded-xl border px-3 py-3.5 text-sm transition ${
                    active
                      ? 'border-accent bg-accent-soft text-accent-dark'
                      : 'border-line bg-surface text-muted hover:bg-black/[0.04] hover:text-ink'
                  }`}
                >
                  <Icon size={18} strokeWidth={1.7} />
                  <span>{themeLabel(value)}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Акцентный цвет */}
        <div className="space-y-3">
          <label className="field-label">{t('Акцентный цвет')}</label>
          <div className="flex flex-wrap gap-2.5">
            {Object.entries(accents).map(([key, def]) => {
              const accentKey = key as Accent;
              const active = isAccentActive(accentKey);
              return (
                <button
                  key={key}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  aria-label={t(accentLabel(accentKey))}
                  onClick={() => setAccent(accentKey)}
                  className={`relative shrink-0 h-9 w-9 rounded-xl border-2 transition ${
                    active
                      ? 'border-accent ring-1 ring-offset-1 ring-offset-surface ring-accent-dark'
                      : 'border-line hover:border-accent/50'
                  }`}
                  style={{ borderColor: def.value }}
                >
                  <span
                    className={`absolute inset-0 rounded-[8px] shadow-sm ${
                      active ? 'ring-2 ring-white ring-offset-2 ring-offset-surface' : ''
                    }`}
                    style={{ backgroundColor: def.value }}
                  />
                </button>
              );
            })}
          </div>
        </div>

        {/* Плотность */}
        <div className="space-y-3">
          <label className="field-label">{t('Плотность элементов')}</label>
          <div className="grid gap-2 sm:grid-cols-2">
            {(['compact', 'comfortable'] as const).map((value) => {
              const Icon = value === 'compact' ? Layout : Maximize2;
              const active = isDensityActive(value);
              return (
                <button
                  key={value}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  onClick={() => setDensity(value)}
                  className={`flex flex-col items-center justify-center gap-2 rounded-xl border px-3 py-3.5 text-sm transition ${
                    active
                      ? 'border-accent bg-accent-soft text-accent-dark'
                      : 'border-line bg-surface text-muted hover:bg-black/[0.04] hover:text-ink'
                  }`}
                >
                  <Icon size={18} strokeWidth={1.7} />
                  <span>{value === 'compact' ? t('Компактная') : t('Свободная')}</span>
                </button>
              );
            })}
          </div>
          <p className="text-[11px] text-faint">
            {t('Компактная плотность подходит для больших списков писем и сообщений.')}
          </p>
        </div>
      </div>

      <div className="pt-4 border-t border-line flex items-center justify-between text-xs text-faint">
        <span>{t('Хранится локально в браузере.')}</span>
        <button
          type="button"
          className="icon-btn-sm"
          onClick={() => {
            setTheme('light');
            setAccent('indigo');
            setDensity('compact');
          }}
          aria-label={t('Сбросить настройки внешнего вида')}
          title={t('Сбросить к умолчанию')}
        >
          <RotateCw size={14} strokeWidth={1.6} />
        </button>
      </div>
    </section>
  );
}
