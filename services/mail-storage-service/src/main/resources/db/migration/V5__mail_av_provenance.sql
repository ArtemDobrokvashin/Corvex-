-- Исторические CLEAN не подтверждают проверку: Sent/APPEND выставляли CLEAN без сканирования.
ALTER TABLE message_metadata ADD COLUMN av_scan_verified BOOLEAN NOT NULL DEFAULT FALSE;
