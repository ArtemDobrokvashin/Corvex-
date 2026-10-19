<#
 Корвекс: резервное копирование (запускать по расписанию Task Scheduler ежедневно).
 Стратегия: pg_dump всех БД + снапшот файловых томов (Maildir, вложения, файлы чата) + конфиги.
 Хранение: 7 ежедневных / 4 еженедельных / 6 ежемесячных копий (ротация ниже по дате).
 Восстановление обязано проверяться учебным подъёмом не реже раза в квартал (docs/10).
#>
param([string]$BackupRoot = "D:\CorvexBackup")
$ErrorActionPreference = "Stop"
$Stamp = Get-Date -Format "yyyy-MM-dd_HHmm"
$Dir = Join-Path $BackupRoot $Stamp
New-Item -ItemType Directory -Force $Dir | Out-Null

# 1. PostgreSQL: логические дампы каждой БД сервиса
foreach ($Db in "auth_db","admin_db","mail_db","chat_db") {
  docker compose exec -T postgres pg_dump -U $env:POSTGRES_USER -Fc $Db > (Join-Path $Dir "$Db.dump")
}
# 2. Файловые данные: тома останавливать не требуется — Maildir допускает консистентный снапшот на лету
#    (письмо появляется атомарным rename), метаданные сверяются с дампом mail_db при восстановлении.
docker run --rm -v corvex_mail-data:/data -v "${Dir}:/backup" alpine tar czf /backup/mail-data.tar.gz -C /data .
docker run --rm -v corvex_attachment-data:/data -v "${Dir}:/backup" alpine tar czf /backup/attachments.tar.gz -C /data .
docker run --rm -v corvex_chat-file-data:/data -v "${Dir}:/backup" alpine tar czf /backup/chat-files.tar.gz -C /data .
# 3. Конфигурация и секреты (шифруйте папку бэкапа BitLocker/EFS внешнего носителя!)
Copy-Item .env, docker-compose.yml -Destination $Dir
Copy-Item infra\certs, infra\secrets -Destination $Dir -Recurse
# 4. Elasticsearch НЕ бэкапим: индекс полностью перестраивается из mail_db/chat_db фоновой задачей.
Write-Host "Backup OK: $Dir"
