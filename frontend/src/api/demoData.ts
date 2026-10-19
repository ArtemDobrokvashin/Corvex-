/**
 * ДЕМО-ДАННЫЕ. Используются только когда gateway недоступен (режим витрины без поднятого бэкенда):
 * интерфейс помечает режим плашкой. В продакшене все экраны ходят в apiFetch по контрактам docs/api/*.
 */
export const DEMO_USER = {
  id: '00000000-0000-0000-0000-000000000001',
  displayName: 'Ivanov',
  email: 'Ivanov@company.local',
  groups: ['DIRECTOR'],
  permissions: [
    'MAIL_READ_OWN', 'MAIL_SEND_INTERNAL', 'MAIL_SEND_EXTERNAL', 'MAIL_MANAGE_FOLDERS',
    'MAIL_DELEGATE_ACCESS', 'MAIL_EXPORT', 'CHAT_READ', 'CHAT_WRITE', 'CHAT_CREATE_GROUP',
    'CHAT_SEND_FILES', 'CHAT_VOICE_VIDEO', 'ANALYTICS_VIEW_COMPANY',
    'ADMIN_USERS_MANAGE', 'ADMIN_GROUPS_MANAGE', 'ADMIN_QUOTAS_MANAGE', 'AUDIT_VIEW',
  ],
};

export interface DemoMail {
  id: string; from: string; subject: string; snippet: string; body: string;
  date: string; seen: boolean; flagged: boolean; hasAttachments: boolean; folder: string;
}

export const DEMO_FOLDERS = [
  { id: 'INBOX', name: 'Входящие', unread: 3 },
  { id: 'SENT', name: 'Отправленные', unread: 0 },
  { id: 'DRAFTS', name: 'Черновики', unread: 1 },
  { id: 'ARCHIVE', name: 'Архив', unread: 0 },
  { id: 'SPAM', name: 'Спам', unread: 5 },
  { id: 'TRASH', name: 'Корзина', unread: 0 },
  { id: 'proj', name: 'Проекты / Миграция на Corvex', unread: 1 },
];

export const DEMO_MAILS: DemoMail[] = [
  { id: 'm1', folder: 'INBOX', from: 'Петрова Анна <a.petrova@company.local>', subject: 'Сводка по платежам за октябрь', snippet: 'Коллеги, направляю реестр: 47 платежей, 3 требуют вашего подтверждения до пятницы…', body: 'Коллеги,\n\nнаправляю реестр платежей за октябрь: 47 позиций, три платежа свыше лимита требуют вашего подтверждения до пятницы.\n\nВо вложении — выгрузка из 1С.\n\n— Петрова Анна, бухгалтерия', date: '09:42', seen: false, flagged: true, hasAttachments: true },
  { id: 'm2', folder: 'INBOX', from: 'IT-отдел <it@company.local>', subject: 'Плановое обслуживание сервера в воскресенье 02:00–04:00', snippet: 'Будут недоступны почта и чат. Работы связаны с обновлением Corvex до 1.1…', body: 'В воскресенье с 02:00 до 04:00 проводятся плановые работы: обновление платформы Corvex.\nПочта (включая Outlook/Thunderbird) и корпоративный чат будут недоступны. Входящие письма не потеряются — они встанут в очередь у отправителей.', date: 'Вчера', seen: false, flagged: false, hasAttachments: false },
  { id: 'm3', folder: 'INBOX', from: 'Смирнов Олег <o.smirnov@partner.example>', subject: 'Re: Договор поставки №118 — подписанный скан', snippet: 'Добрый день! Высылаю подписанный с нашей стороны скан, оригинал уезжает курьером…', body: 'Добрый день!\n\nВысылаю подписанный с нашей стороны скан договора №118. Оригинал отправлен курьером, трек во вложении.', date: 'Вчера', seen: true, flagged: false, hasAttachments: true },
  { id: 'm4', folder: 'INBOX', from: 'HR <hr@company.local>', subject: 'График отпусков на 2027 год — заполните до 1 ноября', snippet: 'Напоминаем о необходимости утвердить график отпусков подразделений…', body: 'Руководителям подразделений: утвердите графики отпусков на 2027 год до 1 ноября. Форма — во внутреннем канале HR.', date: '17 окт', seen: true, flagged: false, hasAttachments: false },
  { id: 'm5', folder: 'proj', from: 'Козлов Дмитрий <d.kozlov@company.local>', subject: 'Миграция со старого почтового сервера: пилот — 50 ящиков', snippet: 'Пилотная группа переведена, imapsync отработал без потерь, жалобы только на…', body: 'Пилот: 50 ящиков мигрированы imapsync-ом без потерь. Из замечаний — у трёх пользователей на Windows 7 пришлось вручную прописать сервер в Thunderbird (автоконфиг починили). Предлагаю на следующей неделе переводить бухгалтерию.', date: '16 окт', seen: false, flagged: false, hasAttachments: false },
];

export interface DemoRoom { id: string; title: string; kind: string; unread: number; online?: boolean }
export const DEMO_ROOMS: DemoRoom[] = [
  { id: 'r1', title: '📢 Объявления компании', kind: 'Системный канал · только чтение', unread: 1 },
  { id: 'r2', title: 'Петрова Анна', kind: 'Личные сообщения', unread: 2, online: true },
  { id: 'r3', title: 'Бухгалтерия', kind: 'Групповой чат · 8 участников', unread: 0 },
  { id: 'r4', title: 'Руководство', kind: 'Приватный канал · 5 участников', unread: 0 },
  { id: 'r5', title: 'Козлов Дмитрий', kind: 'Личные сообщения', unread: 0, online: false },
];

export interface DemoMsg { id: string; roomId: string; author: string; text: string; time: string; mine?: boolean }
export const DEMO_MESSAGES: DemoMsg[] = [
  { id: 'c1', roomId: 'r2', author: 'Петрова Анна', text: 'Доброе утро! Реестр за октябрь у вас в почте, три платежа ждут подтверждения.', time: '09:40' },
  { id: 'c2', roomId: 'r2', author: 'Вы', text: 'Спасибо, посмотрю до обеда. **Срочный** там какой?', time: '09:44', mine: true },
  { id: 'c3', roomId: 'r2', author: 'Петрова Анна', text: 'Поставщику оборудования — у него отгрузка привязана к предоплате.', time: '09:45' },
  { id: 'c4', roomId: 'r1', author: 'IT-отдел', text: 'В воскресенье 02:00–04:00 плановое обновление Corvex. Почта и чат будут недоступны.', time: 'Вчера' },
  { id: 'c5', roomId: 'r3', author: 'Соколова Мария', text: '@i.ivanov счёт 118 от поставщика пришёл, положила в общую папку.', time: '10:12' },
];

export const PERMISSION_LABELS: Record<string, string> = {
  MAIL_READ_OWN: 'Читать свою почту', MAIL_SEND_INTERNAL: 'Писать внутри компании',
  MAIL_SEND_EXTERNAL: 'Писать во внешние домены', MAIL_MANAGE_FOLDERS: 'Папки и правила сортировки',
  MAIL_BULK_SEND: 'Массовые рассылки', MAIL_DELEGATE_ACCESS: 'Делегированный доступ к ящикам',
  MAIL_EXPORT: 'Экспорт ящика', CHAT_READ: 'Читать чаты', CHAT_WRITE: 'Писать в чаты',
  CHAT_CREATE_GROUP: 'Создавать групповые чаты', CHAT_CREATE_CHANNEL: 'Создавать каналы',
  CHAT_MODERATE: 'Модерация каналов', CHAT_SEND_FILES: 'Отправлять файлы в чат',
  CHAT_VOICE_VIDEO: 'Голосовые и видео', ADMIN_USERS_MANAGE: 'Управлять пользователями',
  ADMIN_GROUPS_MANAGE: 'Группы и права', ADMIN_QUOTAS_MANAGE: 'Дисковые квоты',
  ADMIN_SECURITY_POLICY: 'Политики безопасности и 2FA', ADMIN_MAIL_POLICY: 'Спам/антивирус/лимиты',
  ADMIN_SERVER_CONFIG: 'Настройки сервера и сертификаты', ADMIN_BACKUP: 'Резервное копирование',
  ANALYTICS_VIEW_OWN_UNIT: 'Аналитика своего подразделения', ANALYTICS_VIEW_COMPANY: 'Аналитика по компании',
  AUDIT_VIEW: 'Журнал аудита',
};

export const DEMO_GROUPS: { code: string; name: string; isSystem: boolean; enabled: boolean; memberCount: number; permissions: string[] }[] = [
  { code: 'SUPER_ADMIN', name: 'Супер администратор', isSystem: true, enabled: true, memberCount: 1, permissions: Object.keys(PERMISSION_LABELS) },
  { code: 'ADMIN', name: 'Администратор', isSystem: true, enabled: true, memberCount: 3, permissions: ['MAIL_READ_OWN','MAIL_SEND_INTERNAL','MAIL_SEND_EXTERNAL','MAIL_MANAGE_FOLDERS','CHAT_READ','CHAT_WRITE','CHAT_CREATE_GROUP','CHAT_CREATE_CHANNEL','CHAT_MODERATE','CHAT_SEND_FILES','ADMIN_USERS_MANAGE','ADMIN_GROUPS_MANAGE','ADMIN_QUOTAS_MANAGE','ADMIN_SECURITY_POLICY','ADMIN_MAIL_POLICY','ADMIN_BACKUP','ANALYTICS_VIEW_COMPANY','AUDIT_VIEW'] },
  { code: 'DIRECTOR', name: 'Директор', isSystem: true, enabled: true, memberCount: 2, permissions: ['MAIL_READ_OWN','MAIL_SEND_INTERNAL','MAIL_SEND_EXTERNAL','MAIL_MANAGE_FOLDERS','MAIL_DELEGATE_ACCESS','MAIL_EXPORT','CHAT_READ','CHAT_WRITE','CHAT_CREATE_GROUP','CHAT_SEND_FILES','CHAT_VOICE_VIDEO','ANALYTICS_VIEW_COMPANY'] },
  { code: 'ACCOUNTANT', name: 'Бухгалтер', isSystem: true, enabled: true, memberCount: 8, permissions: ['MAIL_READ_OWN','MAIL_SEND_INTERNAL','MAIL_SEND_EXTERNAL','MAIL_MANAGE_FOLDERS','MAIL_BULK_SEND','CHAT_READ','CHAT_WRITE','CHAT_CREATE_GROUP','CHAT_SEND_FILES'] },
  { code: 'MANAGER', name: 'Руководитель', isSystem: true, enabled: true, memberCount: 24, permissions: ['MAIL_READ_OWN','MAIL_SEND_INTERNAL','MAIL_SEND_EXTERNAL','MAIL_MANAGE_FOLDERS','CHAT_READ','CHAT_WRITE','CHAT_CREATE_GROUP','CHAT_SEND_FILES','CHAT_VOICE_VIDEO','ANALYTICS_VIEW_OWN_UNIT'] },
  { code: 'USER', name: 'Обычный пользователь', isSystem: true, enabled: true, memberCount: 812, permissions: ['MAIL_READ_OWN','MAIL_SEND_INTERNAL','MAIL_SEND_EXTERNAL','MAIL_MANAGE_FOLDERS','CHAT_READ','CHAT_WRITE','CHAT_CREATE_GROUP','CHAT_SEND_FILES'] },
];

export interface DemoAdminUser {
  id: string;
  username: string;
  email: string;
  displayName: string;
  source: string;
  status: string;
  department: string | null;
  groups: string[];
  quotaBytes: number;
  usedBytes: number;
  mustChangePassword: boolean;
  totpEnabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export const DEMO_USERS: DemoAdminUser[] = [
  { id: 'demo-u1', username: 'admin', email: 'admin@company.local', displayName: 'Супер администратор', source: 'LOCAL', status: 'ACTIVE', department: 'IT', groups: ['SUPER_ADMIN'], quotaBytes: 5368709120, usedBytes: 0, mustChangePassword: false, totpEnabled: true, lastLoginAt: '2026-10-19T08:15:00Z', createdAt: '2026-10-01T00:00:00Z' },
  { id: 'demo-u2', username: 'i.ivanov', email: 'Ivanov@company.local', displayName: 'Ivanov', source: 'LOCAL', status: 'ACTIVE', department: 'Руководство', groups: ['DIRECTOR'], quotaBytes: 10737418240, usedBytes: 0, mustChangePassword: false, totpEnabled: false, lastLoginAt: '2026-10-19T07:40:00Z', createdAt: '2026-10-02T00:00:00Z' },
  { id: 'demo-u3', username: 'a.petrova', email: 'a.petrova@company.local', displayName: 'Петрова Анна', source: 'LOCAL', status: 'ACTIVE', department: 'Бухгалтерия', groups: ['ACCOUNTANT'], quotaBytes: 5368709120, usedBytes: 0, mustChangePassword: true, totpEnabled: false, lastLoginAt: null, createdAt: '2026-10-18T00:00:00Z' },
  { id: 'demo-u4', username: 'd.kozlov', email: 'd.kozlov@company.local', displayName: 'Козлов Дмитрий', source: 'LOCAL', status: 'BLOCKED', department: 'IT', groups: ['MANAGER', 'USER'], quotaBytes: 5368709120, usedBytes: 0, mustChangePassword: false, totpEnabled: false, lastLoginAt: '2026-10-15T11:02:00Z', createdAt: '2026-10-03T00:00:00Z' },
];

export interface DemoAuditEntry {
  id: number;
  actorId: string | null;
  actorName: string | null;
  action: string;
  entity: string | null;
  entityId: string | null;
  details: Record<string, unknown>;
  ipAddress: string | null;
  createdAt: string;
}

export const DEMO_AUDIT: DemoAuditEntry[] = [
  { id: 5, actorId: 'demo-u1', actorName: 'admin', action: 'USER_BLOCKED', entity: 'user', entityId: 'demo-u4', details: {}, ipAddress: '192.168.1.10', createdAt: '2026-10-18T15:20:00Z' },
  { id: 4, actorId: 'demo-u1', actorName: 'admin', action: 'USER_CREATED', entity: 'user', entityId: 'demo-u3', details: { username: 'a.petrova', groups: ['ACCOUNTANT'] }, ipAddress: '192.168.1.10', createdAt: '2026-10-18T09:05:00Z' },
  { id: 3, actorId: 'demo-u1', actorName: 'admin', action: 'GROUP_PERMISSIONS_CHANGED', entity: 'group', entityId: 'ACCOUNTANT', details: { added: ['MAIL_BULK_SEND'], removed: [] }, ipAddress: '192.168.1.10', createdAt: '2026-10-17T12:44:00Z' },
  { id: 2, actorId: null, actorName: 'system', action: 'BOOTSTRAP_SUPERADMIN_ASSIGNED', entity: 'user', entityId: 'demo-u1', details: { group: 'SUPER_ADMIN' }, ipAddress: null, createdAt: '2026-10-01T00:01:00Z' },
];
