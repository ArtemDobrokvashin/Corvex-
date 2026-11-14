<# Single-service candidate deploy. Does not claim scan completeness or mail recovery. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$ComposeDir = (Get-Location).Path,
    [switch]$ConfirmLaptop,
    [switch]$Apply
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'manifest'
$stateFile = $null
$tagChanged = $false
try {
    $m = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
    if ($m.candidateCommit -notmatch '^[a-f0-9]{40}$' -or $m.candidateTag -notmatch '^corvex-av-guard:candidate-[a-f0-9]{32}$' -or
        $m.candidateImageId -notmatch '^sha256:[a-f0-9]{64}$' -or $m.previousImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.previousContainerId -notmatch '^[a-f0-9]{12,64}$') { throw 'Invalid manifest.' }
    Push-Location -LiteralPath $ComposeDir
    try {
        $stage = 'source'
        $root = (Get-Location).Path.TrimEnd([char[]]@('\', '/'))
        $commit = @(& git rev-parse 'eda8c7d^{commit}' 2>$null)
        if ($LASTEXITCODE -ne 0 -or $commit.Count -ne 1 -or $commit[0] -cne $m.candidateCommit) { throw 'Candidate commit mismatch.' }
        $prefix = 'services/mail-antivirus-service/src/'
        $fullCommit = $commit[0]
        $tree = @(& git ls-tree -r $fullCommit -- $prefix)
        if ($LASTEXITCODE -ne 0 -or $tree.Count -eq 0) { throw 'Source tree unavailable.' }
        $expected = @{}
        foreach ($line in $tree) {
            if ($line -notmatch '^(100644|100755) blob ([a-f0-9]{40})\t(services/mail-antivirus-service/src/[A-Za-z0-9._/-]+)$') { throw 'Unexpected tree.' }
            $expected[$Matches[3]] = $Matches[2]
        }
        foreach ($relative in @('services/mail-antivirus-service/Dockerfile', 'services/mail-antivirus-service/pom.xml', 'services/mail-antivirus-service/maven-settings.xml')) {
            $oid = @(& git rev-parse "$($m.candidateCommit):$relative" 2>$null)
            if ($LASTEXITCODE -ne 0 -or $oid.Count -ne 1) { throw 'Build metadata unavailable.' }
            $expected[$relative] = $oid[0]
        }
        $src = Join-Path $root ($prefix.TrimEnd('/'))
        if (-not (Test-Path -LiteralPath $src -PathType Container)) { throw 'Source directory missing.' }
        $items = @((Get-Item -LiteralPath $src -Force)) + @(Get-ChildItem -LiteralPath $src -Recurse -Force)
        foreach ($item in $items) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Reparse point in source tree.' }
            if (-not $item.PSIsContainer) {
                $path = $item.FullName.Substring($root.Length + 1).Replace('\', '/')
                if (-not $expected.ContainsKey($path)) { throw 'Extra source file.' }
            }
        }
        foreach ($path in $expected.Keys) {
            $file = Join-Path $root $path
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing source: $path" }
            $hash = @(& git hash-object -- $file 2>$null)
            if ($LASTEXITCODE -ne 0 -or $hash.Count -ne 1 -or $hash[0] -cne $expected[$path]) { throw "Source differs: $path" }
        }
        $stage = 'images-and-container'
        $new = @(& docker image inspect --format '{{.Id}}' $m.candidateTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $new.Count -ne 1 -or $new[0] -cne $m.candidateImageId) { throw 'Candidate image mismatch.' }
        $old = @(& docker image inspect --format '{{.Id}}' $m.previousImageId 2>$null)
        if ($LASTEXITCODE -ne 0 -or $old.Count -ne 1 -or $old[0] -cne $m.previousImageId) { throw 'Previous image missing.' }
        $ids = @(& docker compose ps --status running -q mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -cne $m.previousContainerId) { throw 'Container changed.' }
        $infoRaw = @(& docker inspect --format '{{json .}}' $ids[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $infoRaw.Count -ne 1) { throw 'Container metadata unavailable.' }
        # The full inspect data stays in memory only and is never displayed or saved.
        $info = $infoRaw[0] | ConvertFrom-Json
        if ($info.Image -cne $m.previousImageId -or $info.State.Health.Status -cne 'healthy') { throw 'Container image/health changed.' }
        $project = $info.Config.Labels.'com.docker.compose.project'
        $oldHash = $info.Config.Labels.'com.docker.compose.config-hash'
        if ($project -notmatch '^[a-z0-9][a-z0-9_-]*$' -or $oldHash -notmatch '^[a-f0-9]{64}$') { throw 'Invalid Compose labels.' }
        $serviceTag = $project + '-mail-antivirus-service'
        if ($info.Config.Image -cne $serviceTag -and $info.Config.Image -cne ($serviceTag + ':latest')) { throw 'Unexpected service image reference.' }
        $tagId = @(& docker image inspect --format '{{.Id}}' $serviceTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $tagId.Count -ne 1 -or $tagId[0] -cne $m.previousImageId) { throw 'Service tag no longer points to old image.' }
        $hashLine = @(& docker compose config --hash mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $hashLine.Count -ne 1 -or $hashLine[0] -notmatch '^mail-antivirus-service\s+([a-f0-9]{64})$' -or $Matches[1] -cne $oldHash) { throw 'Compose configuration changed.' }
        $stage = 'queue-snapshot'
        $savedPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $raw = @(& docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged consumers type --formatter json 2>$null)
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedPreference }
        if ($code -ne 0) { throw 'Queue inventory failed.' }
        $json = ($raw -join "`n").Trim()
        if ($json.Length -gt 1048576 -or -not $json.StartsWith('[') -or -not $json.EndsWith(']')) { throw 'Unexpected queue inventory.' }
        $rows = @(ConvertFrom-Json -InputObject $json)
        if ($rows.Count -eq 1 -and $rows[0] -is [array]) { $rows = @($rows[0]) }
        $required = @('mail.av.spam-checked', 'mail.av.spam-checked.dlq', 'mail.storage.av-checked', 'mail.storage.av-checked.dlq')
        $found = @{}
        foreach ($row in $rows) {
            if ($null -eq $row -or $row.PSObject.Properties.Name -notcontains 'name') { throw 'Invalid queue inventory.' }
            if ($required -cnotcontains $row.name) { continue }
            if ($found.ContainsKey($row.name)) { throw 'Duplicate queue.' }
            foreach ($key in @('messages_ready', 'messages_unacknowledged', 'consumers')) {
                if ($row.PSObject.Properties.Name -notcontains $key -or ($row.$key -isnot [int] -and $row.$key -isnot [long]) -or $row.$key -lt 0) { throw 'Invalid queue counter.' }
            }
            $found[$row.name] = $row
        }
        foreach ($name in $required) {
            if (-not $found.ContainsKey($name)) { throw 'Required queue missing.' }
            $row = $found[$name]
            $needConsumers = if ($name.EndsWith('.dlq')) { 0 } else { 1 }
            if ($row.messages_ready -ne 0 -or $row.messages_unacknowledged -ne 0 -or $row.consumers -ne $needConsumers) { throw "Queue is not idle: $name" }
            Write-Host "$name ready=$($row.messages_ready) unacked=$($row.messages_unacknowledged) consumers=$($row.consumers)"
        }
        # A snapshot cannot stop messages arriving immediately afterward.
        if (-not $Apply) {
            Write-Host 'Dry-run preflight passed. No image tags or containers changed.'
            return
        }
        $stage = 'save-rollback'
        $suffix = [guid]::NewGuid().ToString('N')
        $backupTag = 'corvex-av-guard:previous-' + $suffix
        $existing = @(& docker image ls -q --filter "reference=$backupTag" 2>$null)
        if ($LASTEXITCODE -ne 0 -or $existing.Count -ne 0) { throw 'Backup tag unavailable.' }
        $dir = Join-Path ([IO.Path]::GetTempPath()) ('corvex-av-deploy-' + $suffix)
        New-Item -ItemType Directory -Path $dir | Out-Null
        $stateFile = Join-Path $dir 'deployment-manifest.json'
        $state = [ordered]@{ composeDir = $root; originalContainerId = $m.previousContainerId; previousImageId = $m.previousImageId; candidateImageId = $m.candidateImageId; candidateTag = $m.candidateTag; serviceTag = $serviceTag; backupTag = $backupTag; candidateCommit = $m.candidateCommit; configHash = $oldHash }
        [IO.File]::WriteAllText($stateFile, ($state | ConvertTo-Json), [Text.Encoding]::UTF8)
        Write-Host "Deployment manifest: $stateFile"
        & docker image tag $m.previousImageId $backupTag
        if ($LASTEXITCODE -ne 0) { throw 'Cannot preserve previous image tag.' }
        $backup = @(& docker image inspect --format '{{.Id}}' $backupTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $backup.Count -ne 1 -or $backup[0] -cne $m.previousImageId) { throw 'Backup tag verification failed.' }
        $stage = 'activate-tag'
        # Do not auto-rollback: messages may have been processed by either version.
        # Mark uncertain before the Docker command: failure may occur after mutation.
        $tagChanged = $true
        & docker image tag $m.candidateImageId $serviceTag
        if ($LASTEXITCODE -ne 0) { throw 'Cannot activate candidate tag.' }
        $activated = @(& docker image inspect --format '{{.Id}}' $serviceTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $activated.Count -ne 1 -or $activated[0] -cne $m.candidateImageId) { throw 'Candidate tag verification failed.' }
        $stage = 'recreate-av-only'
        $savedPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & docker compose up -d --no-deps --no-build --pull never --force-recreate mail-antivirus-service
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedPreference }
        if ($code -ne 0) { throw 'Compose recreation failed.' }
        $stage = 'health'
        $newIds = @(& docker compose ps --status running -q mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $newIds.Count -ne 1) { throw 'New AV container unavailable.' }
        $runningImage = @(& docker inspect --format '{{.Image}}' $newIds[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $runningImage.Count -ne 1 -or $runningImage[0] -cne $m.candidateImageId) { throw 'Running image mismatch.' }
        $healthy = $false
        for ($i = 0; $i -lt 60; $i++) {
            $status = @(& docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $newIds[0] 2>$null)
            if ($LASTEXITCODE -ne 0 -or $status.Count -ne 1) { throw 'Health unavailable.' }
            if ($status[0] -ceq 'running healthy') { $healthy = $true; break }
            if ($status[0] -notin @('running starting', 'running healthy')) { throw "AV state: $($status[0])" }
            Start-Sleep -Seconds 2
        }
        if (-not $healthy) { throw 'AV did not become healthy within 120 seconds.' }
        Write-Host 'Candidate AV container is running and healthy.'
        Write-Host "Previous image backup tag: $backupTag"
        Write-Host 'This is not an EICAR/outage test, mail durability proof or complete scan guarantee.'
        Write-Host 'Do not replay or purge queues. Retain the deployment manifest and both images.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR at $stage. Deployment manifest: $stateFile")
    if ($tagChanged) { [Console]::Error.WriteLine('Service tag was changed; container state may have changed. Do not retry or rollback blindly.') }
    else { [Console]::Error.WriteLine('Service tag not changed; a backup image tag may have been created.') }
    throw
}
