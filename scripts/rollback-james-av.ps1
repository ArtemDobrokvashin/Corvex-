<# Explicit rollback of James spool gate config. No mail, volumes or queues deleted. #>
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$DeploymentManifest, [switch]$ConfirmLaptop)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'manifest'
$changed = $false
try {
    $m = Get-Content -Raw -LiteralPath $DeploymentManifest | ConvertFrom-Json
    if ($m.network -notmatch '^corvex-james-av-[a-f0-9]{32}$' -or $m.jamesImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.composeBeforeSha256 -notmatch '^[a-f0-9]{64}$' -or $m.composeAfterSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Incomplete or invalid deployment manifest; inspect manually.' }
    if (-not (Test-Path -LiteralPath $m.composeBackup -PathType Leaf) -or
        (Get-FileHash -LiteralPath $m.composeBackup -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.composeBeforeSha256) { throw 'Compose backup unavailable.' }
    Push-Location -LiteralPath $m.composeDir
    try {
        $stage = 'preflight'
        $composePath = Join-Path (Get-Location).Path 'docker-compose.yml'
        $hash = (Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -cne $m.composeAfterSha256) { throw 'Compose changed independently; preserve it.' }
        $ids = @(& docker compose --profile james-transition ps -a -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -gt 1) { throw 'Unexpected James containers.' }
        if ($ids.Count -eq 1) {
            $image = @(& docker inspect --format '{{.Image}}' $ids[0] 2>$null)
            if ($LASTEXITCODE -ne 0 -or $image.Count -ne 1 -or $image[0] -cne $m.jamesImageId) { throw 'James image changed independently.' }
        }
        $stage = 'restore-compose'
        $changed = $true
        [IO.File]::WriteAllBytes($composePath, [IO.File]::ReadAllBytes($m.composeBackup))
        if ((Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.composeBeforeSha256) { throw 'Restored Compose hash mismatch.' }
        $stage = 'recreate-james'
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & docker compose --profile james-transition up -d --no-deps --no-build --pull never --force-recreate james; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
        if ($code -ne 0) { throw 'James recreation failed.' }
        $ids = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1) { throw 'Restored James not running.' }
        $raw = @(& docker inspect --format '{{json .}}' $ids[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) { throw 'Cannot inspect restored James.' }
        $info = $raw[0] | ConvertFrom-Json
        if ($info.Image -cne $m.jamesImageId) { throw 'Unexpected restored James image.' }
        foreach ($dst in @('/root/conf','/root/extensions-jars','/root/var')) {
            $property = $m.originalVolumes.PSObject.Properties[$dst]
            if ($null -eq $property) { throw 'Volume record missing.' }
            $found = @($info.Mounts | Where-Object { $_.Destination -ceq $dst -and $_.Type -ceq 'volume' -and $_.Name -ceq $property.Value })
            if ($found.Count -ne 1) { throw "Original volume not attached: $dst" }
        }
        Write-Host 'Original Compose and James image restored; original James volumes attached.'
        Write-Host 'Network and artifacts were deliberately retained; do not remove while in use.'
        Write-Host 'Messages processed or quarantined are not undone; no queues or data were replayed.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR: rollback failed at $stage; configuration may have changed: $changed. Do not retry blindly.")
    throw
}
