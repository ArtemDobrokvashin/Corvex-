<# Explicit image rollback only. Does not reverse already processed mail or repair queues. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DeploymentManifest,
    [switch]$ConfirmLaptop
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'manifest'
$tagChanged = $false
try {
    $m = Get-Content -Raw -LiteralPath $DeploymentManifest | ConvertFrom-Json
    if ($m.serviceTag -notmatch '^[a-z0-9][a-z0-9_-]*-mail-antivirus-service$' -or
        $m.backupTag -notmatch '^corvex-av-guard:previous-[a-f0-9]{32}$' -or
        $m.previousImageId -notmatch '^sha256:[a-f0-9]{64}$' -or $m.candidateImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.configHash -notmatch '^[a-f0-9]{64}$') { throw 'Invalid deployment manifest.' }
    Push-Location -LiteralPath $m.composeDir
    try {
        $stage = 'configuration'
        $hash = @(& docker compose config --hash mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $hash.Count -ne 1 -or $hash[0] -notmatch '^mail-antivirus-service\s+([a-f0-9]{64})$' -or $Matches[1] -cne $m.configHash) { throw 'Compose configuration changed.' }
        $backup = @(& docker image inspect --format '{{.Id}}' $m.backupTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $backup.Count -ne 1 -or $backup[0] -cne $m.previousImageId) { throw 'Backup image unavailable.' }
        $current = @(& docker image inspect --format '{{.Id}}' $m.serviceTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $current.Count -ne 1 -or ($current[0] -cne $m.previousImageId -and $current[0] -cne $m.candidateImageId)) { throw 'Service tag changed independently.' }
        $ids = @(& docker compose ps -a -q mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -gt 1) { throw 'Unexpected service containers.' }
        if ($ids.Count -eq 1) {
            $raw = @(& docker inspect --format '{{json .}}' $ids[0] 2>$null)
            if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) { throw 'Cannot inspect service container.' }
            $info = $raw[0] | ConvertFrom-Json
            if (($info.Image -cne $m.previousImageId -and $info.Image -cne $m.candidateImageId) -or
                ($info.Config.Image -cne $m.serviceTag -and $info.Config.Image -cne ($m.serviceTag + ':latest'))) { throw 'Unexpected service image.' }
        }
        $stage = 'restore-tag'
        # A failed Docker command may still have changed the tag.
        $tagChanged = $true
        & docker image tag $m.previousImageId $m.serviceTag
        if ($LASTEXITCODE -ne 0) { throw 'Cannot restore old tag.' }
        $restored = @(& docker image inspect --format '{{.Id}}' $m.serviceTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $restored.Count -ne 1 -or $restored[0] -cne $m.previousImageId) { throw 'Restored tag mismatch.' }
        $stage = 'recreate-av-only'
        $savedPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & docker compose up -d --no-deps --no-build --pull never --force-recreate mail-antivirus-service
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedPreference }
        if ($code -ne 0) { throw 'Compose recreation failed.' }
        $ids = @(& docker compose ps --status running -q mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1) { throw 'Restored container unavailable.' }
        $image = @(& docker inspect --format '{{.Image}}' $ids[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $image.Count -ne 1 -or $image[0] -cne $m.previousImageId) { throw 'Restored image mismatch.' }
        $healthy = $false
        for ($i = 0; $i -lt 60; $i++) {
            $status = @(& docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $ids[0] 2>$null)
            if ($LASTEXITCODE -ne 0 -or $status.Count -ne 1) { throw 'Health unavailable.' }
            if ($status[0] -ceq 'running healthy') { $healthy = $true; break }
            if ($status[0] -cne 'running starting') { throw "AV state: $($status[0])" }
            Start-Sleep -Seconds 2
        }
        if (-not $healthy) { throw 'AV did not become healthy within 120 seconds.' }
        Write-Host 'Previous AV image restored and healthy. Queues were not purged or replayed.'
        Write-Host 'Mail already processed is not undone; previous AV behavior is not fail-closed.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR: rollback failed at $stage; tag changed=$tagChanged. Do not retry blindly.")
    throw
}
