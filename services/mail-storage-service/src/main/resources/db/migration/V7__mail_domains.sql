-- Собственные почтовые домены Corvex: реестр, журнал операций и последние
-- результаты DNS-проверки. Наличие строки здесь НЕ означает, что домен уже
-- принимает почту: приём дополнительно требует записи домена в James и
-- фактически опубликованных MX/SPF. Эти признаки проверяются отдельно и
-- хранятся с отметкой времени.

CREATE TABLE mail_domains (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain             VARCHAR(253) NOT NULL,
    enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    -- NOT_REQUESTED -> REQUESTING -> CREATED | FAILED | UNCERTAIN.
    -- REQUESTING переживает перезапуск: исход неизвестен, пока GET в James не
    -- подтвердит наличие домена, поэтому слепой повтор PUT запрещён.
    james_state        VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUESTED',
    james_requested_at TIMESTAMPTZ,
    james_confirmed_at TIMESTAMPTZ,
    created_by         UUID NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mail_domains_domain_unique UNIQUE (domain),
    CONSTRAINT mail_domains_domain_lowercase CHECK (domain = lower(domain)),
    CONSTRAINT mail_domains_james_state CHECK
        (james_state IN ('NOT_REQUESTED', 'REQUESTING', 'CREATED', 'FAILED', 'UNCERTAIN'))
);

-- Журнал переживает удаление домена: domain_id обнуляется, имя сохраняется,
-- иначе история административных действий исчезала бы вместе с записью.
CREATE TABLE mail_domain_events (
    id         BIGSERIAL PRIMARY KEY,
    domain_id  UUID REFERENCES mail_domains(id) ON DELETE SET NULL,
    domain     VARCHAR(253) NOT NULL,
    actor_id   UUID NOT NULL,
    event      VARCHAR(32) NOT NULL,
    detail     VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX mail_domain_events_domain_idx ON mail_domain_events (domain_id, created_at DESC);

CREATE TABLE mail_domain_checks (
    domain_id   UUID NOT NULL REFERENCES mail_domains(id) ON DELETE CASCADE,
    check_name  VARCHAR(24) NOT NULL,
    state       VARCHAR(16) NOT NULL,
    detail      VARCHAR(512) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (domain_id, check_name),
    CONSTRAINT mail_domain_checks_name CHECK
        (check_name IN ('MX', 'SPF', 'DKIM', 'DMARC', 'AUTODISCOVER')),
    CONSTRAINT mail_domain_checks_state CHECK
        (state IN ('PASS', 'WARN', 'FAIL', 'UNKNOWN'))
);
