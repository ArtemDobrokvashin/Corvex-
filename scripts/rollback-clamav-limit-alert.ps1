<# Restore the pre-change ClamAV Compose configuration and container.

   Reads the manifest written by enable-clamav-limit-alert.ps1, restores the
   exact Compose backup, recreates only clamav, re-creates its James AV network
   attachments with the 'clamav' alias and verifies health and configuration.
   Nothing is deleted and no step is retried automatically. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DeploymentManifest,
    [switch]$ConfirmLaptop
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'manifest'
$changed = $false
function Invoke-Docker([string[]]$Arguments) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& docker @Arguments 2>$null)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    return @{ Output = $output; Code = $code }
}
function Get-Networks([string]$Id) {
    $result = Invoke-Docker @('inspect', '--format', '{{json .NetworkSettings.Networks}}', $Id)
    if ($result.Code -ne 0 -or $result.Output.Count -ne 1) { throw 'Cannot inspect container networks.' }
    return ($result.Output[0] | ConvertFrom-Json)
}
function Wait-Healthy([string]$Id) {
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        Start-Sleep -Seconds 5
        $result = Invoke-Docker @('inspect', '--format', '{{.State.Status}} {{.State.Health.Status}}', $Id)
        if ($result.Code -ne 0 -or $result.Output.Count -ne 1) { throw 'Cannot inspect ClamAV state.' }
        if ($result.Output[0] -ceq 'running healthy') { return $true }
        if ($result.Output[0] -match '^(exited|dead|removing)') { throw "ClamAV stopped: $($result.Output[0])" }
    }
    throw 'ClamAV did not become healthy within five minutes.'
}
function Connect-AvNetworks([string]$Id, [string[]]$Networks) {
    foreach ($network in $Networks) {
        $current = Get-Networks $Id
        if ($null -ne $current.PSObject.Properties[$network]) {
            if (@($current.PSObject.Properties[$network].Value.Aliases) -cnotcontains 'clamav') {
                throw "Network $network is connected without the clamav alias."
            }
            continue
        }
        $connect = Invoke-Docker @('network', 'connect', '--alias', 'clamav', $network, $Id)
        if ($connect.Code -ne 0) { throw "Cannot reattach ClamAV to $network." }
    }
    $final = Get-Networks $Id
    foreach ($network in $Networks) {
        if ($null -eq $final.PSObject.Properties[$network]) { throw "ClamAV is not attached to $network." }
        if (@($final.PSObject.Properties[$network].Value.Aliases) -cnotcontains 'clamav') {
            throw "ClamAV alias missing on $network."
        }
    }
}
function Assert-ConfigReport([string]$Dir, [string]$Expected) {
    $out = Join-Path ([IO.Path]::GetTempPath()) ('corvex-clamd-report-' + [guid]::NewGuid().ToString('N') + '.txt')
    $err = Join-Path ([IO.Path]::GetTempPath()) ('corvex-clamd-report-' + [guid]::NewGuid().ToString('N') + '.err')
    try {
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $childArgs = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
                ('"' + (Join-Path $PSScriptRoot 'james-clamav-config-report.ps1') + '"'), '-ComposeDir', ('"' + $Dir + '"'))
            $proc = Start-Process -FilePath 'powershell.exe' -Wait -PassThru -NoNewWindow `
                -ArgumentList $childArgs -RedirectStandardOutput $out -RedirectStandardError $err
        } finally { $ErrorActionPreference = $saved }
        if ($proc.ExitCode -ne 0) { throw 'Configuration report failed.' }
        $text = [IO.File]::ReadAllText($out)
        if ($text -notmatch ('(?m)^' + [regex]::Escape($Expected) + '\r?$')) {
            throw "ClamAV configuration does not show '$Expected'."
        }
        return $text
    } finally {
        if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Force -ErrorAction SilentlyContinue }
        if (Test-Path -LiteralPath $err) { Remove-Item -LiteralPath $err -Force -ErrorAction SilentlyContinue }
    }
}
try {
    foreach ($name in @('docker', 'powershell.exe')) {
        if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "Required command missing: $name" }
    }
    $m = Get-Content -Raw -LiteralPath $DeploymentManifest | ConvertFrom-Json
    if ($m.applied -isnot [bool] -or $m.composeDir -isnot [string] -or
        $m.composeBeforeSha256 -notmatch '^[a-f0-9]{64}$' -or
        -not (Test-Path -LiteralPath $m.composeDir -PathType Container)) {
        throw 'Invalid deployment manifest; inspect manually.'
    }
    if (-not $m.applied) { Write-Host 'Nothing to roll back: the manifest is not marked as applied.'; return }
    if (-not (Test-Path -LiteralPath $m.composeBackup -PathType Leaf)) { throw 'Compose backup is missing; preserve the current file.' }
    if ((Get-FileHash -LiteralPath $m.composeBackup -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.composeBeforeSha256) {
        throw 'Compose backup hash differs; preserve it and inspect.'
    }
    $avNetworks = @($m.avNetworks)
    if ($avNetworks.Count -lt 1) { throw 'No James AV networks recorded.' }
    foreach ($network in $avNetworks) {
        if ($network -notmatch '^corvex-james-av-[a-f0-9]{32}$') { throw 'Unexpected network record.' }
    }
    Push-Location -LiteralPath $m.composeDir
    try {
        $composePath = Join-Path (Get-Location).Path 'docker-compose.yml'
        $stage = 'preflight'
        $raw = @([IO.File]::ReadAllBytes($composePath))
        $head = @($raw)
        $hasBom = $head.Length -ge 3 -and $head[0] -eq 239 -and $head[1] -eq 187 -and $head[2] -eq 191
        $text = [IO.File]::ReadAllText($composePath, (New-Object System.Text.UTF8Encoding($hasBom, $true)))
        $currentSha = (Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($currentSha -ceq $m.composeBeforeSha256) {
            Write-Host 'Compose already matches the backup; no container change is required.'
            return
        }
        if ($text -notmatch '(?m)^      CLAMD_CONF_AlertExceedsMax: "yes"\r?$') {
            throw 'Compose was changed independently; preserve it and inspect.'
        }
        $ids = (Invoke-Docker @('compose', 'ps', '--status', 'running', '-q', 'clamav')).Output
        if (@($ids).Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Expected one running ClamAV container.' }
        $stage = 'restore-compose'
        $changed = $true
        [IO.File]::WriteAllBytes($composePath, [IO.File]::ReadAllBytes($m.composeBackup))
        if ((Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $m.composeBeforeSha256) {
            throw 'Restored Compose hash differs.'
        }
        $stage = 'recreate'
        $up = Invoke-Docker @('compose', 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', 'clamav')
        if ($up.Code -ne 0) { throw 'ClamAV recreation failed.' }
        $newIds = (Invoke-Docker @('compose', 'ps', '--status', 'running', '-q', 'clamav')).Output
        if (@($newIds).Count -ne 1 -or $newIds[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Restored ClamAV container not found.' }
        $newId = $newIds[0]
        $stage = 'health'
        $null = Wait-Healthy $newId
        $stage = 'networks-reattach'
        Connect-AvNetworks $newId $avNetworks
        $stage = 'verify-config'
        $report = Assert-ConfigReport $m.composeDir 'AlertExceedsMax: not explicitly set'
        $stage = 'verify-hash'
        $newInfoRaw = Invoke-Docker @('inspect', '--format', '{{json .}}', $newId)
        if ($newInfoRaw.Code -ne 0 -or $newInfoRaw.Output.Count -ne 1) { throw 'Cannot inspect the restored ClamAV.' }
        $newInfo = $newInfoRaw.Output[0] | ConvertFrom-Json
        $expectedHash = (Invoke-Docker @('compose', 'config', '--hash', 'clamav')).Output
        if (@($expectedHash).Count -ne 1 -or $expectedHash[0] -notmatch '^clamav\s+([a-f0-9]{64})$') { throw 'Compose hash unavailable.' }
        $serviceHash = $Matches[1]
        $newLabel = $newInfo.Config.Labels.PSObject.Properties['com.docker.compose.config-hash']
        if ($null -eq $newLabel -or $newLabel.Value -cne $serviceHash) { throw 'Restored ClamAV does not match the restored Compose.' }
        $m.applied = $false
        $m | Add-Member -NotePropertyName rolledBackAtUtc -NotePropertyValue ((Get-Date).ToUniversalTime().ToString('o')) -Force
        $m | Add-Member -NotePropertyName rollbackContainerId -NotePropertyValue $newId -Force
        [IO.File]::WriteAllText($DeploymentManifest, ($m | ConvertTo-Json -Depth 5), [Text.Encoding]::UTF8)
        Write-Host $report
        Write-Host 'PASS: previous Compose, healthy ClamAV and James AV network alias restored.'
        Write-Host 'Deep archives may again be reported CLEAN; this is the observed fail-open state, not AV acceptance.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR at ${stage}: $($_.Exception.Message)")
    if ($changed) {
        [Console]::Error.WriteLine('Compose or containers may have changed; inspect them and the manifest before any retry.')
    } else {
        [Console]::Error.WriteLine('No files, containers or networks were changed.')
    }
    throw
}
