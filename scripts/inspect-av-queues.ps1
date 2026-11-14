<# Read-only RabbitMQ AV queue counters; never reads or changes message contents. #>
[CmdletBinding()]
param([string]$ComposeDir = (Get-Location).Path)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$queues = @('mail.av.spam-checked', 'mail.av.spam-checked.dlq',
            'mail.storage.av-checked', 'mail.storage.av-checked.dlq')
$stage = 'initialization'
try {
    Push-Location -LiteralPath $ComposeDir
    try {
        $stage = 'rabbitmq-command'
        $old = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $raw = @(& docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages_ready messages_unacknowledged consumers type --formatter json 2>$null)
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $old }
        if ($code -ne 0) { throw 'RabbitMQ command failed.' }
        $stage = 'json-decoding'
        $json = ($raw -join "`n").Trim()
        if ($json.Length -gt 1048576 -or -not $json.StartsWith('[') -or -not $json.EndsWith(']')) {
            throw 'Unexpected queue output.'
        }
        $parsed = ConvertFrom-Json -InputObject $json
        $rows = @($parsed)
        # Windows PowerShell and newer PowerShell differ in array unrolling.
        if ($rows.Count -eq 1 -and $rows[0] -is [array]) { $rows = @($rows[0]) }
        $stage = 'queue-validation'
        $found = @{}
        foreach ($row in $rows) {
            if ($null -eq $row) { continue }
            $names = @($row.PSObject.Properties.Name)
            if ($names -notcontains 'name' -or $row.name -isnot [string]) { throw 'Invalid queue name.' }
            if (-not ($queues -ccontains $row.name)) { continue }
            if ($found.ContainsKey($row.name)) { throw 'Duplicate queue.' }
            foreach ($key in @('messages_ready', 'messages_unacknowledged', 'consumers')) {
                if ($names -notcontains $key) { throw 'Missing counter.' }
                $value = $row.$key
                if (($value -isnot [int] -and $value -isnot [long]) -or $value -lt 0) { throw 'Invalid counter.' }
            }
            if ($names -notcontains 'type' -or @('classic', 'quorum', 'stream') -cnotcontains $row.type) {
                throw 'Invalid queue type.'
            }
            $found[$row.name] = $row
        }
        Write-Host '=== AV queues (counts only) ==='
        foreach ($queue in $queues) {
            if (-not $found.ContainsKey($queue)) {
                Write-Host "${queue}: MISSING OR NOT VISIBLE"
            } else {
                $row = $found[$queue]
                Write-Host "$($row.name): ready=$($row.messages_ready) unacked=$($row.messages_unacknowledged) consumers=$($row.consumers) type=$($row.type)"
            }
        }
        Write-Host 'Read-only snapshot; not consistent and not a deployment or recovery approval.'
        Write-Host 'Do not purge or replay DLQ automatically.'
    } finally { Pop-Location }
} catch {
    # A fixed stage label only: no raw output, credentials, queue bodies or native stderr.
    [Console]::Error.WriteLine("ERROR: AV queue inventory failed at $stage; no changes made.")
    exit 2
}
