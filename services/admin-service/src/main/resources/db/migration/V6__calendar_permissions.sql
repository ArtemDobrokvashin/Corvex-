-- Календарь доступен всем стандартным группам: это личный инструмент, а не
-- административная функция. SUPER_ADMIN получает все права из кода
-- (EffectivePermissionService.allOf), поэтому отдельные строки ему не нужны.
-- Администратор может снять право в RBAC.
INSERT INTO group_permissions (group_id, permission)
SELECT id, 'CALENDAR_READ' FROM user_groups
WHERE code IN ('ADMIN', 'DIRECTOR', 'ACCOUNTANT', 'MANAGER', 'USER')
ON CONFLICT DO NOTHING;

INSERT INTO group_permissions (group_id, permission)
SELECT id, 'CALENDAR_WRITE' FROM user_groups
WHERE code IN ('ADMIN', 'DIRECTOR', 'ACCOUNTANT', 'MANAGER', 'USER')
ON CONFLICT DO NOTHING;
