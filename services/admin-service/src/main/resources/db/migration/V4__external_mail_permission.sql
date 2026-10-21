-- External Mail Connector: право подключать внешние ящики выдаётся тем же группам,
-- которым по умолчанию разрешена обычная почта. Администратор может снять чекбокс в RBAC.
INSERT INTO group_permissions (group_id, permission)
SELECT id, 'MAIL_EXTERNAL_ACCOUNTS'
FROM user_groups
WHERE code IN ('ADMIN', 'DIRECTOR', 'ACCOUNTANT', 'MANAGER', 'USER')
ON CONFLICT DO NOTHING;
