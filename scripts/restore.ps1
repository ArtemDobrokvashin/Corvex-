<#
 Corvex: восстановление из резервной копии, созданной scripts/backup.ps1.

 ВНИМАНИЕ: скрипт ПЕРЕЗАПИСЫВАЕТ текущие данные (БД и файловые тома).
 Сначала отработайте восстановление на чистой тестовой VM (docs/10).
 Обязателен явный флаг -Force как защита от случайного запуска.

 Использование:
   powershell -ExecutionPolicy Bypass -File scripts\restore.ps1 `
     -BackupDir D:\CorvexBackup\2026-10-20_0300 -Force [-RestoreConfig]

 Порядок: остановка стека -> восстановление файловых томов -> старт одного
 PostgreSQL -> pg_restore каждой БД -> старт всего стека. Elasticsearch не
 восстанавливается: индекс перестраивается из mail_db/chat_db.
#>
param(
  [Parameter(Mandatory = $true)][string]$BackupDir,
  [switch]$Force,
  [switch]$RestoreConfig
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not $Force) {
  throw "Refusing to overwrite current data without -Force. Rehearse on a clean VM first (docs/10)."
}
if (-not (Test-Path $BackupDir)) { throw "Backup directory not found: $BackupDir" }
$PgUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "corvex" }

$Databases = "auth_db", "admin_db", "mail_db", "chat_db"
$Volumes = @(
  @{ Volume = "corvex_mail-data";      Archive = "mail-data.tar.gz" },
  @{ Volume = "corvex_attachment-data"; Archive = "attachments.tar.gz" },
  @{ Volume = "corvex_chat-file-data"; Archive = "chat-files.tar.gz" }
)
foreach ($Db in $Databases) {
  $Dump = Join-Path $BackupDir "$Db.dump"
  if (-not (Test-Path $Dump)) { throw "Missing database dump: $Dump" }
}
foreach ($V in $Volumes) {
  $Archive = Join-Path $BackupDir $V.Archive
  if (-not (Test-Path $Archive)) { throw "Missing volume archive: $Archive" }
}

function Run($File, $Arguments) {
  & $File @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Command failed: $File $Arguments (exit $LASTEXITCODE)" }
}

if ($RestoreConfig) {
  $Stamp = Get-Date -Format "yyyy-MM-dd_HHmm"
  Write-Host "Saving current config to .pre-restore-$Stamp and restoring config from backup..."
  New-Item -ItemType Directory -Force ".pre-restore-$Stamp" | Out-Null
  Copy-Item .env, docker-compose.yml -Destination ".pre-restore-$Stamp" -ErrorAction SilentlyContinue
  Copy-Item (Join-Path $BackupDir ".env"), (Join-Path $BackupDir "docker-compose.yml") -Destination .
  Copy-Item (Join-Path $BackupDir "certs") -Destination infra -Recurse -Force
  Copy-Item (Join-Path $BackupDir "secrets") -Destination infra -Recurse -Force
}

Write-Host "Stopping the stack (volumes are kept)..."
Run "docker" @("compose", "down")

foreach ($V in $Volumes) {
  Write-Host "Restoring volume $($V.Volume) from $($V.Archive)..."
  $Cmd = "find /data -mindepth 1 -delete && tar xzf /backup/$($V.Archive) -C /data"
  Run "docker" @("run", "--rm", "-v", "$($V.Volume):/data", "-v", "${BackupDir}:/backup:ro", "alpine", "sh", "-c", $Cmd)
}

Write-Host "Starting PostgreSQL only..."
Run "docker" @("compose", "up", "-d", "postgres")
$Ready = $false
for ($i = 0; $i -lt 60; $i++) {
  docker compose exec -T postgres pg_isready -U $PgUser | Out-Null
  if ($LASTEXITCODE -eq 0) { $Ready = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $Ready) { throw "PostgreSQL did not become ready in time" }

foreach ($Db in $Databases) {
  $Dump = Join-Path $BackupDir "$Db.dump"
  Write-Host "Restoring database $Db..."
  Run "docker" @("compose", "cp", $Dump, "postgres:/tmp/$Db.dump")
  Run "docker" @("compose", "exec", "-T", "postgres", "pg_restore", "-U", $PgUser,
                 "-d", $Db, "--clean", "--if-exists", "--no-owner", "/tmp/$Db.dump")
  docker compose exec -T postgres rm -f "/tmp/$Db.dump" | Out-Null
}

Write-Host "Starting the full stack..."
Run "docker" @("compose", "up", "-d")
Run "docker" @("compose", "ps")
Write-Host "Restore OK. Check service health and log in as the bootstrap admin before opening access."
