<# Synthetic native James SMTP in the SAME existing Compose project only.
   Recreates only transition James/JMAP/admin twice, with a loopback SMTP
   candidate during the probe, then restores the disabled SMTP config.
   No actual Corvex mailbox, route, published port, key or DNS is changed.
   Persistent local marker prevents automatic repeat after any send attempt. #>
param([switch]$ConfirmLaptop)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Prior = Join-Path $Repo 'infra\secrets\james-provision-live-probe'
$Marker = Join-Path $Repo 'infra\secrets\james-native-smtp-probe'
$Temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-native-smtp-' + [guid]::NewGuid().ToString('N'))
$Remote = '/tmp/corvex-native-smtp-' + [guid]::NewGuid().ToString('N')
$OldSmtp = $env:CORVEX_JAMES_SMTP_CONFIG_FILE
$OldWeb = $env:CORVEX_JAMES_WEBADMIN_FILE
$OldProvision = $env:CORVEX_JAMES_PROVISION_ENABLED
$Changed = $false
$RestoreOk = $true
$RestartReadOk = $true
$Observed = $false
$James = $null
$Mail = $null
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
if (Test-Path -LiteralPath $Marker) { throw 'FAIL: a native probe intent already exists; do not repeat automatically.' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue) -or -not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'FAIL: Maven and JDK 17 required.' }
foreach ($file in @('intent.txt', 'completed.txt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $Prior $file) -PathType Leaf)) { throw 'FAIL: earlier synthetic provisioning probe was not completed.' }
}
if (([IO.File]::ReadAllText((Join-Path $Prior 'completed.txt'))).Trim() -ne 'acknowledged-and-jmap-verified') { throw 'FAIL: earlier synthetic probe is not verified.' }
$loginLines = @([IO.File]::ReadAllLines((Join-Path $Prior 'intent.txt')) | Where-Object { $_ -match '^synthetic-login=cvx-[0-9a-f]{32}@localhost$' })
if ($loginLines.Count -ne 1) { throw 'FAIL: invalid synthetic account intent.' }
$Login = $loginLines[0].Substring('synthetic-login='.Length)
try {
    Push-Location $Repo
    try {
        $env:CORVEX_JAMES_WEBADMIN_FILE = './infra/secrets/james-provision/webadmin.properties'
        $env:CORVEX_JAMES_PROVISION_ENABLED = 'false'
        $env:CORVEX_JAMES_SMTP_CONFIG_FILE = './infra/james/protocols/smtpserver.xml'
        $script:Compose = @('compose','--env-file','.env','--env-file','infra/secrets/james/compose.env',
            '--env-file','infra/secrets/james-provision/compose.env','--profile','james-transition','--profile','james-provision')
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
        New-Item -ItemType Directory -Path $Temp | Out-Null
        $cpFile = Join-Path $Temp 'classpath.txt'
        Push-Location (Join-Path $Repo 'services\mail-storage-service')
        try {
            & mvn -q -B '-DskipTests' test-compile dependency:build-classpath "-Dmdep.outputFile=$cpFile"
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cpFile)) { throw 'FAIL: Maven preparation; no James restart.' }
        } finally { Pop-Location }
        $all = ([IO.File]::ReadAllText($cpFile)).Trim().Split([IO.Path]::PathSeparator)
        $patterns = @('^jackson-core-[0-9].*\.jar$', '^jackson-annotations-[0-9].*\.jar$', '^jackson-databind-[0-9].*\.jar$')
        $jars = @()
        foreach ($pattern in $patterns) {
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
        & javac --release 17 -cp (($jars + @($classes)) -join [IO.Path]::PathSeparator) -d $out (Join-Path $PSScriptRoot 'JamesNativeSmtpProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: synthetic Java probe compilation; no James restart.' }
        $package = Join-Path $out 'ru\corvex\mailstorage\james'
        foreach ($pattern in @('JamesMailboxBinding*.class','JamesReadTokenIssuer*.class','JamesJmapReadClient*.class')) {
            $files = @(Get-ChildItem -LiteralPath $sourceClasses -Filter $pattern -File)
            if ($files.Count -eq 0) { throw 'FAIL: compiled class missing; no James restart.' }
            foreach ($file in $files) { Copy-Item -LiteralPath $file.FullName -Destination $package }
        }
        # Files are prepared on the existing mail-storage container before James
        # is restarted; they contain no passwords or token.
        & docker exec $Mail mkdir -p "$Remote/lib"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: probe directory creation.' }
        & docker cp (Join-Path $out 'ru') "${Mail}:${Remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy probe classes.' }
        foreach ($jar in $jars) { & docker cp $jar "${Mail}:${Remote}/lib/"; if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy probe dependency.' } }
        $env:CORVEX_JAMES_SMTP_CONFIG_FILE = './infra/james/candidates/smtp-loopback.xml'
        & docker @Compose config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: candidate Compose config.' }
        # Mark before recreation, including ambiguous CLI responses; finally
        # always attempts to restore the known disabled SMTP configuration.
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
        $ProbeId = [guid]::NewGuid().ToString('N')
        New-Item -ItemType Directory -Path $Marker | Out-Null
        Write-NewAscii (Join-Path $Marker 'intent.txt') ("synthetic-login=$Login`nmarker=$ProbeId`nstatus=send-may-have-been-attempted`n")
        Write-Host 'Starting one local synthetic SMTP send; no automatic retry.'
        & docker exec $James java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesNativeSmtpProbe send $Login $ProbeId
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: send outcome unknown; do not repeat; restoration will be attempted.' }
        & docker exec $Mail java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesNativeSmtpProbe read $Login $ProbeId
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: message not observed; do not resend; restoration will be attempted.' }
        Write-NewAscii (Join-Path $Marker 'completed.txt') "smtp-accepted-and-jmap-observed`n"
        $Observed = $true
        Write-Host 'PASS: one synthetic native SMTP-to-JMAP path; not AV, recovery, production or cutover.'
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
        if ($RestoreOk -and $Observed) {
            # Read only after normal recreation: this checks mailbox persistence,
            # not crash durability of an in-flight SMTP queue.
            try {
                & docker exec $Mail java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesNativeSmtpProbe read $Login $ProbeId
                if ($LASTEXITCODE -ne 0) { $RestartReadOk = $false }
                else {
                    Write-NewAscii (Join-Path $Marker 'restart-verified.txt') "mail-observed-after-restart`n"
                    Write-Host 'PASS: synthetic message still visible after normal James recreation.'
                }
            } catch { $RestartReadOk = $false }
            if (-not $RestartReadOk) { Write-Warning 'FAIL: post-restart read not verified; do not resend.' }
        }
        # Cleanup failure must never prevent restoring the disabled listener.
        if ($James -and (-not $Changed -or -not $RestoreOk) -and $Remote -match '^/tmp/corvex-native-smtp-[0-9a-f]{32}$') {
            try { & docker exec -u 0 $James sh -c 'find "$1" -depth -delete' sh $Remote }
            catch { Write-Warning 'Temporary James probe cleanup failed.' }
        }
        if ($Mail -and $Remote -match '^/tmp/corvex-native-smtp-[0-9a-f]{32}$') {
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
if (-not $RestartReadOk) { throw 'FAIL: post-restart read not verified; do not resend.' }
