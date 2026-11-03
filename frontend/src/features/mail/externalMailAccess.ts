const has = (permissions: readonly string[] | undefined, permission: string) => Boolean(
  permissions?.includes(permission) || permissions?.includes('*'),
);

/** Владелец может видеть назначенные адреса и выбирать их в send-as. */
export const canUseExternalMail = (permissions: readonly string[] | undefined) => (
  has(permissions, 'MAIL_EXTERNAL_ACCOUNTS') || has(permissions, 'ADMIN_MAIL_POLICY')
);

/** Подключение и настройка внешних ящиков сотрудников — только администратор почты. */
export const canAdministerExternalMail = (permissions: readonly string[] | undefined) => (
  has(permissions, 'ADMIN_MAIL_POLICY')
);
