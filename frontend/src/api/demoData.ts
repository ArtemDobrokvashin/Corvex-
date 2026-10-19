import type { Locale } from '../i18n';

/**
 * ДЕМО-ДАННЫЕ. Используются только когда gateway недоступен (режим витрины без поднятого бэкенда):
 * интерфейс помечает режим плашкой. В продакшене все экраны ходят в apiFetch по контрактам docs/api/*.
 * English mirrors below intentionally keep the same IDs, so switching RU/EN
 * can preserve read/star/folder state while replacing display-only content.
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
  { id: 'r1', title: 'Объявления компании', kind: 'Системный канал · только чтение', unread: 1 },
  { id: 'r2', title: 'Петрова Анна', kind: 'Личные сообщения', unread: 2, online: true },
  { id: 'r3', title: 'Бухгалтерия', kind: 'Групповой чат · 8 участников', unread: 0 },
  { id: 'r4', title: 'Руководство', kind: 'Приватный канал · 5 участников', unread: 0 },
  { id: 'r5', title: 'Козлов Дмитрий', kind: 'Личные сообщения', unread: 0, online: false },
];

export interface DemoMsg { id: string; roomId: string; author: string; text: string; time: string; mine?: boolean }
export const DEMO_MESSAGES: DemoMsg[] = [
  { id: 'c1', roomId: 'r2', author: 'Петрова Анна', text: 'Доброе утро! Реестр за октябрь у вас в почте, три платежа ждут подтверждения.', time: '09:40' },
  { id: 'c2', roomId: 'r2', author: 'Вы', text: 'Спасибо, посмотрю до обеда. **Срочный** там какой?', time: '09:44', mine: true },
  { id: 'c3', roomId: 'r2', author: 'Петрова Анна', text: 'Поставщику оборудования — у него отгрузка привязана к предоплате. 🚚', time: '09:45' },
  { id: 'c4', roomId: 'r1', author: 'IT-отдел', text: 'В воскресенье 02:00–04:00 плановое обновление Corvex. Почта и чат будут недоступны.', time: 'Вчера' },
  { id: 'c5', roomId: 'r3', author: 'Соколова Мария', text: '@i.ivanov счёт 118 от поставщика пришёл, положила в общую папку. ✅', time: '10:12' },
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

// ---------------- English demo mirrors ----------------

export const DEMO_FOLDERS_EN = [
  { id: 'INBOX', name: 'Inbox', unread: 3 },
  { id: 'SENT', name: 'Sent', unread: 0 },
  { id: 'DRAFTS', name: 'Drafts', unread: 1 },
  { id: 'ARCHIVE', name: 'Archive', unread: 0 },
  { id: 'SPAM', name: 'Spam', unread: 5 },
  { id: 'TRASH', name: 'Trash', unread: 0 },
  { id: 'proj', name: 'Projects / Corvex migration', unread: 1 },
];

export const DEMO_MAILS_EN: DemoMail[] = [
  { id: 'm1', folder: 'INBOX', from: 'Anna Petrova <a.petrova@company.local>', subject: 'October payment summary', snippet: 'Team, here is the register: 47 payments, and 3 need your approval by Friday…', body: 'Team,\n\nhere is the October payment register: 47 items, and three payments above the limit need your approval by Friday.\n\nThe export from the accounting system is attached.\n\n— Anna Petrova, Accounting', date: '09:42', seen: false, flagged: true, hasAttachments: true },
  { id: 'm2', folder: 'INBOX', from: 'IT Department <it@company.local>', subject: 'Scheduled server maintenance on Sunday, 02:00–04:00', snippet: 'Mail and chat will be unavailable while Corvex is updated to 1.1…', body: 'Scheduled maintenance will take place on Sunday from 02:00 to 04:00 for the Corvex platform update.\nMail (including Outlook and Thunderbird) and corporate chat will be unavailable. Incoming mail will not be lost—it will remain queued with the senders.', date: 'Yesterday', seen: false, flagged: false, hasAttachments: false },
  { id: 'm3', folder: 'INBOX', from: 'Oleg Smirnov <o.smirnov@partner.example>', subject: 'Re: Supply agreement No. 118 — signed scan', snippet: 'Good afternoon! Here is the signed scan from our side; the original is on its way by courier…', body: 'Good afternoon!\n\nHere is the copy of agreement No. 118 signed by our side. The original has been sent by courier, and the tracking details are attached.', date: 'Yesterday', seen: true, flagged: false, hasAttachments: true },
  { id: 'm4', folder: 'INBOX', from: 'HR <hr@company.local>', subject: '2027 vacation schedule — please complete by November 1', snippet: 'A reminder to approve department vacation schedules…', body: 'Department managers: please approve the 2027 vacation schedules by November 1. The form is available in the internal HR channel.', date: 'Oct 17', seen: true, flagged: false, hasAttachments: false },
  { id: 'm5', folder: 'proj', from: 'Dmitry Kozlov <d.kozlov@company.local>', subject: 'Migration from the old mail server: 50-mailbox pilot', snippet: 'The pilot group has moved, imapsync completed without losses, and the only complaints were about…', body: 'Pilot update: 50 mailboxes were migrated with imapsync and no losses. Three Windows 7 users needed the server entered manually in Thunderbird (autoconfig is now fixed). I suggest moving Accounting next week.', date: 'Oct 16', seen: false, flagged: false, hasAttachments: false },
];

export const DEMO_ROOMS_EN: DemoRoom[] = [
  { id: 'r1', title: 'Company announcements', kind: 'System channel · read-only', unread: 1 },
  { id: 'r2', title: 'Anna Petrova', kind: 'Direct messages', unread: 2, online: true },
  { id: 'r3', title: 'Accounting', kind: 'Group chat · 8 members', unread: 0 },
  { id: 'r4', title: 'Leadership', kind: 'Private channel · 5 members', unread: 0 },
  { id: 'r5', title: 'Dmitry Kozlov', kind: 'Direct messages', unread: 0, online: false },
];

export const DEMO_MESSAGES_EN: DemoMsg[] = [
  { id: 'c1', roomId: 'r2', author: 'Anna Petrova', text: 'Good morning! The October register is in your inbox, and three payments are waiting for approval.', time: '09:40' },
  { id: 'c2', roomId: 'r2', author: 'You', text: 'Thanks, I will review it before lunch. Which one is **urgent**?', time: '09:44', mine: true },
  { id: 'c3', roomId: 'r2', author: 'Anna Petrova', text: 'The equipment supplier—the shipment depends on prepayment. 🚚', time: '09:45' },
  { id: 'c4', roomId: 'r1', author: 'IT Department', text: 'Corvex will be updated on Sunday from 02:00 to 04:00. Mail and chat will be unavailable.', time: 'Yesterday' },
  { id: 'c5', roomId: 'r3', author: 'Maria Sokolova', text: '@i.ivanov invoice 118 from the supplier has arrived; I put it in the shared folder. ✅', time: '10:12' },
];

export const PERMISSION_LABELS_EN: Record<string, string> = {
  MAIL_READ_OWN: 'Read own mail', MAIL_SEND_INTERNAL: 'Send inside the company',
  MAIL_SEND_EXTERNAL: 'Send to external domains', MAIL_MANAGE_FOLDERS: 'Folders and sorting rules',
  MAIL_BULK_SEND: 'Bulk mailing', MAIL_DELEGATE_ACCESS: 'Delegated mailbox access',
  MAIL_EXPORT: 'Export mailbox', CHAT_READ: 'Read chats', CHAT_WRITE: 'Write in chats',
  CHAT_CREATE_GROUP: 'Create group chats', CHAT_CREATE_CHANNEL: 'Create channels',
  CHAT_MODERATE: 'Moderate channels', CHAT_SEND_FILES: 'Send files in chat',
  CHAT_VOICE_VIDEO: 'Voice and video', ADMIN_USERS_MANAGE: 'Manage users',
  ADMIN_GROUPS_MANAGE: 'Groups and permissions', ADMIN_QUOTAS_MANAGE: 'Disk quotas',
  ADMIN_SECURITY_POLICY: 'Security policies and 2FA', ADMIN_MAIL_POLICY: 'spam/antivirus/limits',
  ADMIN_SERVER_CONFIG: 'Server settings and certificates', ADMIN_BACKUP: 'Backup',
  ANALYTICS_VIEW_OWN_UNIT: 'Own department analytics', ANALYTICS_VIEW_COMPANY: 'Company analytics',
  AUDIT_VIEW: 'Audit log',
};

export const DEMO_GROUPS_EN = DEMO_GROUPS.map((group) => ({
  ...group,
  name: {
    SUPER_ADMIN: 'Super administrator',
    ADMIN: 'Administrator',
    DIRECTOR: 'Director',
    ACCOUNTANT: 'Accountant',
    MANAGER: 'Manager',
    USER: 'Regular user',
  }[group.code] ?? group.name,
}));

export const DEMO_USERS_EN: DemoAdminUser[] = DEMO_USERS.map((user) => ({
  ...user,
  displayName: {
    'demo-u1': 'Super administrator',
    'demo-u2': 'Ivan Ivanov',
    'demo-u3': 'Anna Petrova',
    'demo-u4': 'Dmitry Kozlov',
  }[user.id] ?? user.displayName,
  department: user.department === 'Руководство' ? 'Leadership' : user.department === 'Бухгалтерия' ? 'Accounting' : user.department,
}));

export function getDemoUser(locale: Locale) {
  return locale === 'en' ? { ...DEMO_USER, displayName: 'Ivan Ivanov' } : DEMO_USER;
}

export const demoFolders = (locale: Locale) => (locale === 'en' ? DEMO_FOLDERS_EN : DEMO_FOLDERS);
export const demoMails = (locale: Locale) => (locale === 'en' ? DEMO_MAILS_EN : DEMO_MAILS);
export const demoRooms = (locale: Locale) => (locale === 'en' ? DEMO_ROOMS_EN : DEMO_ROOMS);
export const demoMessages = (locale: Locale) => (locale === 'en' ? DEMO_MESSAGES_EN : DEMO_MESSAGES);
export const permissionLabels = (locale: Locale) => (locale === 'en' ? PERMISSION_LABELS_EN : PERMISSION_LABELS);
export const demoGroups = (locale: Locale) => (locale === 'en' ? DEMO_GROUPS_EN : DEMO_GROUPS);
export const demoUsers = (locale: Locale) => (locale === 'en' ? DEMO_USERS_EN : DEMO_USERS);
