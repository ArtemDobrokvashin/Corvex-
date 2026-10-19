-- admin_db: группы и права (RBAC), аудит, серверные настройки.
CREATE TABLE user_groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64) NOT NULL UNIQUE,   -- SUPER_ADMIN, ADMIN, DIRECTOR, ACCOUNTANT, MANAGER, USER, + свои
    name        VARCHAR(256) NOT NULL,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE, -- системные группы нельзя удалить, права править можно
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE group_permissions (
    group_id   UUID NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    permission VARCHAR(64) NOT NULL,           -- коды из ru.corvex.admin.rbac.Permission
    PRIMARY KEY (group_id, permission)
);

-- user_id ссылается на auth_db.users логически (database-per-service: FK между БД запрещён,
-- целостность обеспечивает admin-service вызовом auth-service и компенсациями)
CREATE TABLE user_group_members (
    user_id  UUID NOT NULL,
    group_id UUID NOT NULL REFERENCES user_groups(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, group_id)
);

CREATE TABLE audit_log (
    id         BIGSERIAL PRIMARY KEY,
    actor_id   UUID, actor_name VARCHAR(128),
    action     VARCHAR(128) NOT NULL,          -- USER_CREATED, GROUP_PERMISSIONS_CHANGED, LOGIN_BLOCKED...
    entity     VARCHAR(128), entity_id VARCHAR(128),
    details    JSONB NOT NULL DEFAULT '{}',
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_created ON audit_log(created_at DESC);
CREATE INDEX idx_audit_actor ON audit_log(actor_id, created_at DESC);

CREATE TABLE server_settings (
    key        VARCHAR(128) PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_by UUID, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
