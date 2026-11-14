<# Guarded check using ONLY existing Corvex containers and a pre-existing synthetic
   James account. Recreates james-jmap, never James, ClamAV, a volume or real mail.
   Restores the previous Compose configuration even on a failed check. No retry. #>
param([switch]$ConfirmLaptop, [string]$ComposeDir = (Get-Location).Path)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path -LiteralPath $ComposeDir).Path
$SourceRoot = Split-Path $PSScriptRoot -Parent
$Marker = Join-Path $Repo 'infra\secrets\james-jmap-gateway-probe'
$Prior = Join-Path $Repo 'infra\secrets\james-provision-live-probe'
$Temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-jmap-guard-' + [guid]::NewGuid().ToString('N'))
$Remote = '/tmp/corvex-jmap-guard-' + [guid]::NewGuid().ToString('N')
$Tag = 'corvex-jmap-read-probe:' + [guid]::NewGuid().ToString('N')
$Changed = $false
$RestoreOk = $true
$Container = $null
$OwnedRemote = $false
foreach ($file in @('docker-compose.yml','.env','infra\secrets\james\compose.env','infra\secrets\james-provision\compose.env')) {
    if (-not (Test-Path -LiteralPath (Join-Path $Repo $file) -PathType Leaf)) { throw "FAIL: missing required Compose file: $file" }
}
foreach ($cmd in @('docker','mvn','javac')) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "FAIL: $cmd unavailable." }
}
if (Test-Path -LiteralPath $Marker) { throw 'FAIL: prior gateway probe intent exists; inspect it, do not repeat automatically.' }
if (-not (Test-Path -LiteralPath (Join-Path $Prior 'completed.txt') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $Prior 'intent.txt') -PathType Leaf) -or
    ([IO.File]::ReadAllText((Join-Path $Prior 'completed.txt'))).Trim() -ne 'acknowledged-and-jmap-verified') {
    throw 'FAIL: verified synthetic provisioning record required.'
}
$lines = @([IO.File]::ReadAllLines((Join-Path $Prior 'intent.txt')) | Where-Object { $_ -match '^synthetic-login=cvx-[0-9a-f]{32}@localhost$' })
if ($lines.Count -ne 1) { throw 'FAIL: invalid synthetic account marker.' }
$Login = $lines[0].Substring('synthetic-login='.Length)
function Id([string]$Service) {
    $list = @(& docker @script:Base ps --status running -q $Service)
    if ($LASTEXITCODE -ne 0 -or $list.Count -ne 1 -or $list[0] -notmatch '^[0-9a-f]{12,64}$') { throw "FAIL: expected one running $Service." }
    return $list[0].Trim()
}
function Probe([string]$Mode) {
    if ($Mode -eq 'tls') {
        $output = @(& docker exec $Container java -cp "$Remote`:$Remote/lib/*" JamesTransitionTlsProbe)
    } elseif ($Mode -eq 'read-only') {
        $output = @(& docker exec $Container java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesJmapGatewayLiveProbe $Login 'read-only')
    } else {
        $output = @(& docker exec $Container java -cp "$Remote`:$Remote/lib/*" ru.corvex.mailstorage.james.JamesJmapGatewayLiveProbe $Login)
    }
    $ok = $LASTEXITCODE -eq 0
    foreach ($line in $output) { Write-Host $line }
    return $ok
}
try {
    Push-Location $Repo
    try {
        $script:Base = @('compose','-f','docker-compose.yml','--env-file','.env','--env-file','infra/secrets/james/compose.env',
            '--env-file','infra/secrets/james-provision/compose.env','--profile','james-transition','--profile','james-provision')
        $Container = Id 'mail-storage-service'
        $null = Id 'james'
        $Jmap = Id 'james-jmap'
        $image = (& docker inspect --format '{{.Config.Image}}' $Jmap)
        if ($LASTEXITCODE -ne 0 -or "$image".Trim() -ne 'nginx:1.31-alpine') { throw 'FAIL: running JMAP proxy is not the expected nginx baseline.' }
        $actualHash = (& docker inspect --format '{{index .Config.Labels "com.docker.compose.config-hash"}}' $Jmap)
        $expected = @(& docker @Base config --hash james-jmap)
        if ($LASTEXITCODE -ne 0 -or $expected.Count -ne 1) { throw 'FAIL: could not determine baseline Compose hash.' }
        if ($expected[0] -match '^james-jmap\s+([0-9a-f]{64})\s*$') { $expectedHash = $Matches[1] }
        else { throw 'FAIL: unexpected baseline Compose hash output.' }
        if ("$actualHash".Trim() -ne $expectedHash) { throw 'FAIL: running proxy differs from current Compose baseline; no recreation.' }
        New-Item -ItemType Directory -Path $Temp | Out-Null
        $cpFile = Join-Path $Temp 'classpath.txt'
        & mvn -q -B -f (Join-Path $Repo 'services\mail-storage-service\pom.xml') dependency:build-classpath "-Dmdep.outputFile=$cpFile"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $cpFile -PathType Leaf)) { throw 'FAIL: Maven classpath preparation; no recreation.' }
        $all = ([IO.File]::ReadAllText($cpFile)).Trim().Split([IO.Path]::PathSeparator)
        $jars = @()
        foreach ($pattern in @('^jackson-core-[0-9].*\.jar$', '^jackson-annotations-[0-9].*\.jar$', '^jackson-databind-[0-9].*\.jar$')) {
            $found = @($all | Where-Object { [IO.Path]::GetFileName($_) -match $pattern })
            if ($found.Count -ne 1 -or -not (Test-Path -LiteralPath $found[0] -PathType Leaf)) { throw 'FAIL: expected Jackson jar unavailable.' }
            $jars += $found[0]
        }
        $out = Join-Path $Temp 'classes'
        New-Item -ItemType Directory -Path $out | Out-Null
        $package = Join-Path $SourceRoot 'services\mail-storage-service\src\main\java\ru\corvex\mailstorage\james'
        $sources = @((Join-Path $package 'JamesMailboxBinding.java'), (Join-Path $package 'JamesReadTokenIssuer.java'),
            (Join-Path $package 'JamesJmapReadClient.java'), (Join-Path $PSScriptRoot 'JamesJmapGatewayLiveProbe.java'),
            (Join-Path $PSScriptRoot 'JamesTransitionTlsProbe.java'))
        foreach ($source in $sources) { if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'FAIL: probe source missing.' } }
        & javac --release 17 -cp ($jars -join [IO.Path]::PathSeparator) -d $out @sources
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: probe compilation; no recreation.' }
        $context = Join-Path $SourceRoot 'infra\james\jmap-read-gateway'
        & docker build -t $Tag $context
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: gateway image build/tests; no recreation.' }
        & docker exec $Container mkdir $Remote
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: probe directory; no recreation.' }
        $OwnedRemote = $true
        & docker exec $Container mkdir "$Remote/lib"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: probe library directory.' }
        & docker cp (Join-Path $out 'ru') "${Container}:${Remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy probe classes.' }
        foreach ($class in @('JamesTransitionTlsProbe.class','JamesTransitionTlsProbe$1.class')) {
            & docker cp (Join-Path $out $class) "${Container}:${Remote}/"
            if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy TLS probe.' }
        }
        foreach ($jar in $jars) { & docker cp $jar "${Container}:${Remote}/lib/"; if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy Jackson dependency.' } }
        $overlay = Join-Path $Temp 'candidate.yml'
        [IO.File]::WriteAllText($overlay, "services:`n  james-jmap:`n    image: $Tag`n    read_only: true`n    cap_drop: [ALL]`n    security_opt:`n      - no-new-privileges:true`n", [Text.Encoding]::ASCII)
        $candidate = @('compose','-f','docker-compose.yml','-f',$overlay,'--env-file','.env',
            '--env-file','infra/secrets/james/compose.env','--env-file','infra/secrets/james-provision/compose.env',
            '--profile','james-transition','--profile','james-provision')
        & docker @candidate config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: candidate Compose validation; no recreation.' }
        # Persistent local intent records even an ambiguous Docker failure.
        New-Item -ItemType Directory -Path $Marker | Out-Null
        $intent = Join-Path $Marker 'intent.txt'
        $stream = [IO.File]::Open($intent, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $bytes = [Text.Encoding]::ASCII.GetBytes("proxy-recreation-may-have-started`n"); $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
        $Changed = $true
        & docker @candidate up -d --no-deps --no-build --force-recreate james-jmap
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: candidate startup; baseline restoration will be attempted.' }
        $candidateId = Id 'james-jmap'
        $candidateImage = (& docker inspect --format '{{.Config.Image}}' $candidateId)
        if ($LASTEXITCODE -ne 0 -or "$candidateImage".Trim() -ne $Tag) { throw 'FAIL: expected candidate image is not running.' }
        Start-Sleep -Seconds 2
        if (-not (Probe 'tls')) { throw 'FAIL: candidate TLS probe; baseline restoration will be attempted.' }
        if (-not (Probe 'gateway')) { throw 'FAIL: candidate authenticated/read-only probe; baseline restoration will be attempted.' }
        Write-Host 'PASS: real synthetic account read and invalid writes rejected by candidate; not a mail AV or cutover test.'
    } finally {
        if ($Changed) {
            Write-Host 'Restoring the prior nginx JMAP proxy; no other services are recreated.'
            try {
                & docker @Base up -d --no-deps --no-build --force-recreate james-jmap
                if ($LASTEXITCODE -ne 0) { $RestoreOk = $false }
                if ($RestoreOk) { Start-Sleep -Seconds 2 }
                if ($RestoreOk) {
                    $restored = Id 'james-jmap'
                    $restoredHash = (& docker inspect --format '{{index .Config.Labels "com.docker.compose.config-hash"}}' $restored)
                    if ($LASTEXITCODE -ne 0 -or "$restoredHash".Trim() -ne "$actualHash".Trim()) { $RestoreOk = $false }
                }
                if ($RestoreOk -and -not (Probe 'tls')) { $RestoreOk = $false }
                if ($RestoreOk -and -not (Probe 'read-only')) { $RestoreOk = $false }
            } catch { $RestoreOk = $false }
            if ($RestoreOk) { Write-Host 'PASS: baseline nginx configuration and synthetic authenticated read restored.' }
            else { Write-Warning 'FAIL: proxy restoration not verified. Do not retry; inspect current container.' }
        }
        if ($OwnedRemote -and $Container -and $Remote -match '^/tmp/corvex-jmap-guard-[0-9a-f]{32}$') {
            try { & docker exec -u 0 $Container sh -c 'find "$1" -depth -delete' sh $Remote }
            catch { Write-Warning 'Temporary probe cleanup failed.' }
        }
        Pop-Location
    }
} finally {
    if (Test-Path -LiteralPath $Temp) { Remove-Item -LiteralPath $Temp -Recurse -Force }
}
if (-not $RestoreOk) { throw 'FAIL: proxy restoration incomplete.' }
