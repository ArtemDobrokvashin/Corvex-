<# Read-only readiness inventory for the live mail AV path.

   Prints container states, the enabled/disabled switches and the queue counters
   that decide whether an EICAR/outage acceptance run is meaningful. It does not
   start, stop or change anything, does not read message bodies, variables or
   user data. #>
param(
    [string]$ComposeDir = '.',
    [switch]$ConfirmLaptop
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path -LiteralPath $ComposeDir).Path
foreach ($required in @('docker-compose.yml', '.env')) {
    if (-not (Test-Path -LiteralPath (Join-Path $Repo $required) -PathType Leaf)) { throw "FAIL: missing $required in $Repo" }
}
function Invoke-Capture([scriptblock]$Command) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return , @(& $Command 2>&1) } finally { $ErrorActionPreference = $saved }
}
$Compose = @('compose', '--env-file', '.env')
Push-Location $Repo
try {
    $project = (& docker compose --env-file .env config --format json | ConvertFrom-Json).name
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($project)) { throw 'FAIL: cannot read the Compose project name.' }
    Write-Host "Project: $project"
    $services = @('mail-smtp-service', 'mail-antivirus-service', 'mail-spam-service', 'mail-storage-service', 'rabbitmq', 'clamav')
    $running = @(& docker @Compose ps --status running -q)
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: cannot list running containers.' }
    foreach ($name in $services) {
        $id = @(& docker @Compose ps -q $name)
        if ($LASTEXITCODE -ne 0 -or $id.Count -eq 0 -or [string]::IsNullOrWhiteSpace($id[0])) {
            Write-Host ("{0,-24} absent" -f $name)
            continue
        }
        $state = (& docker inspect --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $id[0]).Trim()
        Write-Host ("{0,-24} {1}" -f $name, $state)
    }
    $smtpId = @(& docker @Compose ps -q mail-smtp-service)
    if ($smtpId.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($smtpId[0])) {
        $flag = (Invoke-Capture { docker exec $smtpId[0] sh -c 'printenv CORVEX_SMTP_ENABLED || true' }) -join ''
        Write-Host ("CORVEX_SMTP_ENABLED   {0}" -f $(if ($flag.Trim()) { $flag.Trim() } else { 'unset' }))
    }
    $rabbit = @(& docker @Compose ps -q rabbitmq)
    if ($rabbit.Count -eq 1 -and -not [string]::IsNullOrWhiteSpace($rabbit[0])) {
        $queues = Invoke-Capture { docker exec $rabbit[0] rabbitmqctl -q list_queues name messages_ready messages_unacknowledged }
        if ($LASTEXITCODE -ne 0) { Write-Host 'Queues: unavailable' }
        else {
            Write-Host 'Queues (name ready unacked):'
            foreach ($line in $queues) {
                $value = "$line".Trim()
                if ($value -and $value -notmatch '^(Listing|Timeout|name)') { Write-Host ("  {0}" -f $value) }
            }
        }
    }
    Write-Host 'Read-only inventory finished; nothing was changed.'
} finally { Pop-Location }
