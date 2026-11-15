<# Read-only James startup evidence and separate-JVM real ClamAV probe. No mail sent. #>
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$DeploymentManifest, [string]$ComposeDir = (Get-Location).Path, [switch]$ConfirmLaptop)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'manifest'
$remote = '/tmp/corvex-james-av-live-' + [guid]::NewGuid().ToString('N')
$temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-james-av-probe-' + [guid]::NewGuid().ToString('N'))
$ownedRemote = $false
$ownedTemp = $false
$container = $null
try {
    $m = Get-Content -Raw -LiteralPath $DeploymentManifest | ConvertFrom-Json
    $p = Get-Content -Raw -LiteralPath $m.preparationManifest | ConvertFrom-Json
    if ($m.network -match '^corvex-james-av-([a-f0-9]{32})$') { $suffix = $Matches[1] }
    else { throw 'Invalid deployment network.' }
    if ($m.jamesImageId -notmatch '^sha256:[a-f0-9]{64}$' -or $p.candidateJarSha256 -notmatch '^[a-f0-9]{64}$' -or
        $p.parentJarSha256 -notmatch '^[a-f0-9]{64}$' -or $p.candidateXmlSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Invalid deployment manifest.' }
    $source = Join-Path $PSScriptRoot 'src/JamesAvLiveProbe.java'
    if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or -not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'Probe source or javac missing.' }
    Push-Location -LiteralPath $ComposeDir
    try {
        $stage = 'container'
        $ids = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Expected running James.' }
        $container = $ids[0]
        $image = @(& docker inspect --format '{{.Image}}' $container 2>$null)
        if ($LASTEXITCODE -ne 0 -or $image.Count -ne 1 -or $image[0] -cne $m.jamesImageId) { throw 'James image changed.' }
        $stage = 'mounted-artifacts'
        $checks = @(
            @('/root/conf/mailetcontainer.xml', $p.candidateXmlSha256),
            @(('/root/extensions-jars/corvex-av-gate-' + $suffix + '.jar'), $p.candidateJarSha256),
            @(('/root/extensions-jars/corvex-av-parent-' + $suffix + '.jar'), $p.parentJarSha256)
        )
        foreach ($check in $checks) {
            $path = $check[0]
            $hash = @(& docker exec $container sha256sum $path 2>$null)
            if ($LASTEXITCODE -ne 0 -or $hash.Count -ne 1 -or $hash[0] -notmatch '^([a-f0-9]{64})\s' -or $Matches[1] -cne $check[1]) { throw 'Mounted artifact missing or changed.' }
        }
        Write-Host 'PASS: running James has the candidate XML and both exact extension jars mounted.'
        $stage = 'james-readiness'
        $ready = $false
        for ($i = 0; $i -lt 40; $i++) {
            $saved = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try { $null = @(& docker exec $container james-cli ListDomains 2>$null); $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
            if ($code -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 3
        }
        if (-not $ready) { throw 'James CLI did not become ready within 120 seconds.' }
        Write-Host 'PASS: James CLI responded (domain names withheld).'
        $stage = 'running-mailet-log'
        $started = @(& docker inspect --format '{{.State.StartedAt}}' $container 2>$null)
        if ($LASTEXITCODE -ne 0 -or $started.Count -ne 1 -or $started[0] -notmatch '^\d{4}-\d\d-\d\dT') { throw 'Container start timestamp unavailable.' }
        $startedAt = $started[0]
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            # Never print raw logs: they can include addresses and other sensitive data.
            $logs = @(& docker logs --since $startedAt --tail 5000 $container 2>&1)
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $saved }
        if ($code -ne 0) { Write-Host 'INCONCLUSIVE: James startup log could not be read.' }
        else {
            $found = $false
            foreach ($line in $logs) {
                if (([string]$line).Contains('Mailet ru.corvex.james.av.CorvexClamAVGate instantiated.')) { $found = $true; break }
            }
            if ($found) { Write-Host 'PASS: this James container logged candidate mailet instantiation.' }
            else { Write-Host 'INCONCLUSIVE: candidate mailet instantiation log not found; do not assume running gate.' }
        }
        $stage = 'synthetic-probe'
        if (Test-Path -LiteralPath $temp) { throw 'Temporary host directory already exists.' }
        New-Item -ItemType Directory -Path $temp | Out-Null
        $ownedTemp = $true
        & javac --release 17 -d $temp $source
        if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed.' }
        & docker exec $container mkdir $remote
        if ($LASTEXITCODE -ne 0) { throw 'Temporary container directory creation failed.' }
        $ownedRemote = $true
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & docker cp (Join-Path $temp 'JamesAvLiveProbe.class') "${container}:${remote}/"; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
        if ($code -ne 0) { throw 'Probe copy failed.' }
        $cp = "$remote`:/root/extensions-jars/*`:/root/resources`:/root/classes`:/root/libs/*"
        & docker exec $container java -cp $cp JamesAvLiveProbe
        if ($LASTEXITCODE -ne 0) { throw 'Separate-JVM real ClamAV probe failed.' }
        Write-Host 'No message, account, queue, network or configuration was changed.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR: James AV live check failed at $stage. No mail was sent; do not replay queues.")
    throw
} finally {
    if ($ownedRemote -and $container -and $remote -match '^/tmp/corvex-james-av-live-[a-f0-9]{32}$') {
        try { & docker exec $container find $remote -depth -delete; if ($LASTEXITCODE -ne 0) { Write-Warning 'Temporary container cleanup failed.' } }
        catch { Write-Warning 'Temporary container cleanup failed.' }
    }
    if ($ownedTemp -and (Test-Path -LiteralPath $temp)) {
        try { Remove-Item -LiteralPath $temp -Recurse -Force } catch { Write-Warning 'Temporary host cleanup failed.' }
    }
}
