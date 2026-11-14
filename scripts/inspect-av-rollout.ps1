<# Read-only AV rollout inventory; no message bodies, credentials or service mutations. #>
[CmdletBinding()]
param(
    [string]$ComposeDir = (Get-Location).Path,
    [string]$Candidate = 'eda8c7d'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$services = @('rabbitmq', 'clamav', 'mail-storage-service', 'mail-antivirus-service')
$queues = @('mail.av.spam-checked', 'mail.av.spam-checked.dlq',
            'mail.storage.av-checked', 'mail.storage.av-checked.dlq')
$files = @(
    'services/mail-antivirus-service/Dockerfile',
    'services/mail-antivirus-service/pom.xml',
    'services/mail-antivirus-service/src/main/java/ru/corvex/mailav/ClamAvClient.java',
    'services/mail-antivirus-service/src/main/java/ru/corvex/mailav/pipeline/AntivirusCheckListener.java',
    'services/mail-antivirus-service/src/main/java/ru/corvex/mailav/pipeline/AvPipelineAmqpConfig.java',
    'services/mail-antivirus-service/src/main/java/ru/corvex/mailav/pipeline/AvRetryRecoverer.java',
    'services/mail-antivirus-service/src/main/java/ru/corvex/mailav/pipeline/AvVerdictUnavailableException.java',
    'services/mail-antivirus-service/src/main/java/ru/corvex/mailav/pipeline/AvScanLimitExceededException.java',
    'services/mail-storage-service/src/main/java/ru/corvex/mailstorage/pipeline/AvCheckedListener.java',
    'services/mail-storage-service/src/main/java/ru/corvex/mailstorage/pipeline/StoragePipelineAmqpConfig.java'
)
try {
    if ($Candidate -notmatch '^[a-f0-9]{7,40}$') { throw 'Invalid candidate commit.' }
    Push-Location -LiteralPath $ComposeDir
    try {
        Write-Host '=== Candidate source comparison (not deployed-image verification) ==='
        foreach ($file in $files) {
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
                Write-Host "${file}: MISSING"
                continue
            }
            $expected = @(& git rev-parse "${Candidate}:${file}" 2>$null)
            if ($LASTEXITCODE -ne 0 -or $expected.Count -ne 1 -or $expected[0] -notmatch '^[a-f0-9]{40}$') {
                throw 'Unable to inspect candidate commit.'
            }
            $actual = @(& git hash-object -- $file 2>$null)
            if ($LASTEXITCODE -ne 0 -or $actual.Count -ne 1 -or $actual[0] -notmatch '^[a-f0-9]{40}$') {
                throw 'Unable to hash local source file.'
            }
            if ($actual[0] -ceq $expected[0]) { $comparison = 'MATCH' } else { $comparison = 'DIFFERENT' }
            Write-Host "${file}: $comparison"
        }
        Write-Host '=== Container status (not health or recovery proof) ==='
        foreach ($service in $services) {
            $ids = @(& docker compose ps --status running -q $service 2>$null)
            if ($LASTEXITCODE -ne 0) { throw 'Unable to query running containers.' }
            if ($ids.Count -ne 1 -or $ids[0] -notmatch '^[0-9a-f]{12,64}$') {
                Write-Host "${service}: not exactly one running container"
                continue
            }
            $format = '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}'
            $status = @(& docker inspect --format $format $ids[0] 2>$null)
            if ($LASTEXITCODE -ne 0 -or $status.Count -ne 1 -or $status[0] -notmatch '^[a-z]+ [a-z-]+$') {
                throw 'Unable to inspect container status.'
            }
            Write-Host "${service}: $($status[0])"
        }
        Write-Host '=== AV queues (counts only) ==='
        $old = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $raw = @(& docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged consumers type --formatter json 2>$null)
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $old }
        if ($exitCode -ne 0) { throw 'Unable to query queue counters.' }
        $json = $raw -join "`n"
        if ($json.Length -gt 1048576) { throw 'Queue response too large.' }
        $rows = @(ConvertFrom-Json -InputObject $json)
        $found = @{}
        foreach ($row in $rows) {
            $names = @($row.PSObject.Properties.Name)
            if ($names -notcontains 'name' -or $row.name -isnot [string]) { throw 'Invalid queue response.' }
            if (-not ($queues -ccontains $row.name)) { continue }
            if ($found.ContainsKey($row.name)) { throw 'Duplicate queue response.' }
            foreach ($key in @('messages_ready', 'messages_unacknowledged', 'consumers')) {
                if ($names -notcontains $key -or $row.$key -isnot [int] -and $row.$key -isnot [long] -or $row.$key -lt 0) {
                    throw 'Invalid queue counter.'
                }
            }
            if ($names -notcontains 'type' -or @('classic', 'quorum', 'stream') -cnotcontains $row.type) {
                throw 'Invalid queue type.'
            }
            $found[$row.name] = $true
            Write-Host "$($row.name): ready=$($row.messages_ready) unacked=$($row.messages_unacknowledged) consumers=$($row.consumers) type=$($row.type)"
        }
        foreach ($queue in $queues) { if (-not $found.ContainsKey($queue)) { Write-Host "${queue}: MISSING OR NOT VISIBLE" } }
        Write-Host 'Read-only snapshot; not a consistent snapshot, deployment authorization or recovery proof.'
        Write-Host 'Do not replay or purge DLQ; do not change ClamAV settings based on this report alone.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine('ERROR: AV inventory failed; no deployment performed.')
    exit 2
}
