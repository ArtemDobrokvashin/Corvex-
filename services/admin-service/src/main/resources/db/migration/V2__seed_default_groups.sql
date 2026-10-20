-- Группы по умолчанию (ТЗ §6.1). Права — в точности как EffectivePermissionService.defaultPermissionsOf.
INSERT INTO user_groups (code, name, is_system) VALUES
 ('SUPER_ADMIN','Супер администратор',TRUE), ('ADMIN','Администратор',TRUE),
 ('DIRECTOR','Директор',TRUE), ('ACCOUNTANT','Бухгалтер',TRUE),
 ('MANAGER','Руководитель',TRUE), ('USER','Обычный пользователь',TRUE);

INSERT INTO group_permissions SELECT id,'MAIL_READ_OWN'    FROM user_groups WHERE code IN ('ADMIN','DIRECTOR','ACCOUNTANT','MANAGER','USER');
INSERT INTO group_permissions SELECT id,'MAIL_SEND_INTERNAL' FROM user_groups WHERE code IN ('ADMIN','DIRECTOR','ACCOUNTANT','MANAGER','USER');
INSERT INTO group_permissions SELECT id,'MAIL_SEND_EXTERNAL' FROM user_groups WHERE code IN ('ADMIN','DIRECTOR','ACCOUNTANT','MANAGER','USER');
INSERT INTO group_permissions SELECT id,'MAIL_MANAGE_FOLDERS' FROM user_groups WHERE code IN ('ADMIN','DIRECTOR','ACCOUNTANT','MANAGER','USER');
INSERT INTO group_permissions SELECT id,'CHAT_READ'  FROM user_groups WHERE code IN ('ADMIN','DIRECTOR','ACCOUNTANT','MANAGER','USER');
INSERT INTO group_permissions SELECT id,'CHAT_WRITE' FROM user_groups WHERE code IN ('ADMIN','DIRECTOR','ACCOUNTANT','MANAGER','USER');
INSERT INTO group_permissions SELECT id,'CHAT_CREATE_GROUP' FROM user_groups WHERE code IN ('ADMIN','DIRECTOR','ACCOUNTANT','MANAGER','USER');
INSERT INTO group_permissions SELECT id,'CHAT_SEND_FILES'   FROM user_groups WHERE code IN ('ADMIN','DIRECTOR','ACCOUNTANT','MANAGER','USER');
INSERT INTO group_permissions SELECT id,'MAIL_BULK_SEND'    FROM user_groups WHERE code='ACCOUNTANT';
INSERT INTO group_permissions SELECT id,'ANALYTICS_VIEW_COMPANY' FROM user_groups WHERE code IN ('DIRECTOR','ADMIN');
INSERT INTO group_permissions SELECT id,'ANALYTICS_VIEW_OWN_UNIT' FROM user_groups WHERE code='MANAGER';
INSERT INTO group_permissions SELECT id, p FROM user_groups, unnest(ARRAY[
  'CHAT_CREATE_CHANNEL','CHAT_MODERATE','ADMIN_USERS_MANAGE','ADMIN_GROUPS_MANAGE','ADMIN_QUOTAS_MANAGE',
  'ADMIN_SECURITY_POLICY','ADMIN_MAIL_POLICY','ADMIN_BACKUP','AUDIT_VIEW']) AS p WHERE code='ADMIN';
-- SUPER_ADMIN не нуждается в строках прав: код группы = безусловный полный доступ (страховка от самоблокировки).
