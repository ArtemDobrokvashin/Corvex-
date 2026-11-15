<# Enable ClamAV "alert on scan limits" in the existing Compose project.

   The laptop probe showed a depth-25 ZIP and the same archive inside MIME being
   reported CLEAN by the running clamd, so this is a fail-open result today.
   With AlertExceedsMax the daemon reports Heuristics.Limits.Exceeded... FOUND
   instead, which the James gate routes to the unknown/quarantine processor.

   Only the clamav container is recreated. Its manual James AV network
   attachments are recorded first and re-created with the 'clamav' alias,
   because recreating a container drops manually connected networks. Nothing is
   deleted; a Compose backup and a durable manifest are written. Not applied
   automatically: -Apply is required. This does not prove complete scanning:
   ClamAV raises the alert only for some limits. #>
[CmdletBinding()]
param(
    [string]$ComposeDir = (Get-Location).Path,
    [switch]$ConfirmLaptop,
    [switch]$Apply
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'preflight'
$manifestPath = $null
$changed = $false
$newId = $null
function Get-Compose([string]$Dir) { return Join-Path $Dir 'docker-compose.yml' }
function Read-ComposeText([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    $bom = $bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191
    return [IO.File]::ReadAllText($Path, (New-Object System.Text.UTF8Encoding($bom, $true)))
}
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
            $aliases = @($current.PSObject.Properties[$network].Value.Aliases)
            if ($aliases -cnotcontains 'clamav') { throw "Network $network is connected without the clamav alias." }
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
    if (-not (Test-Path -LiteralPath $ComposeDir -PathType Container)) { throw 'Compose directory not found.' }
    Push-Location -LiteralPath $ComposeDir
    try {
        $root = (Get-Location).Path
        $composePath = Get-Compose $root
        if (-not (Test-Path -LiteralPath $composePath -PathType Leaf)) { throw 'Compose file missing.' }
        $stage = 'container'
        $ids = (Invoke-Docker @('compose', 'ps', '--status', 'running', '-q', 'clamav')).Output
        if (@($ids).Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Expected one running ClamAV container.' }
        $oldId = $ids[0]
        $infoRaw = Invoke-Docker @('inspect', '--format', '{{json .}}', $oldId)
        if ($infoRaw.Code -ne 0 -or $infoRaw.Output.Count -ne 1) { throw 'Cannot inspect ClamAV.' }
        $info = $infoRaw.Output[0] | ConvertFrom-Json
        if ($info.State.Status -cne 'running' -or $info.State.Health.Status -cne 'healthy') { throw 'ClamAV is not healthy.' }
        if ($info.Config.Image -cne 'clamav/clamav:1.5.4') { throw 'Unexpected ClamAV image reference.' }
        $imageId = $info.Image
        if ($imageId -notmatch '^sha256:[a-f0-9]{64}$') { throw 'Unexpected ClamAV image id.' }
        $hash = (Invoke-Docker @('compose', 'config', '--hash', 'clamav')).Output
        if (@($hash).Count -ne 1 -or $hash[0] -notmatch '^clamav\s+([a-f0-9]{64})$') { throw 'Compose hash unavailable.' }
        $runningHash = $Matches[1]
        $label = $info.Config.Labels.PSObject.Properties['com.docker.compose.config-hash']
        if ($null -eq $label -or $label.Value -cne $runningHash) { throw 'Compose differs from the running ClamAV.' }
        $stage = 'networks'
        $networkJson = Get-Networks $oldId
        $avNetworks = @($networkJson.PSObject.Properties.Name | Where-Object { $_ -match '^corvex-james-av-[a-f0-9]{32}$' })
        if ($avNetworks.Count -eq 0) { throw 'ClamAV is not attached to a James AV network; enable the gate first.' }
        $jamesIds = (Invoke-Docker @('compose', '--profile', 'james-transition', 'ps', '--status', 'running', '-q', 'james')).Output
        if (@($jamesIds).Count -ne 1 -or $jamesIds[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Expected one running James container.' }
        $jamesNetworks = (Get-Networks $jamesIds[0]).PSObject.Properties.Name
        $shared = @($avNetworks | Where-Object { $jamesNetworks -ccontains $_ })
        if ($shared.Count -eq 0) { throw 'ClamAV and James share no James AV network.' }
        $stage = 'compose-text'
        $head = @([IO.File]::ReadAllBytes($composePath))
        $hasBom = $head.Length -ge 3 -and $head[0] -eq 239 -and $head[1] -eq 187 -and $head[2] -eq 191
        $text = Read-ComposeText $composePath
        if ($text.Contains('CLAMD_CONF_AlertExceedsMax')) { throw 'Limit alert is already configured; inspect it.' }
        $nl = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
        $pattern = '(?ms)^  clamav:[ \t]*(?:#.*)?\r?\n.*?(?=^  [A-Za-z0-9_-]+:[ \t]*(?:#.*)?\r?$|^[A-Za-z0-9_-]+:|\z)'
        $blocks = @([regex]::Matches($text, $pattern))
        if ($blocks.Count -ne 1) { throw 'Unexpected Compose layout for clamav.' }
        $block = $blocks[0].Value
        if ($block -match '(?m)^    environment:') { throw 'ClamAV already has an environment block; inspect it.' }
        $anchor = '(?m)^    volumes: \[clamav-data:/var/lib/clamav\]\r?$'
        if ([regex]::Matches($block, $anchor).Count -ne 1) { throw 'ClamAV volumes line not found; inspect Compose.' }
        $addition = '    environment:' + $nl + '      CLAMD_CONF_AlertExceedsMax: "yes"'
        $newBlock = [regex]::Replace($block, $anchor, ('$0' + $nl + $addition), 1)
        $newText = $text.Substring(0, $blocks[0].Index) + $newBlock + $text.Substring($blocks[0].Index + $block.Length)
        if ($newText.Contains('CLAMD_CONF_AlertExceedsMax') -eq $false) { throw 'Compose edit did not apply.' }
        $stage = 'plan'
        Write-Host 'Plan: add CLAMD_CONF_AlertExceedsMax=yes to clamav only, recreate clamav,'
        Write-Host "reattach James AV network(s): $($avNetworks -join ', '), then verify health and config."
        Write-Host 'Effect: archives or messages that exceed scan limits will be reported as FOUND instead of CLEAN.'
        Write-Host 'The James gate treats that as unknown/quarantine. Not every incomplete-scan cause is covered.'
        if (-not $Apply) {
            Write-Host 'Dry-run PASS. No files, containers or networks were changed.'
            return
        }
        $stage = 'backup'
        $backupDir = Join-Path ([IO.Path]::GetTempPath()) ('corvex-clamav-limit-' + [guid]::NewGuid().ToString('N'))
        if (Test-Path -LiteralPath $backupDir) { throw 'Backup path already exists.' }
        New-Item -ItemType Directory -Path $backupDir -ErrorAction Stop | Out-Null
        $backup = Join-Path $backupDir 'docker-compose.yml.backup'
        $beforeSha = (Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::WriteAllBytes($backup, [IO.File]::ReadAllBytes($composePath))
        if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash.ToLowerInvariant() -cne $beforeSha) { throw 'Compose backup hash mismatch.' }
        $manifestPath = Join-Path $backupDir 'deployment-manifest.json'
        $state = [ordered]@{
            composeDir = $root; composeBackup = $backup; composeBeforeSha256 = $beforeSha
            previousContainerId = $oldId; clamavImageId = $imageId; avNetworks = @($avNetworks)
            runningServiceHash = $runningHash; applied = $false
        }
        [IO.File]::WriteAllText($manifestPath, ($state | ConvertTo-Json -Depth 4), [Text.Encoding]::UTF8)
        $stage = 'write-compose'
        $changed = $true
        if ($hasBom) {
            [IO.File]::WriteAllText($composePath, $newText, (New-Object System.Text.UTF8Encoding($true)))
        } else {
            [IO.File]::WriteAllBytes($composePath, (New-Object System.Text.UTF8Encoding($false)).GetBytes($newText))
        }
        $afterSha = (Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant()
        $stage = 'recreate'
        $up = Invoke-Docker @('compose', 'up', '-d', '--no-deps', '--no-build', '--pull', 'never', '--force-recreate', 'clamav')
        if ($up.Code -ne 0) { throw 'ClamAV recreation failed.' }
        $ids = (Invoke-Docker @('compose', 'ps', '--status', 'running', '-q', 'clamav')).Output
        if (@($ids).Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'New ClamAV container not found.' }
        $newId = $ids[0]
        $stage = 'health'
        $null = Wait-Healthy $newId
        $stage = 'networks-reattach'
        Connect-AvNetworks $newId $avNetworks
        $stage = 'verify-config'
        $report = Assert-ConfigReport $root 'AlertExceedsMax: yes'
        $stage = 'verify-hash'
        $newInfoRaw = Invoke-Docker @('inspect', '--format', '{{json .}}', $newId)
        if ($newInfoRaw.Code -ne 0 -or $newInfoRaw.Output.Count -ne 1) { throw 'Cannot inspect the new ClamAV.' }
        $newInfo = $newInfoRaw.Output[0] | ConvertFrom-Json
        $newHash = (Invoke-Docker @('compose', 'config', '--hash', 'clamav')).Output
        if (@($newHash).Count -ne 1 -or $newHash[0] -notmatch '^clamav\s+([a-f0-9]{64})$') { throw 'New Compose hash unavailable.' }
        $newServiceHash = $Matches[1]
        $newLabel = $newInfo.Config.Labels.PSObject.Properties['com.docker.compose.config-hash']
        if ($null -eq $newLabel -or $newLabel.Value -cne $newServiceHash) { throw 'New ClamAV does not match the edited Compose.' }
        $state.applied = $true
        $state['composeAfterSha256'] = $afterSha
        $state['newContainerId'] = $newId
        $state['newServiceHash'] = $newServiceHash
        $state['verifiedAtUtc'] = (Get-Date).ToUniversalTime().ToString('o')
        [IO.File]::WriteAllText($manifestPath, ($state | ConvertTo-Json -Depth 4), [Text.Encoding]::UTF8)
        Write-Host $report
        Write-Host 'PASS: ClamAV recreated, healthy, James AV network alias restored and limit alerts configured.'
        Write-Host "Deployment manifest: $manifestPath"
        Write-Host 'Run the synthetic nested-archive probe once; it must no longer report CLEAN for the deep sample.'
        Write-Host 'This is not complete AV coverage, malware detection proof or cutover approval.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR at ${stage}: $($_.Exception.Message)")
    if ($changed) {
        [Console]::Error.WriteLine("Compose or containers may have changed. Manifest: $manifestPath")
        [Console]::Error.WriteLine('Inspect the manifest and containers; do not retry blindly. Rollback: rollback-clamav-limit-alert.ps1')
    } else {
        [Console]::Error.WriteLine('No files, containers or networks were changed.')
    }
    throw
}
