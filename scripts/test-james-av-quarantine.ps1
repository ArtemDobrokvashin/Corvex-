<# One synthetic end-to-end AV quarantine check on the existing laptop project.

   Recreates only transition James and its two internal proxies with a
   loopback-only SMTP candidate, sends exactly one message whose attachment is a
   25-level nested ZIP containing the EICAR test string, and proves it never
   reaches the mailbox while the corvex-av-unknown repository grows by one.
   Then restores the disabled SMTP configuration. No published port, no real
   mailbox, no queue replay, no automatic repeat after a send attempt. #>
param(
    [string]$Login,
    # Compose project with running James; markers and .env live here. The probe
    # sources come from this script's own directory.
    [string]$ComposeDir = (Join-Path $PSScriptRoot '..'),
    [switch]$ConfirmLaptop
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path -LiteralPath $ComposeDir).Path
$Prior = Join-Path $Repo 'infra\secrets\james-provision-live-probe'
$Marker = Join-Path $Repo 'infra\secrets\james-av-quarantine-probe'
$Temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-av-quarantine-' + [guid]::NewGuid().ToString('N'))
$Remote = '/tmp/corvex-av-quarantine-' + [guid]::NewGuid().ToString('N')
$OldSmtp = $env:CORVEX_JAMES_SMTP_CONFIG_FILE
$OldWeb = $env:CORVEX_JAMES_WEBADMIN_FILE
$OldProvision = $env:CORVEX_JAMES_PROVISION_ENABLED
$Changed = $false
$RestoreOk = $true
$Observed = $false
$James = $null
$Mail = $null
$Compose = @()
function Write-NewAscii([string]$File, [string]$Value) {
    $stream = [IO.File]::Open($File, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $data = [Text.Encoding]::ASCII.GetBytes($Value); $stream.Write($data, 0, $data.Length); $stream.Flush($true) }
    finally { $stream.Dispose() }
}
function Ready-James {
    for ($i = 0; $i -lt 40; $i++) {
        & cmd.exe /c 'docker compose --env-file .env --env-file infra/secrets/james/compose.env --env-file infra/secrets/james-provision/compose.env --profile james-transition --profile james-provision exec -T james james-cli ListDomains 1>nul 2>nul'
        if ($LASTEXITCODE -eq 0) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}
function Start-Proxies {
    & docker @script:Compose up -d --no-deps --no-build --force-recreate james-jmap james-admin
    if ($LASTEXITCODE -ne 0) { return $false }
    & docker @script:Compose exec -T james-admin nginx -t
    return ($LASTEXITCODE -eq 0)
}
function Running-Id([string]$Service) {
    $ids = @(& docker @script:Compose ps --status running -q $Service)
    if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or [string]::IsNullOrWhiteSpace($ids[0])) { throw "FAIL: $Service is not running" }
    return $ids[0].Trim()
}
function Get-RepositoryCounts {
    # Read-only aggregate: how many stored mails belong to the AV quarantine
    # repository and how many exist in total. No message bodies are read.
    $sql = "select coalesce(sum(case when url like '%corvex-av-unknown%' then 1 else 0 end),0)::text || ',' || count(*)::text from mail_repository_content"
    $out = @(& docker exec (Running-Id 'james-postgres') psql -U james -d james -tAc $sql 2>&1)
    if ($LASTEXITCODE -ne 0) { return $null }
    $value = ("$($out -join '')").Trim()
    if ($value -notmatch '^([0-9]+),([0-9]+)$') { return $null }
    return [pscustomobject]@{ Quarantine = [int]$Matches[1]; Total = [int]$Matches[2] }
}
if (Test-Path -LiteralPath $Marker) { throw 'FAIL: a quarantine-proof intent already exists; do not repeat automatically.' }
foreach ($required in @('docker-compose.yml', '.env', 'infra\secrets\james\compose.env', 'infra\secrets\james-provision\compose.env')) {
    if (-not (Test-Path -LiteralPath (Join-Path $Repo $required) -PathType Leaf)) {
        throw ('FAIL: Compose file is missing in "{0}": {1}' -f $Repo, $required)
    }
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue) -or -not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'FAIL: Maven and JDK 17 required.' }
# The synthetic account comes from the verified provisioning record of the
# earlier JMAP probe; -Login is only an explicit override for the same pattern.
if (-not $Login) {
    $priorIntent = Join-Path $Prior 'intent.txt'
    $priorDone = Join-Path $Prior 'completed.txt'
    if (-not (Test-Path -LiteralPath $priorIntent -PathType Leaf) -or
        -not (Test-Path -LiteralPath $priorDone -PathType Leaf) -or
        ([IO.File]::ReadAllText($priorDone)).Trim() -ne 'acknowledged-and-jmap-verified') {
        throw 'FAIL: verified synthetic provisioning record is missing; pass -Login <cvx-...@localhost> explicitly.'
    }
    $loginLines = @([IO.File]::ReadAllLines($priorIntent) | Where-Object { $_ -match '^synthetic-login=cvx-[0-9a-f]{32}@localhost$' })
    if ($loginLines.Count -ne 1) { throw 'FAIL: cannot determine the synthetic account; pass -Login explicitly.' }
    $Login = $loginLines[0].Substring('synthetic-login='.Length)
}
if ($Login -notmatch '^cvx-[0-9a-f]{32}@localhost$') { throw 'FAIL: only the synthetic cvx-<guid>@localhost account is allowed.' }
Write-Host "Synthetic account: $Login"
try {
    Push-Location $Repo
    try {
        $env:CORVEX_JAMES_WEBADMIN_FILE = './infra/secrets/james-provision/webadmin.properties'
        $env:CORVEX_JAMES_PROVISION_ENABLED = 'false'
        $env:CORVEX_JAMES_SMTP_CONFIG_FILE = './infra/james/protocols/smtpserver.xml'
        $script:Compose = @('compose', '--env-file', '.env', '--env-file', 'infra/secrets/james/compose.env',
            '--env-file', 'infra/secrets/james-provision/compose.env', '--profile', 'james-transition', '--profile', 'james-provision')
        $Mail = Running-Id 'mail-storage-service'
        $James = Running-Id 'james'
        $null = Running-Id 'james-jmap'
        $null = Running-Id 'james-admin'
        $db = Running-Id 'james-postgres'
        $health = & docker inspect --format '{{.State.Health.Status}}' $db
        if ($LASTEXITCODE -ne 0 -or "$health".Trim() -ne 'healthy') { throw 'FAIL: James PostgreSQL is not healthy.' }
        $state = & docker exec $Mail sh -c 'printenv CORVEX_JAMES_PROVISION_ENABLED || true'
        if ($LASTEXITCODE -ne 0 -or ($state -and ("$state".Trim().ToLowerInvariant() -ne 'false'))) { throw 'FAIL: Corvex provisioning API is not disabled.' }
        $smtp = (& docker exec $James cat /root/conf/smtpserver.xml) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $smtp -notmatch '^\s*(<\?xml[^>]*\?>\s*)?<smtpservers\s*/>\s*$') { throw 'FAIL: running SMTP config is not the disabled baseline.' }
        $mailet = (& docker exec $James cat /root/conf/mailetcontainer.xml) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $mailet -notmatch 'CorvexClamAVGate' -or $mailet -notmatch 'corvex-av-unknown') {
            throw 'FAIL: running mailet container has no AV gate or quarantine processor.'
        }
        $baseline = Get-RepositoryCounts
        if (-not $baseline) { throw 'FAIL: cannot read the repository counters; nothing was changed.' }
        Write-Host ("Baseline: quarantine={0} total={1}" -f $baseline.Quarantine, $baseline.Total)
        New-Item -ItemType Directory -Path $Temp | Out-Null
        $cpFile = Join-Path $Temp 'classpath.txt'
        Push-Location (Join-Path $Repo 'services\mail-storage-service')
        try {
            & mvn -q -B '-DskipTests' test-compile dependency:build-classpath "-Dmdep.outputFile=$cpFile"
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cpFile)) { throw 'FAIL: Maven preparation; no James restart.' }
        } finally { Pop-Location }
        $all = ([IO.File]::ReadAllText($cpFile)).Trim().Split([IO.Path]::PathSeparator)
        $jars = @()
        foreach ($pattern in @('^jackson-core-[0-9].*\.jar$', '^jackson-annotations-[0-9].*\.jar$', '^jackson-databind-[0-9].*\.jar$')) {
            $found = @($all | Where-Object { [IO.Path]::GetFileName($_) -match $pattern })
            if ($found.Count -ne 1 -or -not (Test-Path -LiteralPath $found[0] -PathType Leaf)) { throw 'FAIL: expected Jackson dependency unavailable.' }
            $jars += $found[0]
        }
        $mailJars = @($all | Where-Object { [IO.Path]::GetFileName($_) -match '^(jakarta\.mail|jakarta\.mail-api|angus-mail|jakarta\.activation|jakarta\.activation-api|angus-activation)-[0-9].*\.jar$' })
        if (-not ($mailJars | Where-Object { [IO.Path]::GetFileName($_) -match '^(jakarta\.mail|angus-mail)-[0-9]' })) { throw 'FAIL: Jakarta Mail provider unavailable.' }
        foreach ($jar in $mailJars) {
            if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'FAIL: missing mail dependency.' }
            $jars += $jar
        }
        $classes = Join-Path $Repo 'services\mail-storage-service\target\classes'
        $sourceClasses = Join-Path $classes 'ru\corvex\mailstorage\james'
        $out = Join-Path $Temp 'classes'
        New-Item -ItemType Directory -Path $out | Out-Null
        & javac --release 17 -cp (($jars + @($classes)) -join [IO.Path]::PathSeparator) -d $out (Join-Path $PSScriptRoot 'JamesAvQuarantineProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: quarantine probe compilation; no James restart.' }
        $package = Join-Path $out 'ru\corvex\mailstorage\james'
        foreach ($pattern in @('JamesMailboxBinding*.class', 'JamesReadTokenIssuer*.class', 'JamesJmapReadClient*.class')) {
            $files = @(Get-ChildItem -LiteralPath $sourceClasses -Filter $pattern -File)
            if ($files.Count -eq 0) { throw 'FAIL: compiled class missing; no James restart.' }
            foreach ($file in $files) { Copy-Item -LiteralPath $file.FullName -Destination $package }
        }
        & docker exec $Mail mkdir -p "$Remote/lib"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: probe directory creation.' }
        & docker cp (Join-Path $out 'ru') "${Mail}:${Remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy probe classes.' }
        foreach ($jar in $jars) { & docker cp $jar "${Mail}:${Remote}/lib/"; if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy probe dependency.' } }
        # Confirm the synthetic mailbox exists before touching any container.
        & docker exec $Mail java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesAvQuarantineProbe check $Login
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: the synthetic account is not usable; nothing was changed.' }
        $env:CORVEX_JAMES_SMTP_CONFIG_FILE = './infra/james/candidates/smtp-quarantine-proof.xml'
        & docker @Compose config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: candidate Compose config.' }
        # Mark before recreation so an ambiguous outcome can never look like a
        # clean run; the finally block always restores the disabled listener.
        $Changed = $true
        & docker @Compose up -d --no-deps --no-build --force-recreate james
        if ($LASTEXITCODE -ne 0 -or -not (Ready-James)) { throw 'FAIL: James candidate startup; restoration will be attempted.' }
        if (-not (Start-Proxies)) { throw 'FAIL: James proxy startup; restoration will be attempted.' }
        $James = Running-Id 'james'
        & docker exec $James mkdir -p "$Remote/lib"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: James probe directory.' }
        & docker cp (Join-Path $out 'ru') "${James}:${Remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy James probe.' }
        foreach ($jar in $jars) { & docker cp $jar "${James}:${Remote}/lib/"; if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy James probe dependency.' } }
        $logs = (& docker logs --since 5m $James 2>&1) -join "`n"
        if ($logs -notmatch 'CorvexClamAVGate instantiated') { Write-Warning 'Gate instantiation was not observed in recent James logs; the result below still depends on it.' }
        $ProbeId = [guid]::NewGuid().ToString('N')
        New-Item -ItemType Directory -Path $Marker | Out-Null
        Write-NewAscii (Join-Path $Marker 'intent.txt') ("synthetic-login=$Login`nmarker=$ProbeId`nstatus=send-may-have-been-attempted`n")
        Write-Host 'Starting one synthetic nested-archive send; no automatic retry.'
        & docker exec $James java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesAvQuarantineProbe send $Login $ProbeId
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: send outcome unknown; do not repeat; restoration will be attempted.' }
        & docker exec $Mail java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesAvQuarantineProbe absent $Login $ProbeId
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: the message was delivered to the mailbox; do not resend; restoration will be attempted.' }
        $after = Get-RepositoryCounts
        if (-not $after) { throw 'FAIL: cannot read the repository counters after the send.' }
        $quarantineDelta = $after.Quarantine - $baseline.Quarantine
        $totalDelta = $after.Total - $baseline.Total
        Write-Host ("Delta: quarantine={0} total={1}" -f $quarantineDelta, $totalDelta)
        if ($quarantineDelta -ne 1) { throw 'FAIL: exactly one quarantined mail was expected; do not resend.' }
        $Observed = $true
        Write-NewAscii (Join-Path $Marker 'completed.txt') ("quarantined=$quarantineDelta total=$totalDelta`n")
        Write-Host 'PASS: one synthetic nested-archive message was stopped by the AV gate and stored in corvex-av-unknown.'
        Write-Host 'This is not virus-detection proof, complete archive coverage or cutover approval.'
    } finally {
        if ($Changed) {
            Write-Host 'Restoring disabled James SMTP and its two proxies.'
            $env:CORVEX_JAMES_SMTP_CONFIG_FILE = './infra/james/protocols/smtpserver.xml'
            try {
                & docker @Compose up -d --no-deps --no-build --force-recreate james
                if ($LASTEXITCODE -ne 0 -or -not (Ready-James)) { $RestoreOk = $false }
                if ($RestoreOk -and -not (Start-Proxies)) { $RestoreOk = $false }
                if ($RestoreOk) {
                    $restored = Running-Id 'james'
                    $smtp = (& docker exec $restored cat /root/conf/smtpserver.xml) -join "`n"
                    if ($LASTEXITCODE -ne 0 -or $smtp -notmatch '^\s*(<\?xml[^>]*\?>\s*)?<smtpservers\s*/>\s*$') { $RestoreOk = $false }
                }
            } catch { $RestoreOk = $false }
            if ($RestoreOk) { Write-Host 'PASS: disabled SMTP configuration restored.' }
            else { Write-Warning 'FAIL: restoration incomplete; do not retry; inspect container state.' }
        }
        if ($James -and (-not $Changed -or -not $RestoreOk) -and $Remote -match '^/tmp/corvex-av-quarantine-[0-9a-f]{32}$') {
            try { & docker exec -u 0 $James sh -c 'find "$1" -depth -delete' sh $Remote }
            catch { Write-Warning 'Temporary James probe cleanup failed.' }
        }
        if ($Mail -and $Remote -match '^/tmp/corvex-av-quarantine-[0-9a-f]{32}$') {
            try { & docker exec -u 0 $Mail sh -c 'find "$1" -depth -delete' sh $Remote }
            catch { Write-Warning 'Temporary mail-storage probe cleanup failed.' }
        }
        Pop-Location
    }
} finally {
    $env:CORVEX_JAMES_SMTP_CONFIG_FILE = $OldSmtp
    $env:CORVEX_JAMES_WEBADMIN_FILE = $OldWeb
    $env:CORVEX_JAMES_PROVISION_ENABLED = $OldProvision
    if (Test-Path $Temp) { Remove-Item -LiteralPath $Temp -Recurse -Force }
}
if (-not $RestoreOk) { throw 'FAIL: restoration incomplete; do not retry automatically.' }
if (-not $Observed) { throw 'FAIL: quarantine was not proven; see the message above.' }
exit 0
