<# One-shot local-only synthetic JMAP AV test. It intentionally creates a synthetic draft and spool message. #>
[CmdletBinding()]
param([string]$ComposeDir = (Get-Location).Path, [Parameter(Mandatory=$true)][string]$DeploymentManifest, [switch]$ConfirmSynthetic)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Draft only: endpoint routing, network containment and crash-safe journal have not
# been verified on the laptop. Do not remove this guard until reviewed and tested.
throw 'DISABLED: synthetic spool probe is unfinished and must not be run.'
if (-not $ConfirmSynthetic) { throw 'FAIL: -ConfirmSynthetic required.' }
$stage = 'initialization'
$marker = $null
$storage = $null
$james = $null
$network = $null
$remote = $null
$networkTouched = $false
$networkInitiallyAbsent = $false
$temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-james-spool-build-' + [guid]::NewGuid().ToString('N'))
function Write-NewAscii([string]$Path, [string]$Content) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $bytes = [Text.Encoding]::ASCII.GetBytes($Content); $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) }
    finally { $stream.Dispose() }
}
function Is-NetworkAttached([string]$Container, [string]$Network) {
    $json = @(& docker inspect --format '{{json .NetworkSettings.Networks}}' $Container 2>$null)
    if ($LASTEXITCODE -ne 0 -or $json.Count -ne 1) { throw 'Cannot inspect network attachment.' }
    $nets = $json[0] | ConvertFrom-Json
    return ($null -ne $nets.PSObject.Properties[$Network])
}
try {
    foreach ($command in @('docker','mvn','javac')) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required command unavailable: $command" }
    }
    $m = Get-Content -Raw -LiteralPath $DeploymentManifest | ConvertFrom-Json
    $prepared = Get-Content -Raw -LiteralPath $m.preparationManifest | ConvertFrom-Json
    if ($m.network -notmatch '^corvex-james-av-[a-f0-9]{32}$' -or $m.jamesImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $prepared.candidateXmlSha256 -notmatch '^[a-f0-9]{64}$') { throw 'Invalid deployment manifest.' }
    $network = $m.network
    $sourceRoot = Split-Path $PSScriptRoot -Parent
    $sourceFiles = @(
        (Join-Path $PSScriptRoot 'JamesAvSpoolProbe.java'),
        (Join-Path $PSScriptRoot 'JamesAvRepositoryProbe.java'),
        (Join-Path $sourceRoot 'services/mail-storage-service/src/main/java/ru/corvex/mailstorage/james/JamesMailboxBinding.java'),
        (Join-Path $sourceRoot 'services/mail-storage-service/src/main/java/ru/corvex/mailstorage/james/JamesReadTokenIssuer.java')
    )
    foreach ($file in $sourceFiles) { if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Probe source missing.' } }
    Push-Location -LiteralPath $ComposeDir
    try {
        $root = (Get-Location).Path
        $marker = Join-Path $root 'infra/secrets/james-av-spool-probe'
        if (Test-Path -LiteralPath $marker) { throw 'One-shot marker exists; do not repeat or delete automatically.' }
        $prior = Join-Path $root 'infra/secrets/james-provision-live-probe'
        if (-not (Test-Path -LiteralPath (Join-Path $prior 'intent.txt') -PathType Leaf) -or
            -not (Test-Path -LiteralPath (Join-Path $prior 'completed.txt') -PathType Leaf) -or
            ([IO.File]::ReadAllText((Join-Path $prior 'completed.txt'))).Trim() -cne 'acknowledged-and-jmap-verified') { throw 'Verified synthetic identity marker missing.' }
        $loginLines = @([IO.File]::ReadAllLines((Join-Path $prior 'intent.txt')) | Where-Object { $_ -cmatch '^synthetic-login=cvx-[0-9a-f]{32}@localhost$' })
        if ($loginLines.Count -ne 1) { throw 'Synthetic identity marker invalid.' }
        $login = $loginLines[0].Substring('synthetic-login='.Length)
        $stage = 'running-services'
        $jamesIds = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $jamesIds.Count -ne 1 -or $jamesIds[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Running James unavailable.' }
        $james = $jamesIds[0]
        $storageIds = @(& docker compose ps --status running -q mail-storage-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $storageIds.Count -ne 1 -or $storageIds[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Running mail-storage unavailable.' }
        $storage = $storageIds[0]
        $infoRaw = @(& docker inspect --format '{{json .}}' $james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $infoRaw.Count -ne 1) { throw 'Cannot inspect James.' }
        $info = $infoRaw[0] | ConvertFrom-Json
        if ($info.Image -cne $m.jamesImageId -or $info.State.Status -cne 'running') { throw 'James image or state changed.' }
        $netProps = @($info.NetworkSettings.Networks.PSObject.Properties)
        if ($netProps.Count -eq 0 -or $null -eq $info.NetworkSettings.Networks.PSObject.Properties[$network]) { throw 'James AV network unavailable.' }
        foreach ($property in $netProps) {
            if ($property.Name -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Unexpected network name.' }
            $internal = @(& docker network inspect --format '{{.Internal}}' $property.Name 2>$null)
            if ($LASTEXITCODE -ne 0 -or $internal.Count -ne 1 -or $internal[0] -cne 'true') { throw 'James has a non-internal network; local-only probe refused.' }
        }
        $ip = $info.NetworkSettings.Networks.PSObject.Properties[$network].Value.IPAddress
        $parsedIp = $null
        if ($ip -notmatch '^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$' -or -not [Net.IPAddress]::TryParse($ip, [ref]$parsedIp) -or
            $parsedIp.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'James private IPv4 unavailable.' }
        if (Is-NetworkAttached $storage $network) { throw 'Storage already connected to AV network; preserve existing network state.' }
        $networkInitiallyAbsent = $true
        $domainLines = @(& docker exec $james james-cli ListDomains 2>$null)
        if ($LASTEXITCODE -ne 0 -or @($domainLines | Where-Object { $_.Trim() -ceq 'localhost' }).Count -ne 1) { throw 'Synthetic localhost domain not confirmed.' }
        $xmlHash = @(& docker exec $james sha256sum /root/conf/mailetcontainer.xml 2>$null)
        if ($LASTEXITCODE -ne 0 -or $xmlHash.Count -ne 1 -or $xmlHash[0] -notmatch '^([a-f0-9]{64})\s' -or $Matches[1] -cne $prepared.candidateXmlSha256) { throw 'Candidate mailet config changed.' }
        $stage = 'compile'
        if (Test-Path -LiteralPath $temp) { throw 'Temporary build directory exists.' }
        New-Item -ItemType Directory -Path $temp | Out-Null
        $classpathFile = Join-Path $temp 'classpath.txt'
        & mvn -q -B -f (Join-Path $root 'services/mail-storage-service/pom.xml') dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile -PathType Leaf)) { throw 'Maven classpath preparation failed.' }
        $all = ([IO.File]::ReadAllText($classpathFile)).Trim().Split([IO.Path]::PathSeparator)
        $jars = @()
        foreach ($pattern in @('^jackson-core-[0-9].*\.jar$', '^jackson-annotations-[0-9].*\.jar$', '^jackson-databind-[0-9].*\.jar$')) {
            $found = @($all | Where-Object { [IO.Path]::GetFileName($_) -match $pattern })
            if ($found.Count -ne 1 -or -not (Test-Path -LiteralPath $found[0] -PathType Leaf)) { throw 'Jackson dependency unavailable.' }
            $jars += $found[0]
        }
        $mailJars = @($all | Where-Object { [IO.Path]::GetFileName($_) -match '^(jakarta\.mail|jakarta\.mail-api|angus-mail|jakarta\.activation|jakarta\.activation-api|angus-activation)-[0-9].*\.jar$' })
        if ($mailJars.Count -lt 2) { throw 'Jakarta mail dependencies unavailable.' }
        foreach ($jar in $mailJars) { if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Mail dependency missing.' } }
        $jars += $mailJars
        $classes = Join-Path $temp 'classes'
        New-Item -ItemType Directory -Path $classes | Out-Null
        & javac --release 17 -cp ($jars -join [IO.Path]::PathSeparator) -d $classes @sourceFiles
        if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed; no mail sent.' }
        $stage = 'one-shot-intent'
        $runId = [guid]::NewGuid().ToString('N')
        $remote = '/tmp/corvex-james-spool-' + $runId
        New-Item -ItemType Directory -Path $marker | Out-Null
        Write-NewAscii (Join-Path $marker 'intent.txt') "run-id=$runId`nsynthetic-login=$login`nnetwork=$network`nstorage-container=$storage`njames-container=$james`nremote=$remote`n"
        Write-Host "One-shot marker: $marker"
        Write-Host 'Do not rerun, delete the marker or replay mail if the test is interrupted.'
        $stage = 'prepare-container-probes'
        & docker exec $storage mkdir $remote
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create storage probe directory.' }
        & docker exec $storage mkdir "$remote/lib"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create storage probe library directory.' }
        & docker exec $james mkdir $remote
        if ($LASTEXITCODE -ne 0) { throw 'Cannot create James probe directory.' }
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & docker cp (Join-Path $classes 'ru') "${storage}:${remote}/"; if ($LASTEXITCODE -ne 0) { throw 'Cannot copy JMAP classes.' }
            & docker cp (Join-Path $classes 'JamesAvRepositoryProbe.class') "${james}:${remote}/"; if ($LASTEXITCODE -ne 0) { throw 'Cannot copy repository class.' }
            foreach ($jar in $jars) { & docker cp $jar "${storage}:${remote}/lib/"; if ($LASTEXITCODE -ne 0) { throw 'Cannot copy dependency.' } }
        } finally { $ErrorActionPreference = $saved }
        $jamesCp = "$remote`:/root/resources`:/root/classes`:/root/libs/*"
        & docker exec $james java -cp $jamesCp JamesAvRepositoryProbe preflight
        if ($LASTEXITCODE -ne 0) { throw 'Repository read-only preflight failed; no mail submitted.' }
        $stage = 'temporary-network-and-submission'
        # Connect only after durable intent; direct access is removed in finally.
        $networkTouched = $true
        & docker network connect $network $storage
        if ($LASTEXITCODE -ne 0) { throw 'Temporary network connection failed.' }
        if (-not (Is-NetworkAttached $storage $network)) { throw 'Temporary network connection not visible.' }
        $cp = "$remote`:$remote/lib/*"
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & docker exec $storage java '-Djdk.httpclient.disableRetryConnect=true' '-Djdk.httpclient.enableAllMethodRetry=false' -cp $cp ru.corvex.mailstorage.james.JamesAvSpoolProbe $login $runId "$remote/journal.txt" $ip
            $probeCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $saved }
        $stage = 'journal'
        # Journal contains synthetic opaque IDs only, never tokens or credentials.
        $journal = Join-Path $marker 'journal.txt'
        if (Test-Path -LiteralPath $journal) { throw 'Journal destination already exists.' }
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & docker cp "${storage}:${remote}/journal.txt" $journal; $copyCode = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
        if ($copyCode -ne 0 -or -not (Test-Path -LiteralPath $journal -PathType Leaf)) { throw 'Journal could not be preserved; do not retry.' }
        if ($probeCode -ne 0) { throw 'JMAP probe failed or was uncertain; do not retry.' }
        $records = @([IO.File]::ReadAllLines($journal) | Where-Object { $_ -match '^submission acknowledged [A-Za-z0-9._~+/=-]{1,1024}$' })
        if ($records.Count -ne 1) { throw 'Submission acknowledgement unavailable; do not retry.' }
        $submissionId = $records[0].Substring('submission acknowledged '.Length)
        $stage = 'quarantine-observation'
        & docker exec $james java -cp $jamesCp JamesAvRepositoryProbe observe $submissionId $login
        if ($LASTEXITCODE -ne 0) { throw 'Quarantine not confirmed; do not resend.' }
        Write-NewAscii (Join-Path $marker 'completed.txt') "infected-repository-observed`n"
        Write-Host 'PASS: one synthetic local JMAP submission was held in the infected repository.'
        Write-Host 'Synthetic draft, quarantine entry, journal and marker are retained; no mail was deleted or replayed.'
        Write-Host 'This is not proof of mailbox non-delivery, outage/crash recovery, or archive completeness.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR at $stage. One-shot marker (if created): $marker")
    [Console]::Error.WriteLine('Do not repeat, purge, replay, delete markers or restore automatically.')
    throw
} finally {
    if ($networkTouched -and $networkInitiallyAbsent -and $storage -and $network) {
        try {
            if (Is-NetworkAttached $storage $network) {
                & docker network disconnect $network $storage
                if ($LASTEXITCODE -ne 0 -or (Is-NetworkAttached $storage $network)) { Write-Warning 'Temporary network disconnect failed; inspect before further work.' }
                else { Write-Host 'Temporary storage-to-James network connection removed.' }
            }
        } catch { Write-Warning 'Temporary network state could not be verified; inspect before further work.' }
    }
    Write-Host "Probe build artifacts retained: $temp"
}
