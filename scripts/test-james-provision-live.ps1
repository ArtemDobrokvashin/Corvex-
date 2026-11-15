<# One synthetic user INSIDE the existing laptop James. No second Compose
   project, no real Corvex user, no mail migration/cutover. Writes a local
   non-secret intent before one attempt; no automatic retries or deletions. #>
param([switch]$ConfirmLaptop)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Marker = Join-Path $Repo 'infra\secrets\james-provision-live-probe'
$Temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-live-probe-' + [guid]::NewGuid().ToString('N'))
$Remote = '/tmp/corvex-live-probe-' + [guid]::NewGuid().ToString('N')
$Container = $null
if (Test-Path $Marker) { throw 'FAIL: an earlier synthetic intent exists; inspect it, do not automatically retry.' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue) -or -not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'FAIL: Maven and JDK 17 javac required.'
}
function Write-NewAscii([string]$File, [string]$Value) {
    $stream = [IO.File]::Open($File, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $bytes = [Text.Encoding]::ASCII.GetBytes($Value); $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) }
    finally { $stream.Dispose() }
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
}
try {
    Push-Location $Repo
    try {
        $argsCompose = @('compose','--env-file','.env','--env-file','infra/secrets/james/compose.env',
            '--env-file','infra/secrets/james-provision/compose.env','--profile','james-transition','--profile','james-provision')
        foreach ($service in @('mail-storage-service','james','james-admin','james-jmap')) {
            $ids = @(& docker @argsCompose ps --status running -q $service)
            if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or [string]::IsNullOrWhiteSpace($ids[0])) { throw "FAIL: running $service not found; no user created" }
            if ($service -eq 'mail-storage-service') { $Container = $ids[0].Trim() }
            if ($service -eq 'james') { $James = $ids[0].Trim() }
            if ($service -eq 'james-admin') { $Admin = $ids[0].Trim() }
        }
        $state = & docker @argsCompose exec -T mail-storage-service sh -c 'printenv CORVEX_JAMES_PROVISION_ENABLED || true'
        if ($LASTEXITCODE -ne 0 -or ($state -and ("$state".Trim().ToLowerInvariant() -ne 'false'))) {
            throw 'FAIL: running Corvex provisioning API must be disabled; no user created'
        }
        if (-not (Test-Path 'infra/secrets/james-adapter/admin-secret' -PathType Leaf)) { throw 'FAIL: admin secret file missing' }
        New-Item -ItemType Directory -Path $Temp | Out-Null
        $cpFile = Join-Path $Temp 'classpath.txt'
        Push-Location (Join-Path $Repo 'services\mail-storage-service')
        try {
            & mvn -q -B '-DskipTests' test-compile dependency:build-classpath "-Dmdep.outputFile=$cpFile"
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $cpFile)) { throw 'FAIL: compile/classpath preparation; no user created' }
        } finally { Pop-Location }
        $all = ([IO.File]::ReadAllText($cpFile)).Trim().Split([IO.Path]::PathSeparator)
        $patterns = @('^httpclient5-[0-9].*\.jar$', '^httpcore5-[0-9].*\.jar$', '^httpcore5-h2-[0-9].*\.jar$',
                      '^slf4j-api-[0-9].*\.jar$', '^jackson-core-[0-9].*\.jar$',
                      '^jackson-annotations-[0-9].*\.jar$', '^jackson-databind-[0-9].*\.jar$')
        $jars = @()
        foreach ($pattern in $patterns) {
            $matches = @($all | Where-Object { [IO.Path]::GetFileName($_) -match $pattern })
            if ($matches.Count -ne 1 -or -not (Test-Path -LiteralPath $matches[0] -PathType Leaf)) { throw 'FAIL: expected runtime dependency unavailable; no user created' }
            $jars += $matches[0]
        }
        $classes = Join-Path $Repo 'services\mail-storage-service\target\classes'
        $sourceClasses = Join-Path $classes 'ru\corvex\mailstorage\james'
        $out = Join-Path $Temp 'classes'
        New-Item -ItemType Directory -Path $out | Out-Null
        $javaCp = (($jars + @($classes)) -join [IO.Path]::PathSeparator)
        & javac --release 17 -cp $javaCp -d $out (Join-Path $PSScriptRoot 'JamesProvisionLiveProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: synthetic probe compilation; no user created' }
        $package = Join-Path $out 'ru\corvex\mailstorage\james'
        foreach ($pattern in @('JamesMailboxBinding*.class','JamesReadTokenIssuer*.class','JamesJmapReadClient*.class','JamesWebAdminCreateClient*.class')) {
            $files = @(Get-ChildItem -LiteralPath $sourceClasses -Filter $pattern -File)
            if ($files.Count -eq 0) { throw 'FAIL: compiled adapter class missing; no user created' }
            foreach ($file in $files) { Copy-Item -LiteralPath $file.FullName -Destination $package }
        }
        # This is a synthetic throwaway password, not a Corvex or WebAdmin secret.
        $testPassword = New-Secret
        $passwordFile = Join-Path $Temp 'synthetic-password'
        Write-NewAscii $passwordFile $testPassword
        $login = 'cvx-' + [guid]::NewGuid().ToString('N') + '@localhost'
        $adminSecret = [IO.File]::ReadAllText((Join-Path $Repo 'infra\secrets\james-adapter\admin-secret')).Trim()
        if ($adminSecret -notmatch '^[A-Za-z0-9_-]{32,256}$') { throw 'FAIL: invalid admin secret configuration' }
        & docker exec $Container mkdir -p "$Remote/lib"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: temporary container directory; no user created' }
        & docker cp (Join-Path $out 'ru') "${Container}:${Remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy adapter classes; no user created' }
        foreach ($jar in $jars) {
            & docker cp $jar "${Container}:${Remote}/lib/"
            if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy runtime dependency; no user created' }
        }
        & docker cp $passwordFile "${Container}:${Remote}/synthetic-password"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy synthetic password; no user created' }
        # CreateNew and durable marker precede the ONLY external create attempt.
        New-Item -ItemType Directory -Path $Marker | Out-Null
        Write-NewAscii (Join-Path $Marker 'intent.txt') ("synthetic-login=$login`nstatus=attempt-may-have-been-made`n")
        Write-Host 'Starting one synthetic account creation; intent recorded locally, no automatic retry.'
        & docker exec $Container java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesProvisionLiveProbe $login "$Remote/synthetic-password"
        $probeExit = $LASTEXITCODE
        # Check logs locally without printing their contents or either secret.
        foreach ($id in @($James, $Admin)) {
            $stdout = Join-Path $Temp ("log-" + $id + '-out.txt')
            $stderr = Join-Path $Temp ("log-" + $id + '-err.txt')
            $proc = Start-Process -FilePath 'docker' -ArgumentList @('logs',$id) -Wait -NoNewWindow -PassThru `
                -RedirectStandardOutput $stdout -RedirectStandardError $stderr
            if ($proc.ExitCode -ne 0) { throw 'FAIL: could not inspect retained container logs; preserve intent and do not retry' }
            $logs = [IO.File]::ReadAllText($stdout) + [IO.File]::ReadAllText($stderr)
            if ($logs.Contains($testPassword) -or $logs.Contains($adminSecret)) {
                throw 'FAIL: a credential appeared in retained container logs; do not print logs, do not retry, rotate credentials'
            }
            $logs = $null
        }
        Write-Host 'PASS: synthetic password and WebAdmin secret absent from inspected retained James/admin container logs.'
        if ($probeExit -ne 0) { throw 'FAIL: synthetic account/JMAP outcome uncertain; preserve intent and do not retry' }
        Write-NewAscii (Join-Path $Marker 'completed.txt') "acknowledged-and-jmap-verified`n"
        Write-Host 'PASS: one synthetic user is retained inside existing James; no real user or mail was switched.'
    } finally { Pop-Location }
} finally {
    if ($Container -and $Remote -match '^/tmp/corvex-live-probe-[0-9a-f]{32}$') {
        # Only this script-generated temporary path; no user data, volumes or containers.
        & docker exec -u 0 $Container sh -c 'find "$1" -depth -delete' sh $Remote
    }
    if (Test-Path $Temp) { Remove-Item -LiteralPath $Temp -Recurse -Force }
    $testPassword = $null
    $adminSecret = $null
}
