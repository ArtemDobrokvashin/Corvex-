-- Этап 1: ip_address аудита INET -> VARCHAR (一致 с auth_db, проще запись из Java).
ALTER TABLE audit_log
    ALTER COLUMN ip_address TYPE VARCHAR(64) USING (CASE WHEN ip_address IS NULL THEN NULL ELSE host(ip_address) END);
