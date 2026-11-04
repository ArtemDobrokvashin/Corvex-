<#
 Verifies an isolated restore from scripts/restore.ps1 -DataOnly against its backup.
 The source corvex project is forbidden. No application containers are started/stopped.

 Example:
   powershell -ExecutionPolicy Bypass -File scripts\restore_verify.ps1 `
     -BackupDir C:\CorvexBackup\2026-11-03_161627 `
     -ProjectName corvex-restore-drill

 File volumes are compared read-only with their archives. Each database is compared
 with a fresh reference restore using the same pg_dump for both sides. A temporary
 reference database is dropped only after its comparison passes; on failure it remains
 available for diagnosis. Restored PostgreSQL and volumes are never removed.
#>
param(
  [Parameter(Mandatory = $true)][string]$BackupDir,
  [string]$ProjectName = "corvex-restore-drill"
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($ProjectName -eq "corvex") {
  throw "Refusing to verify the source corvex project; use an isolated DataOnly restore"
}
if ($ProjectName -notmatch "^[a-z0-9][a-z0-9_-]*$") {
  throw "Invalid Compose project name: $ProjectName"
}
if (-not (Test-Path $BackupDir)) { throw "Backup directory not found: $BackupDir" }
$BackupDir = (Resolve-Path $BackupDir).ProviderPath

$Databases = "auth_db", "admin_db", "mail_db", "chat_db"
$Volumes = @(
  @{ Volume = "${ProjectName}_mail-data"; Archive = "mail-data.tar.gz"; Marker = "mail_data_match" },
  @{ Volume = "${ProjectName}_attachment-data"; Archive = "attachments.tar.gz"; Marker = "attachment_data_match" },
  @{ Volume = "${ProjectName}_chat-file-data"; Archive = "chat-files.tar.gz"; Marker = "chat_file_data_match" }
)
if (Test-Path (Join-Path $BackupDir "alert-relay-data.tar.gz")) {
  $Volumes += @{ Volume = "${ProjectName}_alert-relay-data"; Archive = "alert-relay-data.tar.gz"; Marker = "alert_relay_data_match" }
}
foreach ($Db in $Databases) {
  if (-not (Test-Path (Join-Path $BackupDir "$Db.dump"))) { throw "Missing database dump: $Db.dump" }
}
foreach ($V in $Volumes) {
  if (-not (Test-Path (Join-Path $BackupDir $V.Archive))) { throw "Missing volume archive: $($V.Archive)" }
}

function Run($File, $Arguments) {
  & $File @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Command failed: $File $Arguments (exit $LASTEXITCODE)" }
}

# Prove which project owns the restored PostgreSQL before creating any reference DB.
$PostgresId = & docker compose -p $ProjectName ps -q postgres
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($PostgresId)) {
  throw "Cannot locate the isolated postgres container"
}
$RawLabels = & docker inspect --format '{{json .Config.Labels}}' $PostgresId.Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($RawLabels)) {
  throw "Cannot read labels from the isolated postgres container"
}
$Labels = $RawLabels | ConvertFrom-Json
$ActualProject = [string]$Labels.'com.docker.compose.project'
if ([string]::IsNullOrWhiteSpace($ActualProject) -or $ActualProject.Trim() -ne $ProjectName) {
  throw "Compose project mismatch: expected $ProjectName, got $ActualProject"
}

# Compare restored files with the original archives, not with mutable source volumes.
foreach ($V in $Volumes) {
  Write-Host "Verifying $($V.Volume) against $($V.Archive)..."
  $Cmd = "mkdir /expected && tar xzf /backup/$($V.Archive) -C /expected && diff -qr /expected /restored && echo $($V.Marker)"
  Run "docker" @("run", "--rm", "-v", "$($V.Volume):/restored:ro", "-v", "${BackupDir}:/backup:ro",
    "alpine", "sh", "-c", $Cmd)
}

# pg_restore and pg_dump can render equivalent DDL differently. Restore the backup to
# a fresh reference DB, then use the same pg_dump on both databases for a canonical diff.
$Suffix = Get-Date -Format "yyyyMMddHHmmss"
$ShellUser = '${POSTGRES_USER:-corvex}'
foreach ($Db in $Databases) {
  $ReferenceDb = "verify_${Db}_$Suffix"
  Write-Host "Verifying $Db using temporary reference $ReferenceDb..."
  $Cmd = "export PGPASSWORD=`$POSTGRES_PASSWORD; " +
    "createdb -h postgres -U $ShellUser $ReferenceDb && " +
    "pg_restore -h postgres -U $ShellUser -d $ReferenceDb -Fc --no-owner --no-privileges /backup/$Db.dump && " +
    "pg_dump -h postgres -U $ShellUser -Fp --no-owner --no-privileges -f /tmp/actual.sql $Db && " +
    "pg_dump -h postgres -U $ShellUser -Fp --no-owner --no-privileges -f /tmp/expected.sql $ReferenceDb && " +
    "sed -e /^--/d -e /^$/d -e /^SET/d -e /^SELECT.pg_catalog.set_config/d -e /^.restrict/d -e /^.unrestrict/d /tmp/expected.sql > /tmp/expected.norm && " +
    "sed -e /^--/d -e /^$/d -e /^SET/d -e /^SELECT.pg_catalog.set_config/d -e /^.restrict/d -e /^.unrestrict/d /tmp/actual.sql > /tmp/actual.norm && " +
    "diff -q /tmp/expected.norm /tmp/actual.norm && " +
    "dropdb -h postgres -U $ShellUser $ReferenceDb && echo ${Db}_logical_match"
  Run "docker" @("run", "--rm", "--network", "${ProjectName}_data", "--env-file", ".env",
    "-v", "${BackupDir}:/backup:ro", "postgres:16-alpine", "sh", "-c", $Cmd)
}

Write-Host "Restore verification OK: $($Databases.Count) databases and $($Volumes.Count) file volumes match the backup."
Write-Host "Application services were not started. Isolated PostgreSQL and volumes were kept."
