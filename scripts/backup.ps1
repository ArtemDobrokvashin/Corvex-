<#
 Corvex backup for Task Scheduler.
 Creates logical PostgreSQL dumps, read-only snapshots of file volumes, config copies
 and a SHA-256 manifest. Old copies are never deleted: configure retention separately.
 Restore must be rehearsed regularly; scripts/restore.ps1 supports an isolated data drill.
#>
param(
  [string]$BackupRoot = "D:\CorvexBackup",
  [string]$ProjectName = "corvex"
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($ProjectName -notmatch "^[a-z0-9][a-z0-9_-]*$") {
  throw "Invalid Compose project name: $ProjectName"
}

function Run($File, $Arguments) {
  & $File @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Command failed: $File $Arguments (exit $LASTEXITCODE)" }
}

# Read the effective user from the running container; host PowerShell does not import .env.
$PgUser = & docker compose -p $ProjectName exec -T postgres printenv POSTGRES_USER
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($PgUser)) {
  throw "Cannot read POSTGRES_USER from the postgres container"
}
$PgUser = $PgUser.Trim()

$Stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$Dir = Join-Path $BackupRoot $Stamp
New-Item -ItemType Directory -Force $Dir | Out-Null
$Dir = (Resolve-Path $Dir).ProviderPath
$Databases = "auth_db", "admin_db", "mail_db", "chat_db"

# Never redirect pg_dump -Fc through PowerShell: Windows PowerShell treats native
# redirection as text and can corrupt the binary custom-format dump. cp is binary-safe.
foreach ($Db in $Databases) {
  $ContainerDump = "/tmp/corvex-backup-$Db.dump"
  $HostDump = Join-Path $Dir "$Db.dump"
  try {
    Run "docker" @("compose", "-p", $ProjectName, "exec", "-T", "postgres",
      "pg_dump", "-U", $PgUser, "-Fc", "-f", $ContainerDump, $Db)
    Run "docker" @("compose", "-p", $ProjectName, "cp", "postgres:$ContainerDump", $HostDump)
  } finally {
    & docker compose -p $ProjectName exec -T postgres rm -f $ContainerDump | Out-Null
  }
  if (-not (Test-Path $HostDump) -or (Get-Item $HostDump).Length -le 0) {
    throw "Database dump is missing or empty: $HostDump"
  }
}

# Database records are dumped before file snapshots. Source volumes are read-only.
$Volumes = @(
  @{ Volume = "${ProjectName}_mail-data"; Archive = "mail-data.tar.gz" },
  @{ Volume = "${ProjectName}_attachment-data"; Archive = "attachments.tar.gz" },
  @{ Volume = "${ProjectName}_chat-file-data"; Archive = "chat-files.tar.gz" }
)
foreach ($V in $Volumes) {
  $Archive = Join-Path $Dir $V.Archive
  Run "docker" @("run", "--rm", "-v", "$($V.Volume):/data:ro", "-v", "${Dir}:/backup",
    "alpine", "tar", "czf", "/backup/$($V.Archive)", "-C", "/data", ".")
  if (-not (Test-Path $Archive) -or (Get-Item $Archive).Length -le 0) {
    throw "Volume archive is missing or empty: $Archive"
  }
}

# Contains secrets: store the backup on encrypted external media with restricted ACLs.
Copy-Item .env, docker-compose.yml -Destination $Dir
Copy-Item infra\certs, infra\secrets -Destination $Dir -Recurse
# Elasticsearch is intentionally excluded: its index is rebuildable from the databases.

$GitSha = & git rev-parse HEAD
if ($LASTEXITCODE -ne 0) { $GitSha = "unknown" } else { $GitSha = $GitSha.Trim() }
$Files = @(
  Get-ChildItem -File -Recurse -Path $Dir | Sort-Object FullName | ForEach-Object {
    $Relative = $_.FullName.Substring($Dir.Length + 1).Replace("\", "/")
    @{
      path = $Relative
      bytes = $_.Length
      sha256 = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant()
    }
  }
)
@{
  version = 1
  created_at = (Get-Date).ToUniversalTime().ToString("o")
  git_sha = $GitSha
  source_project = $ProjectName
  files = $Files
} | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $Dir "manifest.json")

Write-Host "Backup OK: $Dir"
