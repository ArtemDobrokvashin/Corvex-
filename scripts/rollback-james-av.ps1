<# Explicit rollback of James spool gate config. No mail, volumes or queues deleted. #>
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$DeploymentManifest, [switch]$ConfirmLaptop)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'manifest'
function Invoke-Docker([string[]]$Arguments) {
    # Native stderr is a terminating error in Windows PowerShell 5.1 when
    # $ErrorActionPreference is Stop; keep it non-terminating and discarded.
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& docker @Arguments 2>$null)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    return @{ Output = $output; Code = $code }
}
$changed = $false
try {
    $m = Get-Content -Raw -LiteralPath $DeploymentManifest | ConvertFrom-Json
    if ($m.network -notmatch '^corvex-james-av-[a-f0-9]{32}$' -or $m.jamesImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.composeBeforeSha256 -notmatch '^[a-f0-9]{64}$' -or $m.composeAfterSha256 -notmatch '^[a-f0-9]{64}$' -or
        $m.rollbackComposeSha256 -notmatch '^[a-f0-9]{64}$' -or $m.webAdminPinned -isnot [bool]) { throw 'Incomplete or invalid deployment manifest; inspect manually.' }
    if (-not (Test-Path -LiteralPath $m.composeBackup -PathType Leaf) -or
        (Get-FileHash -LiteralPath $m.composeBackup -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.composeBeforeSha256 -or
        -not (Test-Path -LiteralPath $m.rollbackCompose -PathType Leaf) -or
        (Get-FileHash -LiteralPath $m.rollbackCompose -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.rollbackComposeSha256) { throw 'Compose backup unavailable.' }
    Push-Location -LiteralPath $m.composeDir
    try {
        $stage = 'preflight'
        $composePath = Join-Path (Get-Location).Path 'docker-compose.yml'
        $hash = (Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -cne $m.composeAfterSha256) { throw 'Compose changed independently; preserve it.' }
        if ($m.webAdminPinned) {
            if ($m.webAdminRelative -cne './infra/secrets/james-provision/webadmin.properties' -or $m.webAdminSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Invalid WebAdmin record.' }
            $webFile = Join-Path (Get-Location).Path $m.webAdminRelative
            if (-not (Test-Path -LiteralPath $webFile -PathType Leaf) -or (Get-FileHash -LiteralPath $webFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.webAdminSha256) { throw 'WebAdmin source changed; preserve it.' }
        }
        $found = Invoke-Docker @('compose', '--profile', 'james-transition', 'ps', '-a', '-q', 'james')
        if ($found.Code -ne 0 -or $found.Output.Count -gt 1) { throw 'Unexpected James containers.' }
        if ($found.Output.Count -eq 1) {
            $image = Invoke-Docker @('inspect', '--format', '{{.Image}}', $found.Output[0])
            if ($image.Code -ne 0 -or $image.Output.Count -ne 1 -or $image.Output[0] -cne $m.jamesImageId) { throw 'James image changed independently.' }
        }
        $stage = 'restore-compose'
        $changed = $true
        # Restore pre-gate configuration while retaining the previously running
        # WebAdmin source when the old Compose file had drifted from runtime.
        [IO.File]::WriteAllBytes($composePath, [IO.File]::ReadAllBytes($m.rollbackCompose))
        if ((Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.rollbackComposeSha256) { throw 'Restored Compose hash mismatch.' }
        $stage = 'recreate-james'
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & docker compose --profile james-transition up -d --no-deps --no-build --pull never --force-recreate james; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
        if ($code -ne 0) { throw 'James recreation failed.' }
        $running = Invoke-Docker @('compose', '--profile', 'james-transition', 'ps', '--status', 'running', '-q', 'james')
        if ($running.Code -ne 0 -or $running.Output.Count -ne 1) { throw 'Restored James not running.' }
        $ids = $running.Output
        $raw = Invoke-Docker @('inspect', '--format', '{{json .}}', $ids[0])
        if ($raw.Code -ne 0 -or $raw.Output.Count -ne 1) { throw 'Cannot inspect restored James.' }
        $info = $raw.Output[0] | ConvertFrom-Json
        if ($info.Image -cne $m.jamesImageId) { throw 'Unexpected restored James image.' }
        foreach ($dst in @('/root/conf','/root/extensions-jars','/root/var')) {
            $property = $m.originalVolumes.PSObject.Properties[$dst]
            if ($null -eq $property) { throw 'Volume record missing.' }
            $found = @($info.Mounts | Where-Object { $_.Destination -ceq $dst -and $_.Type -ceq 'volume' -and $_.Name -ceq $property.Value })
            if ($found.Count -ne 1) { throw "Original volume not attached: $dst" }
        }
        if ($m.webAdminPinned) {
            $webHash = Invoke-Docker @('exec', $ids[0], 'sha256sum', '/root/conf/webadmin.properties')
            if ($webHash.Code -ne 0 -or $webHash.Output.Count -ne 1 -or $webHash.Output[0] -notmatch '^([a-f0-9]{64})\s' -or $Matches[1] -cne $m.webAdminSha256) { throw 'Restored WebAdmin config mismatch.' }
        }
        Write-Host 'Pre-gate Compose and James image restored; original James volumes attached.'
        Write-Host 'Network and artifacts were deliberately retained; do not remove while in use.'
        Write-Host 'Messages processed or quarantined are not undone; no queues or data were replayed.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR: rollback failed at $stage; configuration may have changed: $changed. Do not retry blindly.")
    throw
}
