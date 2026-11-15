<# Controlled rollback drill for the experimental James gate.

   Performs, in one confirmed run: rollback to the pre-gate Compose and James
   image, read-only verification of the restored volumes and mailet config,
   a fresh artifact preparation, re-enablement, and verification of the new
   deployment. It never deletes volumes, mail, artifacts, backups or networks
   and never retries automatically. Any failure leaves a durable journal and
   stops; inspect it before doing anything else. Synthetic/no-mail James only. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DeploymentManifest,
    [string]$SourceCommit = 'HEAD',
    [switch]$ConfirmLaptop
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'manifest'
$journalDir = $null
$journal = $null
$stream = $null
$work = $null
$prep = $null
$newDeployment = $null
$resultPath = $null
$rollbackVerified = $false
$applyVerified = $false
function Write-Journal([string]$Text) {
    # Durable, exclusive journal: created once, fsync after every step.
    if ($null -ne $stream) {
        $bytes = [Text.Encoding]::ASCII.GetBytes(('{0} {1}' -f (Get-Date).ToUniversalTime().ToString('o'), $Text) + "`n")
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    }
    Write-Host $Text
}
function Read-ChildText([string]$Path) {
    # Windows PowerShell redirected output may be UTF-8, UTF-16 with BOM or OEM.
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -ge 2 -and $bytes[0] -eq 255 -and $bytes[1] -eq 254) { return [Text.Encoding]::Unicode.GetString($bytes, 2, $bytes.Length - 2) }
    if ($bytes.Length -ge 2 -and $bytes[0] -eq 254 -and $bytes[1] -eq 255) { return [Text.Encoding]::BigEndianUnicode.GetString($bytes, 2, $bytes.Length - 2) }
    return [Text.Encoding]::UTF8.GetString($bytes)
}
function Invoke-Child([string]$Script, [string[]]$Arguments, [string]$OutFile, [string]$ErrFile) {
    # Separate Windows PowerShell process: no inherited environment overrides.
    $all = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $Script) + $Arguments
    $all = @($all | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList $all -Wait -PassThru -NoNewWindow `
            -RedirectStandardOutput $OutFile -RedirectStandardError $ErrFile
    } finally { $ErrorActionPreference = $saved }
    return $proc.ExitCode
}
function Get-Sha([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing file: $Path" }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-RunningJames([string]$ComposeDir) {
    Push-Location -LiteralPath $ComposeDir
    try {
        $ids = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') {
            throw 'Expected exactly one running James container.'
        }
        return $ids[0]
    } finally { Pop-Location }
}
function Get-RunningClamav([string]$ComposeDir) {
    Push-Location -LiteralPath $ComposeDir
    try {
        $ids = @(& docker compose ps --status running -q clamav 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') {
            throw 'Expected exactly one running ClamAV container.'
        }
        return $ids[0]
    } finally { Pop-Location }
}
function Get-JamesInfo([string]$Id) {
    $raw = @(& docker inspect --format '{{json .}}' $Id 2>$null)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) { throw 'Cannot inspect James.' }
    # Full inspect data stays in memory only.
    return $raw[0] | ConvertFrom-Json
}
function Assert-Volumes($Info, $Expected) {
    foreach ($dst in @('/root/conf', '/root/extensions-jars', '/root/var')) {
        $property = $Expected.PSObject.Properties[$dst]
        if ($null -eq $property) { throw "Original volume record missing: $dst" }
        $found = @($Info.Mounts | Where-Object { $_.Destination -ceq $dst -and $_.Type -ceq 'volume' -and $_.Name -ceq $property.Value })
        if ($found.Count -ne 1) { throw "Original volume not attached: $dst" }
    }
}
function Get-MailetSha([string]$Id) {
    $lines = @(& docker exec $Id sha256sum /root/conf/mailetcontainer.xml 2>$null)
    if ($LASTEXITCODE -ne 0 -or $lines.Count -ne 1 -or $lines[0] -notmatch '^([a-f0-9]{64})\s') {
        throw 'Cannot hash the mounted mailet configuration.'
    }
    return $Matches[1]
}
function Get-GateJars([string]$Id) {
    $entries = @(& docker exec $Id find /root/extensions-jars -maxdepth 1 -type f -name '*.jar' 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot list James extension jars.' }
    return @($entries | Where-Object { $_ -match '\.jar$' })
}
function Read-ManifestLine([string]$OutFile, [string]$Label) {
    $text = Read-ChildText $OutFile
    $found = [regex]::Matches($text, '(?m)^' + [regex]::Escape($Label) + ': (.+?)\r?$')
    if ($found.Count -ne 1) { throw "Expected exactly one '$Label' line in child output." }
    return $found[0].Groups[1].Value
}
try {
    foreach ($name in @('docker', 'git', 'tar', 'mvn', 'powershell.exe')) {
        if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "Required command missing: $name" }
    }
    foreach ($script in @('rollback-james-av.ps1', 'prepare-james-av-runtime.ps1', 'enable-james-av.ps1')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $script) -PathType Leaf)) { throw "Required script missing: $script" }
    }
    $m = Get-Content -Raw -LiteralPath $DeploymentManifest | ConvertFrom-Json
    if ($m.network -notmatch '^corvex-james-av-[a-f0-9]{32}$' -or $m.jamesImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.composeBeforeSha256 -notmatch '^[a-f0-9]{64}$' -or $m.composeAfterSha256 -notmatch '^[a-f0-9]{64}$' -or
        $m.rollbackComposeSha256 -notmatch '^[a-f0-9]{64}$' -or $m.webAdminPinned -isnot [bool] -or
        $m.composeDir -isnot [string] -or -not (Test-Path -LiteralPath $m.composeDir -PathType Container)) {
        throw 'Incomplete or invalid deployment manifest; inspect manually.'
    }
    if ((Get-Sha $m.composeBackup) -cne $m.composeBeforeSha256 -or (Get-Sha $m.rollbackCompose) -cne $m.rollbackComposeSha256) {
        throw 'Compose backup or rollback file changed; preserve and inspect.'
    }
    if ($m.webAdminPinned) {
        $webFile = Join-Path $m.composeDir $m.webAdminRelative
        if ((Get-Sha $webFile) -cne $m.webAdminSha256) { throw 'WebAdmin source changed; preserve and inspect.' }
    }
    $prep = Get-Content -Raw -LiteralPath $m.preparationManifest | ConvertFrom-Json
    if ($prep.originalConfigSha256 -notmatch '^[a-f0-9]{64}$' -or $prep.candidateXmlSha256 -notmatch '^[a-f0-9]{64}$' -or
        $prep.composeDir -cne $m.composeDir) { throw 'Preparation manifest is inconsistent with the deployment.' }
    $stage = 'preflight'
    if ((Get-Sha (Join-Path $m.composeDir 'docker-compose.yml')) -cne $m.composeAfterSha256) {
        throw 'Compose changed independently since deployment; preserve and inspect.'
    }
    $container = Get-RunningJames $m.composeDir
    $info = Get-JamesInfo $container
    if ($info.Image -cne $m.jamesImageId) { throw 'James image changed independently; preserve and inspect.' }
    if ((Get-MailetSha $container) -cne $prep.candidateXmlSha256) { throw 'Gate mailet configuration is not the deployed one.' }
    if ((Get-GateJars $container).Count -lt 1) { throw 'Gate extension jar is not mounted.' }
    # Read-only availability checks; the drill never reconfigures ClamAV.
    $clam = Get-RunningClamav $m.composeDir
    $clamStatus = @(& docker inspect --format '{{.State.Status}} {{.State.Health.Status}}' $clam 2>$null)
    if ($LASTEXITCODE -ne 0 -or $clamStatus.Count -ne 1 -or $clamStatus[0] -cne 'running healthy') {
        throw 'ClamAV is not healthy; do not drill.'
    }
    $domains = @(& docker exec $container james-cli ListDomains 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'James CLI is not ready; do not drill.' }
    Write-Host "James CLI reported $($domains.Count) domain(s)."

    $journalDir = Join-Path (Split-Path -Parent (Resolve-Path -LiteralPath $DeploymentManifest).Path) ('rollback-drill-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $journalDir -ErrorAction Stop | Out-Null
    $journal = Join-Path $journalDir 'journal.txt'
    $stream = [IO.File]::Open($journal, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::Read)
    $work = Join-Path $journalDir 'child-output'
    New-Item -ItemType Directory -Path $work | Out-Null
    Write-Journal 'PREFLIGHT PASS: deployment manifest, Compose, gate mount, ClamAV and James verified.'
    Write-Journal "Journal directory: $journalDir"

    # Stage 1: rollback to the recorded pre-gate Compose and image.
    $stage = 'rollback'
    Write-Journal 'ROLLBACK START: restoring pre-gate Compose and recreating James once.'
    $out = Join-Path $work 'rollback-out.txt'
    $err = Join-Path $work 'rollback-err.txt'
    $code = Invoke-Child (Join-Path $PSScriptRoot 'rollback-james-av.ps1') @('-DeploymentManifest', $DeploymentManifest, '-ConfirmLaptop') $out $err
    (Read-ChildText $out).TrimEnd() | Write-Host
    if ($code -ne 0) {
        (Read-ChildText $err).TrimEnd().Split("`n") | ForEach-Object { [Console]::Error.WriteLine($_) }
        throw "Rollback script failed with exit $code."
    }
    $stage = 'verify-rollback'
    if ((Get-Sha (Join-Path $m.composeDir 'docker-compose.yml')) -cne $m.rollbackComposeSha256) { throw 'Restored Compose hash differs.' }
    $container = Get-RunningJames $m.composeDir
    $info = Get-JamesInfo $container
    if ($info.Image -cne $m.jamesImageId) { throw 'Restored James image differs.' }
    Assert-Volumes $info $m.originalVolumes
    if ((Get-MailetSha $container) -cne $prep.originalConfigSha256) { throw 'Restored mailet configuration differs from the original.' }
    if ((Get-GateJars $container).Count -ne 0) { throw 'Gate extension jars remain after rollback.' }
    $rollbackVerified = $true
    Write-Journal 'ROLLBACK VERIFIED: pre-gate Compose, image, original volumes and original mailet config restored.'

    # Stage 2: fresh preparation from an immutable commit.
    $stage = 'prepare'
    Write-Journal "PREPARE START: rebuilding artifacts from source $SourceCommit."
    $out = Join-Path $work 'prepare-out.txt'
    $err = Join-Path $work 'prepare-err.txt'
    $code = Invoke-Child (Join-Path $PSScriptRoot 'prepare-james-av-runtime.ps1') @('-ComposeDir', $m.composeDir, '-SourceCommit', $SourceCommit, '-ConfirmLaptop') $out $err
    (Read-ChildText $out).TrimEnd() | Write-Host
    if ($code -ne 0) {
        (Read-ChildText $err).TrimEnd().Split("`n") | ForEach-Object { [Console]::Error.WriteLine($_) }
        throw "Preparation failed with exit $code; James is pre-gate."
    }
    $newPrepPath = Read-ManifestLine $out 'James AV preparation manifest'
    $newPrep = Get-Content -Raw -LiteralPath $newPrepPath | ConvertFrom-Json
    if ($newPrep.jamesContainerId -cne $container -or $newPrep.jamesImageId -cne $m.jamesImageId -or
        $newPrep.originalConfigSha256 -cne $prep.originalConfigSha256) { throw 'New preparation manifest does not match the restored runtime.' }
    $stage = 'verify-prepare'
    foreach ($pair in @(@($newPrep.originalConfig, $newPrep.originalConfigSha256), @($newPrep.candidateXml, $newPrep.candidateXmlSha256),
                        @($newPrep.candidateJar, $newPrep.candidateJarSha256), @($newPrep.parentJar, $newPrep.parentJarSha256))) {
        if ((Get-Sha $pair[0]) -cne $pair[1]) { throw 'Prepared artifact hash differs.' }
    }
    Write-Journal "PREPARE VERIFIED: artifacts built from commit $($newPrep.candidateCommit)."

    # Stage 3: re-enable using the fresh preparation manifest.
    $stage = 'apply'
    Write-Journal 'APPLY START: enabling the gate again with a new isolated network.'
    $out = Join-Path $work 'apply-out.txt'
    $err = Join-Path $work 'apply-err.txt'
    $code = Invoke-Child (Join-Path $PSScriptRoot 'enable-james-av.ps1') @('-PreparationManifest', $newPrepPath, '-ConfirmLaptop', '-Apply') $out $err
    (Read-ChildText $out).TrimEnd() | Write-Host
    if ($code -ne 0) {
        (Read-ChildText $err).TrimEnd().Split("`n") | ForEach-Object { [Console]::Error.WriteLine($_) }
        throw "Re-enable failed with exit $code; James may be pre-gate or partially configured."
    }
    $newDeploymentPath = Read-ManifestLine $out 'James AV deployment manifest'
    $newDeployment = Get-Content -Raw -LiteralPath $newDeploymentPath | ConvertFrom-Json
    $stage = 'verify-apply'
    if ((Get-Sha (Join-Path $m.composeDir 'docker-compose.yml')) -cne $newDeployment.composeAfterSha256) { throw 'Re-enabled Compose hash differs.' }
    $container = Get-RunningJames $m.composeDir
    $info = Get-JamesInfo $container
    if ($info.Image -cne $m.jamesImageId) { throw 'James image changed during re-enable.' }
    Assert-Volumes $info $m.originalVolumes
    if ((Get-MailetSha $container) -cne $newPrep.candidateXmlSha256) { throw 'Gate mailet configuration is not mounted.' }
    if ((Get-GateJars $container).Count -lt 2) { throw 'Expected gate and parent extension jars.' }
    $applyVerified = $true
    Write-Journal 'APPLY VERIFIED: gate configuration and both extension jars are mounted; original volumes retained.'

    $stage = 'result'
    $result = [ordered]@{
        completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        originalDeploymentManifest = (Resolve-Path -LiteralPath $DeploymentManifest).Path
        rollbackVerified = $rollbackVerified
        sourceCommit = $newPrep.candidateCommit
        newPreparationManifest = $newPrepPath
        newDeploymentManifest = $newDeploymentPath
        applyVerified = $applyVerified
        jamesImageId = $m.jamesImageId
        retiredNetwork = $m.network
        activeNetwork = $newDeployment.network
        note = 'Rollback and re-enable were exercised. Mail flow, quarantine and clamd behaviour are still not proven; retained networks and backups must not be deleted.'
    }
    $resultPath = Join-Path $journalDir 'result.json'
    [IO.File]::WriteAllText($resultPath, ($result | ConvertTo-Json -Depth 4), [Text.Encoding]::UTF8)
    Write-Journal "PASS: rollback and re-enable verified. Result: $resultPath"
    Write-Journal "Retained networks: $($m.network), $($newDeployment.network). Do not delete while in use."
} catch {
    $message = $_.Exception.Message
    if ($null -ne $stream) { Write-Journal "FAILED at stage ${stage}: $message" }
    [Console]::Error.WriteLine("ERROR: rollback drill failed at stage $stage. State: rollbackVerified=$rollbackVerified applyVerified=$applyVerified. Journal: $journal")
    [Console]::Error.WriteLine('James may be pre-gate. Do not retry blindly; inspect the journal, Compose file and containers first.')
    throw
} finally {
    if ($null -ne $stream) { $stream.Dispose() }
}
