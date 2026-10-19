import { useCallback } from 'react';
import { create } from 'zustand';

export type Locale = 'ru' | 'en';

export type TranslationParams = Record<string, string | number>;

const STORAGE_KEY = 'corvex.locale';

const TITLES: Record<Locale, string> = {
  ru: 'Corvex — корпоративная почта и чат',
  en: 'Corvex — corporate mail and chat',
};

function detectLocale(): Locale {
  if (typeof window === 'undefined') return 'ru';
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved === 'ru' || saved === 'en') return saved;
  } catch {
    // Private mode / disabled storage: browser language is still available.
  }
  const languages = window.navigator.languages?.length ? window.navigator.languages : [window.navigator.language];
  for (const language of languages) {
    const normalized = language.toLowerCase();
    if (normalized.startsWith('ru')) return 'ru';
    if (normalized.startsWith('en')) return 'en';
  }
  return 'ru';
}

function applyLocale(locale: Locale) {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = locale;
    document.title = TITLES[locale];
  }
  if (typeof window !== 'undefined') {
    try {
      window.localStorage.setItem(STORAGE_KEY, locale);
    } catch {
      // The in-memory choice still works for this tab.
    }
  }
}

interface LocaleState {
  locale: Locale;
  setLocale: (locale: Locale) => void;
}

export const useLocaleStore = create<LocaleState>((set) => ({
  locale: detectLocale(),
  setLocale: (locale) => {
    applyLocale(locale);
    set({ locale });
  },
}));

applyLocale(useLocaleStore.getState().locale);

export const getCurrentLocale = (): Locale => useLocaleStore.getState().locale;
export const localeTag = (locale: Locale): string => (locale === 'en' ? 'en-US' : 'ru-RU');

/**
 * Russian is the source language and the translation key. This keeps the
 * existing product copy readable in components and makes missing English
 * entries easy to detect in tests; English lives in one dictionary below.
 */
const EN: Record<string, string> = {
  // Shell and shared UI
  'Открыть почту': 'Open Mail',
  'Входящие и папки': 'Inbox and folders',
  'Написать письмо': 'Write an email',
  'Новый черновик': 'New draft',
  'Открыть сообщения': 'Open Messages',
  'Каналы и личные чаты': 'Channels and direct chats',
  'Администрирование': 'Administration',
  'Пользователи, группы и аудит': 'Users, groups, and audit',
  'Безопасность': 'Security',
  'Пароль, 2FA и пароли приложений': 'Password, 2FA, and app passwords',
  'Выйти из демо': 'Exit demo',
  'Выйти': 'Sign out',
  'Завершить текущую сессию': 'End the current session',
  'Восстанавливаем сессию': 'Restoring your session',
  'Переходим ко входу': 'Taking you to sign in',
  'Почтовый модуль появится на этапе 2 — письма ниже демонстрационные. Вход и админка уже работают с сервером.': 'The mail module arrives in Stage 2—the messages below are a demo. Sign-in and Administration already use the server.',
  'Realtime-чат появится на этапе 4 — ниже демонстрация будущего интерфейса.': 'Realtime chat arrives in Stage 4—the interface below is a preview.',
  'Быстрый переход': 'Quick switcher',
  'Профиль': 'Profile',
  'Corvex — почта': 'Corvex — Mail',
  'Основная навигация': 'Primary navigation',
  'Почта': 'Mail',
  'Чат': 'Chat',
  'Админ': 'Admin',
  'Перейти': 'Go to',
  'Демо': 'Demo',
  'Профиль пользователя': 'User profile',
  'Демо-режим: сервер не подключён, данные учебные.': 'Demo mode: no server is connected, and the data is sample data.',
  'Скрыть уведомление': 'Dismiss notification',
  'Сообщения': 'Messages',
  'Закрыть меню': 'Close menu',
  'В сети': 'Online',
  'Перейти или выполнить действие…': 'Jump to or run an action…',
  'Ничего не найдено': 'Nothing found',
  'Язык интерфейса': 'Interface language',
  'Русский': 'Russian',
  'Английский': 'English',
  'Загружаем рабочее пространство': 'Loading your workspace',
  'Загружаем почту…': 'Loading Mail…',
  'Загружаем сообщения…': 'Loading Messages…',
  'Загружаем администрирование…': 'Loading Administration…',

  // Login
  'Корпоративные коммуникации': 'Corporate communications',
  'Рабочее пространство компании': 'Your company workspace',
  'Почта и сообщения.': 'Mail and messages.',
  'Без лишнего шума.': 'Without the noise.',
  'Один спокойный интерфейс для писем, командных чатов и внутренних сервисов вашей организации.': 'One calm interface for email, team chat, and your organization’s internal services.',
  'Поиск по почте и людям': 'Search mail and people',
  'Входящие': 'Inbox',
  'Отправленные': 'Sent',
  'Архив': 'Archive',
  'Реестр уже в почте, посмотрите до обеда.': 'The register is in your inbox—please review it before lunch.',
  'Данные внутри компании': 'Data stays inside your company',
  '2FA и пароли приложений': '2FA and app passwords',
  'Быстрый переход Ctrl+K': 'Quick switcher Ctrl+K',
  'Корпоративная почта и чат': 'Corporate mail and chat',
  'С возвращением': 'Welcome back',
  'Войдите, чтобы продолжить работу с почтой и сообщениями компании.': 'Sign in to continue with company mail and messages.',
  'Логин или e-mail': 'Username or email',
  'Пароль': 'Password',
  'Введите пароль': 'Enter your password',
  'Скрыть пароль': 'Hide password',
  'Показать пароль': 'Show password',
  'Проверяем…': 'Checking…',
  'Войти': 'Sign in',
  'Первый вход администратора — с паролем из лога auth-service или переменной окружения.': 'For the administrator’s first sign-in, use the password from the auth-service log or environment variable.',
  'Подтвердите вход': 'Confirm sign-in',
  'Введите шестизначный код из приложения-аутентификатора.': 'Enter the six-digit code from your authenticator app.',
  'Код двухфакторной аутентификации': 'Two-factor authentication code',
  'Подтвердить': 'Confirm',
  'Назад ко входу': 'Back to sign in',
  'Сервер не отвечает. Демо покажет интерфейс на учебных данных, но это не настоящий вход.': 'The server is not responding. The demo shows the interface with sample data, but it is not a real sign-in.',
  'Посмотреть демо без сервера': 'View the demo without a server',
  'Сессия защищена HttpOnly refresh-cookie и короткоживущим JWT': 'Your session is protected by an HttpOnly refresh cookie and a short-lived JWT',

  // Password change and Security settings
  'Не короче 10 символов': 'At least 10 characters',
  'Есть буквы и цифры': 'Contains letters and digits',
  'Повтор совпадает': 'Confirmation matches',
  'Новые пароли не совпадают': 'The new passwords do not match',
  'Пароль должен быть не короче 10 символов и содержать буквы и цифры': 'The password must be at least 10 characters and contain letters and digits',
  'Задайте постоянный пароль': 'Set a permanent password',
  'Администратор выдал временный пароль. Он перестанет подходить сразу после этой смены.': 'An administrator issued a temporary password. It will stop working as soon as you change it.',
  'Временный пароль': 'Temporary password',
  'Новый пароль': 'New password',
  'Повторите пароль': 'Repeat the password',
  'Требования к паролю': 'Password requirements',
  'Сохраняем…': 'Saving…',
  'Сменить пароль и продолжить': 'Change password and continue',
  'В демо-режиме настройки недоступны — нужен запущенный сервер': 'Settings are unavailable in demo mode—a running server is required',
  'Не удалось скопировать автоматически — выделите текст вручную': 'Automatic copying failed—select the text manually',
  'Пароль изменён. Другие устройства разлогинены.': 'Password changed. Other devices have been signed out.',
  'Двухфакторная аутентификация включена': 'Two-factor authentication is enabled',
  'Двухфакторная аутентификация отключена': 'Two-factor authentication is disabled',
  'Закрыть настройки': 'Close settings',
  'Это демонстрационная сессия: экран можно изучить, но изменения не сохранятся без сервера.': 'This is a demo session: you can explore the screen, but changes will not be saved without a server.',
  'После смены пароля все остальные устройства выйдут из Corvex.': 'After you change your password, all other devices will be signed out of Corvex.',
  'Текущий пароль': 'Current password',
  'Минимум 10 символов, буквы и цифры': 'At least 10 characters, with letters and digits',
  'Сменить пароль': 'Change password',
  'Двухфакторная аутентификация': 'Two-factor authentication',
  'Одноразовый код из приложения защищает аккаунт даже при утечке пароля.': 'A one-time code from an app protects your account even if your password leaks.',
  'Включена': 'Enabled',
  'Выключена': 'Disabled',
  '· TOTP, шаг 30 секунд': '· TOTP, 30-second step',
  'Настроить аутентификатор': 'Set up an authenticator',
  'Добавьте секрет в приложение-аутентификатор, затем введите первый код для подтверждения.': 'Add the secret to your authenticator app, then enter the first code to confirm.',
  'Копировать секрет': 'Copy secret',
  'otpauth-ссылка для QR': 'otpauth link for a QR code',
  'Код из приложения': 'Code from the app',
  'Включить 2FA': 'Enable 2FA',
  'Текущий код для отключения': 'Current code to disable 2FA',
  'Отключить 2FA': 'Disable 2FA',
  'Пароли приложений': 'App passwords',
  'Отдельные пароли для Outlook и Thunderbird. Они особенно нужны при включённой 2FA.': 'Separate passwords for Outlook and Thunderbird. They are especially important when 2FA is enabled.',
  'Сохраните пароль сейчас — он показывается один раз.': 'Save this password now—it is shown only once.',
  'Копировать': 'Copy',
  'Например: Thunderbird дома': 'For example: Thunderbird at home',
  'Название пароля приложения': 'App password name',
  'Создать': 'Create',
  'Паролей приложений пока нет.': 'No app passwords yet.',
  'Создан {date}': 'Created {date}',
  'использользовался {date}': 'last used {date}',
  'ещё не использовался': 'not used yet',
  'Отозвать': 'Revoke',

  // Mail
  'Вы': 'You',
  '(без темы)': '(no subject)',
  'Сейчас': 'Now',
  'Написать': 'Compose',
  'Папки': 'Folders',
  'Папки почты': 'Mail folders',
  'Ящик': 'Mailbox',
  'Использовано {used} из {total}': '{used} of {total} used',
  'Сортировка': 'Sort',
  'Фильтры': 'Filters',
  'Поиск по теме, отправителю, тексту': 'Search subject, sender, or text',
  'Фильтр писем': 'Mail filter',
  'Все': 'All',
  'Непрочитанные': 'Unread',
  'Непрочитано': 'Unread',
  'Убрать из важных': 'Remove star',
  'Отметить важным': 'Star message',
  'Здесь пока тихо': 'It’s quiet here',
  'По этому запросу писем не нашлось. Попробуйте другое слово или сбросьте фильтр.': 'No mail matches this search. Try another word or reset the filter.',
  'В этой папке нет писем для выбранного фильтра.': 'There is no mail in this folder for the selected filter.',
  'К списку': 'Back to list',
  'В архив': 'Archive',
  'Удалить': 'Delete',
  'Важное': 'Starred',
  'Ещё действия': 'More actions',
  '{current} из {total}': '{current} of {total}',
  'Предыдущее письмо': 'Previous message',
  'Следующее письмо': 'Next message',
  'внутренний отправитель': 'internal sender',
  'мне': 'to me',
  'Файл во вложении': 'Attached file',
  'Проверено антивирусом · безопасно открыть': 'Scanned by antivirus · safe to open',
  'Скачать': 'Download',
  'Ответить': 'Reply',
  'Пересылаемое письмо': 'Forwarded message',
  'Переслать': 'Forward',
  'Выберите письмо': 'Select a message',
  'Откройте письмо из списка или двигайтесь по нему клавишами J и K.': 'Open a message from the list or move through it with J and K.',
  'навигация': 'navigation',
  'написать': 'compose',
  'поиск': 'search',
  'Новое письмо': 'New message',
  'Ответ / пересылка': 'Reply / forward',
  'Свернуть черновик': 'Minimize draft',
  'Закрыть черновик': 'Close draft',
  'Письмо отправлено': 'Message sent',
  'Копия сохранена в папке «Отправленные».': 'A copy was saved in Sent.',
  'Кому': 'To',
  'Копия': 'Cc',
  'Тема': 'Subject',
  'О чём письмо?': 'What is this message about?',
  'Напишите сообщение…': 'Write your message…',
  'Отправить': 'Send',
  'Прикрепить файлы': 'Attach files',
  'Эмодзи': 'Emoji',
  'Подпись добавится автоматически': 'Your signature will be added automatically',
  'Удалить черновик': 'Discard draft',

  // Chat
  'Новый разговор': 'New conversation',
  'Поиск людей и каналов': 'Search people and channels',
  'Каналы и группы': 'Channels and groups',
  'Личные сообщения': 'Direct messages',
  'Попробуйте другое имя или название канала.': 'Try another name or channel title.',
  'Не в сети': 'Offline',
  'Чатов пока нет': 'No chats yet',
  'К списку чатов': 'Back to chats',
  'Поиск в разговоре': 'Search in conversation',
  'Аудиозвонок': 'Audio call',
  'Видеозвонок': 'Video call',
  'Сегодня': 'Today',
  'Прочитано': 'Read',
  'Нравится': 'Like',
  'Ещё': 'More',
  'Ещё действия с сообщением': 'More message actions',
  'Пока нет сообщений': 'No messages yet',
  'Начните разговор первым сообщением ниже.': 'Start the conversation with the first message below.',
  'Это канал объявлений. Публиковать могут только администраторы.': 'This is an announcement channel. Only administrators can post.',
  'Сообщение для {name}…': 'Message {name}…',
  'Прикрепить файл': 'Attach a file',
  'Голосовое сообщение': 'Voice message',
  'Enter — отправить · Shift+Enter — новая строка': 'Enter to send · Shift+Enter for a new line',
  'Отправить сообщение': 'Send message',
  'Реакция: большой палец вверх': 'Reaction: thumbs up',

  // Administration
  'Организация': 'Organization',
  'Люди, доступы и история изменений. Опасные действия всегда подтверждаются и попадают в аудит.': 'People, access, and change history. Dangerous actions always require confirmation and are audited.',
  'Разделы администрирования': 'Administration sections',
  'Пользователи': 'Users',
  'Учётные записи и статусы': 'Accounts and statuses',
  'Группы и права': 'Groups and permissions',
  'Кто что может делать': 'Who can do what',
  'Аудит': 'Audit',
  'История административных действий': 'History of administrative actions',
  'Как действуют права': 'How permissions work',
  'Итоговый доступ человека — объединение прав всех его групп. Новые права попадут в токены при обновлении сессии, не позднее 15 минут.': 'A person’s final access is the union of all their groups’ permissions. New permissions enter tokens when the session refreshes, within 15 minutes.',
  'Не удалось загрузить группы: {error}': 'Could not load groups: {error}',
  'Активен': 'Active',
  'Заблокирован': 'Blocked',
  'Отключён': 'Disabled',
  'Пользователь создан. Временный пароль он сменит при первом входе.': 'User created. They will change the temporary password at first sign-in.',
  'Отключить пользователя {name} ({username})? Сессии будут отозваны, группы сняты.': 'Disable user {name} ({username})? Sessions will be revoked and groups removed.',
  'Группы пользователя {username} обновлены.': 'Groups for {username} have been updated.',
  'Учётные записи сотрудников и сервисных администраторов': 'Employee and service administrator accounts',
  'Поиск людей…': 'Search people…',
  'Фильтр по статусу': 'Filter by status',
  'Все статусы': 'All statuses',
  'Активные': 'Active',
  'Заблокированные': 'Blocked',
  'Отключённые': 'Disabled',
  'Найти': 'Search',
  'Новый пользователь': 'New user',
  'Новая учётная запись': 'New account',
  'Логин': 'Username',
  'Имя и фамилия': 'Full name',
  'Подразделение': 'Department',
  'Не короче 10 символов, буквы и цифры': 'At least 10 characters, with letters and digits',
  'Пользователь обязательно сменит его при первом входе.': 'The user must change it at first sign-in.',
  'Группы доступа': 'Access groups',
  'Если ничего не выбрать, сервер назначит группу «Обычный пользователь».': 'If nothing is selected, the server assigns the “Regular user” group.',
  'Создать пользователя': 'Create user',
  'Отмена': 'Cancel',
  'Пользователь': 'User',
  'Отдел': 'Department',
  'Группы': 'Groups',
  'Статус': 'Status',
  'Последний вход': 'Last sign-in',
  'Действия': 'Actions',
  'локальный': 'local',
  '2FA включена': '2FA enabled',
  'Ожидает смену пароля': 'Password change pending',
  'Группы пользователя': 'User groups',
  'Сохранить': 'Save',
  '{used}% занято': '{used}% used',
  'Назначить группы': 'Assign groups',
  'Заблокировать': 'Block',
  'Разблокировать': 'Unblock',
  'Отключить пользователя': 'Disable user',
  'Пользователи не найдены': 'No users found',
  'Измените запрос или создайте новую учётную запись.': 'Change the search or create a new account.',
  'Назад': 'Back',
  'Вперёд': 'Next',
  'Письма, папки и отправка': 'Messages, folders, and sending',
  'Чаты, каналы и звонки': 'Chats, channels, and calls',
  'Пользователи, политики и сервер': 'Users, policies, and server',
  'Аналитика и аудит': 'Analytics and audit',
  'Отчёты и контроль изменений': 'Reports and change control',
  'Группы загружаются': 'Groups are loading',
  'Если это длится долго, проверьте соединение с сервером.': 'If this takes a long time, check the server connection.',
  'системная': 'system',
  'Своя группа': 'Custom group',
  'Код': 'Code',
  'Название': 'Name',
  'Отдел продаж': 'Sales department',
  'Создать группу': 'Create group',
  'Нужно право «Группы и права»': 'The “Groups and permissions” permission is required',
  'Состояние сервисов': 'Service health',
  'Нет данных. Возможно, у текущей роли нет права на системное состояние.': 'No data. The current role may not have permission to view system health.',
  'работает': 'running',
  'деградация': 'degraded',
  'недоступен': 'unavailable',
  'Свободно на диске:': 'Free disk space:',
  'Очередь почты:': 'Mail queue:',
  'нет данных RabbitMQ': 'no RabbitMQ data',
  'Сохранено': 'Saved',
  'Выберите возможности этой группы. Доступ сотрудника складывается из всех групп, в которые он входит.': 'Choose what this group can do. An employee’s access combines every group they belong to.',
  'Права суперадминистратора защищены от редактирования — это страховка от самоблокировки системы.': 'Super administrator permissions are protected from editing—this prevents locking the system out.',
  'Выбрано прав:': 'Selected permissions:',
  'Изменение записано в аудит': 'The change was written to the audit log',
  'Сохранить права': 'Save permissions',
  'Журнал аудита': 'Audit log',
  'Последние административные события: кто, когда и что изменил.': 'Recent administrative events: who changed what, and when.',
  'Время': 'Time',
  'Инициатор': 'Actor',
  'Действие': 'Action',
  'Объект': 'Object',
  'Детали': 'Details',
  'Система': 'System',
  'Записей пока нет': 'No entries yet',
  'Здесь появятся создание пользователей, изменения прав и другие события.': 'User creation, permission changes, and other events will appear here.',
  'elasticsearch (поиск)': 'elasticsearch (search)',
  'Бухгалтерия': 'Accounting',
  'Ivanov': 'Ivan Ivanov',

  // API and network errors (canonical server messages are Russian)
  'Неверный логин или пароль': 'Incorrect username or password',
  'Слишком много неверных попыток, попробуйте позже': 'Too many failed attempts. Please try again later',
  'Доступ запрещён': 'Access denied',
  'Сервер недоступен или произошла ошибка. Попробуйте позже': 'The server is unavailable or an error occurred. Please try again later',
  'Сеть недоступна: сервер не отвечает': 'Network unavailable: the server is not responding',
  'Недостаточно прав для этого действия': 'You do not have permission for this action',
  'Некорректный запрос': 'Invalid request',
  'Некорректный JSON запроса': 'Invalid request JSON',
  'Нет аутентификации': 'Authentication is required',
  'Некорректный токен': 'Invalid token',
  'Учётная запись заблокирована или отключена. Обратитесь к администратору': 'This account is blocked or disabled. Contact an administrator',
  'Второй фактор временно недоступен, попробуйте позже': 'The second factor is temporarily unavailable. Please try again later',
  'Сессия входа истекла, войдите заново': 'Your sign-in session has expired. Please sign in again',
  'Неверный код подтверждения': 'Incorrect confirmation code',
  'Учётная запись заблокирована или отключена': 'This account is blocked or disabled',
  'Нет refresh-токена': 'No refresh token',
  'Refresh-токен недействителен': 'The refresh token is invalid',
  'Refresh-токен истёк': 'The refresh token has expired',
  'Пользователь не существует': 'The user does not exist',
  'Учётная запись недоступна': 'This account is unavailable',
  'Текущий пароль неверен': 'The current password is incorrect',
  'Новый пароль должен отличаться от текущего': 'The new password must differ from the current one',
  'Сначала выполните настройку 2FA (totp/setup)': 'Set up 2FA first (totp/setup)',
  '2FA не включена': '2FA is not enabled',
  '2FA уже включена. Сначала отключите её текущим кодом': '2FA is already enabled. Disable it with a current code first',
  'Пароль приложения не найден': 'App password not found',
  'укажите логин': 'enter a username',
  'укажите пароль': 'enter a password',
  'нет mfaTicket': 'mfaTicket is missing',
  'код — 6 цифр': 'the code must contain 6 digits',
  'укажите название': 'enter a name',
  'неккоректный e-mail': 'invalid email address',
  'некорректный e-mail': 'invalid email address',
  'Пароль должен содержать буквы и цифры': 'The password must contain letters and digits',
  'groupCodes обязателен (может быть пустым)': 'groupCodes is required (it may be empty)',
  'код группы: латиница, цифры, _ и -': 'group code: Latin letters, digits, _ and -',
  'permissions обязателен (может быть пустым)': 'permissions is required (it may be empty)',
  'auth-service недоступен': 'auth-service is unavailable',
  'Права группы SUPER_ADMIN не редактируются (страховка от самоблокировки)': 'SUPER_ADMIN permissions cannot be edited (protection against locking the system out)',
  'Код группы не указан': 'No group code was provided',
  'Пустой набор настроек': 'The settings set is empty',
};

const API_PATTERNS: Array<{ pattern: RegExp; en: string; ru?: string }> = [
  {
    pattern: /^Слишком много неверных попыток\. Повторите через (\d+) минут$/,
    en: 'Too many failed attempts. Try again in $1 minutes',
  },
  {
    pattern: /^Пароль должен быть не короче (\d+) символов$/,
    en: 'The password must be at least $1 characters long',
  },
  {
    pattern: /^Пароль должен быть не длиннее (\d+) символов$/,
    en: 'The password must be at most $1 characters long',
  },
  {
    pattern: /^must not be blank$/i,
    en: 'must not be blank',
    ru: 'не должно быть пустым',
  },
  {
    pattern: /^must not be null$/i,
    en: 'must not be null',
    ru: 'обязательное поле',
  },
  {
    pattern: /^size must be between (\d+) and (\d+)$/i,
    en: 'size must be between $1 and $2',
    ru: 'размер должен быть от $1 до $2',
  },
  {
    pattern: /^length must be between (\d+) and (\d+)$/i,
    en: 'length must be between $1 and $2',
    ru: 'длина должна быть от $1 до $2',
  },
  {
    pattern: /^must be a well-formed email address$/i,
    en: 'must be a well-formed email address',
    ru: 'неккоректный e-mail',
  },
  {
    pattern: /^must be greater than or equal to 0$/i,
    en: 'must be greater than or equal to 0',
    ru: 'должно быть не меньше 0',
  },
  {
    pattern: /^must match ".*"$/i,
    en: 'has an invalid format',
    ru: 'не соответствует требуемому формату',
  },
];

const API_PREFIXES_EN: Record<string, string> = {
  'auth-service недоступен: ': 'auth-service is unavailable: ',
  'Группа уже существует: ': 'A group already exists: ',
  'Группа не найдена: ': 'Group not found: ',
  'Неизвестные коды прав: ': 'Unknown permission codes: ',
  'Неизвестная настройка: ': 'Unknown setting: ',
  'Значение настройки не может быть null: ': 'A setting value cannot be null: ',
  'Логин уже занят: ': 'This username is already taken: ',
  'E-mail уже занят: ': 'This email address is already taken: ',
  'Пользователь не найден: ': 'User not found: ',
  'Неизвестный статус пользователя: ': 'Unknown user status: ',
  'Недопустимый сегмент пути: ': 'Invalid path segment: ',
};

function interpolate(template: string, params?: TranslationParams): string {
  if (!params) return template;
  return template.replace(/\{([A-Za-z0-9_]+)\}/g, (whole, key: string) => (
    Object.prototype.hasOwnProperty.call(params, key) ? String(params[key]) : whole
  ));
}

export function translate(locale: Locale, source: string, params?: TranslationParams): string {
  const template = locale === 'en' ? (EN[source] ?? source) : source;
  return interpolate(template, params);
}

export function localizeApiDetail(detail: string, locale: Locale): string {
  if (!detail) return detail;

  // Bean Validation details have the form "field: message".
  const fieldSeparator = detail.indexOf(': ');
  if (fieldSeparator > 0) {
    const field = detail.slice(0, fieldSeparator);
    const message = detail.slice(fieldSeparator + 2);
    if (/^[A-Za-z][A-Za-z0-9_]*$/.test(field) && message) {
      const localizedMessage = localizeApiDetail(message, locale);
      if (localizedMessage !== message) return `${field}: ${localizedMessage}`;
    }
  }

  for (const { pattern, en, ru } of API_PATTERNS) {
    if (pattern.test(detail)) {
      return detail.replace(pattern, locale === 'en' ? en : (ru ?? detail));
    }
  }

  if (locale !== 'en') return detail;
  if (EN[detail]) return EN[detail];

  for (const [prefix, translatedPrefix] of Object.entries(API_PREFIXES_EN)) {
    if (detail.startsWith(prefix)) {
      return `${translatedPrefix}${localizeApiDetail(detail.slice(prefix.length), locale)}`;
    }
  }
  return detail;
}

export function useI18n() {
  const locale = useLocaleStore((s) => s.locale);
  const setLocale = useLocaleStore((s) => s.setLocale);
  const t = useCallback((source: string, params?: TranslationParams) => translate(locale, source, params), [locale]);
  return { locale, setLocale, t, tag: localeTag(locale) };
}

export function useT() {
  return useI18n().t;
}

export function formatNumber(locale: Locale, value: number): string {
  return new Intl.NumberFormat(localeTag(locale)).format(value);
}

export function formatDate(locale: Locale, value: string | Date): string {
  return new Intl.DateTimeFormat(localeTag(locale), { dateStyle: 'medium' }).format(new Date(value));
}

export function formatDateTime(locale: Locale, value: string | Date): string {
  return new Intl.DateTimeFormat(localeTag(locale), {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

export function formatTime(locale: Locale, value: string | Date): string {
  return new Intl.DateTimeFormat(localeTag(locale), { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

export function formatBytes(locale: Locale, bytes: number): string {
  if (bytes < 0) return '—';
  const value = bytes / 1024 ** 3;
  const formatted = new Intl.NumberFormat(localeTag(locale), {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).format(value);
  return `${formatted} ${locale === 'en' ? 'GB' : 'ГБ'}`;
}

export function plural(
  locale: Locale,
  count: number,
  ruForms: [string, string, string],
  enForms: [string, string],
): string {
  if (locale === 'en') return count === 1 ? enForms[0] : enForms[1];
  const absolute = Math.abs(count) % 100;
  const lastDigit = absolute % 10;
  if (absolute > 10 && absolute < 20) return ruForms[2];
  if (lastDigit > 1 && lastDigit < 5) return ruForms[1];
  if (lastDigit === 1) return ruForms[0];
  return ruForms[2];
}

export function countLabel(
  locale: Locale,
  count: number,
  ruForms: [string, string, string],
  enForms: [string, string],
): string {
  return `${formatNumber(locale, count)} ${plural(locale, count, ruForms, enForms)}`;
}

const SYSTEM_GROUP_NAMES: Record<Locale, Record<string, string>> = {
  ru: {
    SUPER_ADMIN: 'Супер администратор',
    ADMIN: 'Администратор',
    DIRECTOR: 'Директор',
    ACCOUNTANT: 'Бухгалтер',
    MANAGER: 'Руководитель',
    USER: 'Обычный пользователь',
  },
  en: {
    SUPER_ADMIN: 'Super administrator',
    ADMIN: 'Administrator',
    DIRECTOR: 'Director',
    ACCOUNTANT: 'Accountant',
    MANAGER: 'Manager',
    USER: 'Regular user',
  },
};

export function systemGroupName(locale: Locale, code: string, fallback?: string): string {
  return SYSTEM_GROUP_NAMES[locale][code] ?? fallback ?? code;
}

export function LanguageSwitcher({ className = '' }: { className?: string }) {
  const { locale, setLocale, t } = useI18n();
  const options: Array<{ value: Locale; short: string; label: string }> = [
    { value: 'ru', short: 'RU', label: t('Русский') },
    { value: 'en', short: 'EN', label: t('Английский') },
  ];
  return (
    <div className={`inline-flex rounded-full border border-line bg-chrome p-0.5 ${className}`} role="group" aria-label={t('Язык интерфейса')}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => setLocale(option.value)}
          aria-pressed={locale === option.value}
          aria-label={option.label}
          title={option.label}
          className={`h-7 min-w-9 rounded-full px-2 text-[11px] font-semibold transition ${locale === option.value ? 'bg-surface text-ink shadow-soft' : 'text-faint hover:text-ink'}`}
        >
          {option.short}
        </button>
      ))}
    </div>
  );
}

export const ENGLISH_TRANSLATIONS = EN;
