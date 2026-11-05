# Аудит видимости функционала админки

Дата проверки: 2026-11-04. Цель матрицы — не потерять рабочий backend или существующий экран между API, RBAC, router и навигацией.

## 1. Маршруты разделов

Все разделы имеют deep link и восстанавливаются после refresh. Неизвестный или запрещённый `section` заменяется на первый доступный раздел без загрузки его API.

| Route | Раздел | Доступ |
|---|---|---|
| `/admin/users` | Пользователи, создание, профиль, блокировка/отключение, группы | `ADMIN_USERS_MANAGE`; назначение групп также `ADMIN_GROUPS_MANAGE` |
| `/admin/quotas` | Фактический usage и изменение только квоты | `ADMIN_USERS_MANAGE` или `ADMIN_QUOTAS_MANAGE` |
| `/admin/groups` | Группы и чекбоксы прав | чтение `ADMIN_GROUPS_MANAGE`/`ADMIN_USERS_MANAGE`, изменение `ADMIN_GROUPS_MANAGE` |
| `/admin/external-mail` | Вход в управление внешними ящиками сотрудников | `ADMIN_MAIL_POLICY` |
| `/admin/server` | Health и регистрация backup-запроса | health: `ADMIN_SERVER_CONFIG`/`ADMIN_USERS_MANAGE`/`ADMIN_GROUPS_MANAGE`; backup: `ADMIN_BACKUP` |
| `/admin/settings` | Реестр `server_settings`; runtime-алерты | реестр: `ADMIN_SERVER_CONFIG`; алерты: дополнительно членство `SUPER_ADMIN` |
| `/admin/analytics` | Учётные записи и входы company/own-unit | `ANALYTICS_VIEW_COMPANY` или `ANALYTICS_VIEW_OWN_UNIT` |
| `/admin/audit` | Журнал, серверные фильтры и pagination | `AUDIT_VIEW` |

`/admin` перенаправляет на первый разрешённый раздел. Пункт основного меню и command palette используют тот же `canAccessAdmin`, что и навигация.

## 2. Покрытие admin-service API

| Backend contract | Видимый вход | Примечание |
|---|---|---|
| `GET /admin/users` | `/admin/users` | `q`, `status`, `department`, `page`, `size` доступны в UI |
| `POST /admin/users` | «Новый пользователь» | Логин, email, ФИО, пароль, подразделение, группы и начальная квота |
| `PATCH /admin/users/{id}` | «Редактировать профиль», разблокировка | ФИО/email/подразделение и status; общий PATCH не выдаётся quota-only роли |
| `PATCH /admin/users/{id}/quota` | `/admin/quotas` | Узкий контракт и аудит `USER_QUOTA_CHANGED`; `0` = unlimited |
| `POST /admin/users/{id}/block`, `DELETE /admin/users/{id}` | Действия в `/admin/users` | Подтверждение опасного отключения |
| `PUT /admin/users/{id}/groups` | Действия в `/admin/users` | Полная замена групп |
| `GET/POST /admin/groups`, `PUT /admin/groups/{code}/permissions` | `/admin/groups` | Системные и пользовательские группы, чекбоксы прав |
| `GET/PATCH /admin/settings` | `/admin/settings` | Сохранение только изменённых ключей; видимое предупреждение об отсутствии runtime-потребителей |
| `GET/PUT /admin/alerts/settings` | `/admin/settings` → «Алерты и доставка» | Только `SUPER_ADMIN`; категории, repeat, recipients/channels; секреты write-only |
| `GET /admin/alerts/runtime` | Блок «Фактическое применение» | Сравнение сохранённой revision с фактически загруженной notification-service/alert-relay |
| `GET /admin/audit` | `/admin/audit` | `actorId`, `action`, `from`, `to`, `page`, `size` |
| `GET /admin/analytics/activity` | `/admin/analytics` | `period` намеренно не показан: backend его игнорирует и возвращает фиксированные окна 24h/7d |
| `GET /admin/system/health` | `/admin/server` | Реальные probes и состояние недоступности |
| `POST /admin/backup/run` | `/admin/server` | Только `BACKUP_REQUESTED`; UI прямо сообщает, что физическая копия не создаётся |

Внутренние `/internal/**` контракты не являются страницами админки и не публикуются в навигации.

## 3. Функционал вне admin-service

- **External Mail:** рабочая форма остаётся в MailPage, без второй копии. `/admin/external-mail` ведёт на `/mail?externalAccounts=1`, где открывается существующее управление владельцами, подключениями, синхронизацией и аудитом. Backend повторно проверяет `ADMIN_MAIL_POLICY`; секреты не возвращаются.
- **Личная безопасность:** смена пароля, собственная 2FA и app passwords находятся в профиле пользователя, а не в административной политике.
- **Alerting:** `/admin/settings` содержит реальную runtime-панель для `SUPER_ADMIN`: admin-service версионирует config, notification-service и alert-relay применяют категории/repeat/recipients, а отдельный status подтверждает загруженную revision. Alertmanager продолжает управлять grouping/silences; `scripts/alerts.ps1` показывает effective policy. SMTP/Telegram/MAX secrets write-only и не появляются в public API. Подробности — `docs/ALERTING.md`.
- **`ADMIN_SECURITY_POLICY`:** атомарное право есть в каталоге RBAC, но самостоятельного backend-модуля административной password/2FA policy пока нет. Пустая админка для одного этого права не открывается.

## 4. Граница settings

Реестр содержит семь существующих ключей: mail message size/default quota/spam threshold/POP3, password minimum length/require TOTP и chat file size. На дату аудита они валидируются, сохраняются транзакционно и попадают в аудит, но работающие сервисы не читают их автоматически. Поэтому раздел называется **«Реестр серверных настроек»**, а не применённой runtime-политикой. Квота вводится в десятичных ГБ и преобразуется в bytes только на границе API. Описание SpamAssassin различает фактический `spamd score/required_score` и неприменённый registry threshold.

**Отдельный alert config в этот реестр не входит:** у него собственная версионированная таблица и реальные runtime-потребители. Это не смешивает доказанное применение алертов с честно неприменёнными семью registry keys.

## 5. Regression-защита

- `adminNavigation.test.tsx`: deep links, refresh-совместимые routes, RBAC fallback, settings и External Mail.
- `adminUsers.test.tsx`: department-фильтр, общий PATCH профиля и начальная квота.
- `auditTab.test.tsx`: фильтры, ISO date-time и pagination.
- `settingsTab.test.tsx`: GET, PATCH только изменений, десятичные ГБ и non-runtime предупреждение.
- `AlertSettingsPanel.test.tsx`: write-only secret, категории/repeat, PUT и подтверждённая revision.
- `ExternalMailAccountsModal.test.tsx`: явные результаты owner search, выбор владельца, пустая выдача и HTTP-ошибка без stale results.
- `adminAccess.test.ts`: единые правила видимости основного меню и разделов.
- `adminApi.test.ts`: query/path/method контракты.
