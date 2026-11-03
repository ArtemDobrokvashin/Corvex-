<#
 Corvex restore from scripts/backup.ps1.

 Full restore (overwrites the current corvex data):
   powershell -ExecutionPolicy Bypass -File scripts\restore.ps1 `
     -BackupDir D:\CorvexBackup\2026-10-20_030000 -Force [-RestoreConfig]

 Isolated data-only drill on the same PC (source corvex remains running and unchanged):
   powershell -ExecutionPolicy Bypass -File scripts\restore.ps1 `
     -BackupDir D:\CorvexBackup\2026-10-20_030000 `
     -ProjectName corvex-restore-drill -DataOnly -Force

 DataOnly restores only new volumes and PostgreSQL in the isolated Compose project.
 It never starts application containers, so host ports do not conflict with the source.
#>
param(
  [Parameter(Mandatory = $true)][string]$BackupDir,
  [string]$ProjectName = "corvex",
  [switch]$Force,
  [switch]$RestoreConfig,
  [switch]$DataOnly
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if (-not $Force) {
  throw "Refusing to restore without -Force"
}
if ($ProjectName -notmatch "^[a-z0-9][a-z0-9_-]*$") {
  throw "Invalid Compose project name: $ProjectName"
}
if ($DataOnly -and $ProjectName -eq "corvex") {
  throw "DataOnly requires an isolated -ProjectName, not the source corvex project"
}
if (-not $DataOnly -and $ProjectName -ne "corvex") {
  throw "An isolated project supports only -DataOnly; refusing to publish application ports"
}
if (($DataOnly -or $ProjectName -ne "corvex") -and $RestoreConfig) {
  throw "-RestoreConfig is allowed only for a full restore of the source corvex project"
}
if (-not (Test-Path $BackupDir)) { throw "Backup directory not found: $BackupDir" }
$BackupDir = (Resolve-Path $BackupDir).ProviderPath

$Databases = "auth_db", "admin_db", "mail_db", "chat_db"
$Volumes = @(
  @{ Volume = "${ProjectName}_mail-data"; Archive = "mail-data.tar.gz" },
  @{ Volume = "${ProjectName}_attachment-data"; Archive = "attachments.tar.gz" },
  @{ Volume = "${ProjectName}_chat-file-data"; Archive = "chat-files.tar.gz" }
)
foreach ($Db in $Databases) {
  $Dump = Join-Path $BackupDir "$Db.dump"
  if (-not (Test-Path $Dump)) { throw "Missing database dump: $Dump" }
}
foreach ($V in $Volumes) {
  $Archive = Join-Path $BackupDir $V.Archive
  if (-not (Test-Path $Archive)) { throw "Missing volume archive: $Archive" }
}

# New backups carry hashes for every file, including config/certs/secrets.
# Legacy backups without a manifest remain restorable after the required-file checks.
$ManifestPath = Join-Path $BackupDir "manifest.json"
if (Test-Path $ManifestPath) {
  $Manifest = Get-Content -Raw -Encoding UTF8 $ManifestPath | ConvertFrom-Json
  foreach ($File in @($Manifest.files)) {
    $Relative = [string]$File.path
    if ([IO.Path]::IsPathRooted($Relative) -or $Relative -match '(^|[\\/])\.\.([\\/]|$)') {
      throw "Unsafe path in backup manifest: $Relative"
    }
    $Target = Join-Path $BackupDir $Relative
    if (-not (Test-Path $Target)) { throw "Manifest file is missing: $Target" }
    $Item = Get-Item $Target
    if ($Item.Length -ne [int64]$File.bytes) { throw "Backup size mismatch: $Target" }
    $ActualHash = (Get-FileHash -Algorithm SHA256 $Target).Hash.ToLowerInvariant()
    if ($ActualHash -ne ([string]$File.sha256).ToLowerInvariant()) {
      throw "Backup SHA-256 mismatch: $Target"
    }
  }
  Write-Host "Backup manifest and SHA-256 hashes verified"
} else {
  Write-Host "No manifest found: legacy backup, required files checked only"
}

function Run($File, $Arguments) {
  & $File @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Command failed: $File $Arguments (exit $LASTEXITCODE)" }
}

if ($RestoreConfig) {
  $Stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
  Write-Host "Saving current config to .pre-restore-$Stamp and restoring config from backup..."
  New-Item -ItemType Directory -Force ".pre-restore-$Stamp" | Out-Null
  Copy-Item .env, docker-compose.yml -Destination ".pre-restore-$Stamp" -ErrorAction SilentlyContinue
  Copy-Item (Join-Path $BackupDir ".env"), (Join-Path $BackupDir "docker-compose.yml") -Destination .
  Copy-Item (Join-Path $BackupDir "certs") -Destination infra -Recurse -Force
  Copy-Item (Join-Path $BackupDir "secrets") -Destination infra -Recurse -Force
}

if ($DataOnly) {
  # Start only PostgreSQL first and prove the Compose project label before any restore.
  # A wrong project is rejected before file deletion or pg_restore can touch source data.
  Write-Host "Starting isolated PostgreSQL in project $ProjectName..."
  Run "docker" @("compose", "-p", $ProjectName, "up", "-d", "postgres")
  $PostgresId = & docker compose -p $ProjectName ps -q postgres
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($PostgresId)) {
    throw "Cannot locate the isolated postgres container"
  }
  # JSON avoids nested quotes in Go templates: Windows PowerShell may strip them.
  $RawLabels = & docker inspect --format '{{json .Config.Labels}}' $PostgresId.Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($RawLabels)) {
    throw "Cannot read labels from the isolated postgres container"
  }
  $Labels = $RawLabels | ConvertFrom-Json
  $ActualProject = [string]$Labels.'com.docker.compose.project'
  if ([string]::IsNullOrWhiteSpace($ActualProject) -or $ActualProject.Trim() -ne $ProjectName) {
    throw "Compose project mismatch: expected $ProjectName, got $ActualProject"
  }
} else {
  Write-Host "Stopping only Compose project $ProjectName (volumes are kept)..."
  Run "docker" @("compose", "-p", $ProjectName, "--profile", "monitoring", "down")
}

foreach ($V in $Volumes) {
  Write-Host "Restoring volume $($V.Volume) from $($V.Archive)..."
  $Cmd = "find /data -mindepth 1 -delete && tar xzf /backup/$($V.Archive) -C /data"
  Run "docker" @("run", "--rm", "-v", "$($V.Volume):/data", "-v", "${BackupDir}:/backup:ro",
    "alpine", "sh", "-c", $Cmd)
}

if (-not $DataOnly) {
  Write-Host "Starting PostgreSQL only in project $ProjectName..."
  Run "docker" @("compose", "-p", $ProjectName, "up", "-d", "postgres")
}
$PgUser = & docker compose -p $ProjectName exec -T postgres printenv POSTGRES_USER
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($PgUser)) {
  throw "Cannot read POSTGRES_USER from the restored postgres container"
}
$PgUser = $PgUser.Trim()
$Ready = $false
for ($i = 0; $i -lt 60; $i++) {
  & docker compose -p $ProjectName exec -T postgres pg_isready -U $PgUser | Out-Null
  if ($LASTEXITCODE -eq 0) { $Ready = $true; break }
  Start-Sleep -Seconds 2
}
if (-not $Ready) { throw "PostgreSQL did not become ready in time" }

foreach ($Db in $Databases) {
  $Dump = Join-Path $BackupDir "$Db.dump"
  Write-Host "Restoring database $Db..."
  Run "docker" @("compose", "-p", $ProjectName, "cp", $Dump, "postgres:/tmp/$Db.dump")
  Run "docker" @("compose", "-p", $ProjectName, "exec", "-T", "postgres", "pg_restore",
    "-U", $PgUser, "-d", $Db, "--clean", "--if-exists", "--no-owner", "/tmp/$Db.dump")
  Run "docker" @("compose", "-p", $ProjectName, "exec", "-T", "postgres", "rm", "-f", "/tmp/$Db.dump")
}

if ($DataOnly) {
  Run "docker" @("compose", "-p", $ProjectName, "ps")
  Write-Host "Isolated data restore OK. Application services were not started."
  Write-Host "Source corvex containers and volumes were not changed. Project: $ProjectName"
  return
}

Write-Host "Starting the full stack..."
Run "docker" @("compose", "-p", $ProjectName, "up", "-d")
Run "docker" @("compose", "-p", $ProjectName, "ps")
Write-Host "Restore OK. Check service health and log in as the bootstrap admin before opening access."
