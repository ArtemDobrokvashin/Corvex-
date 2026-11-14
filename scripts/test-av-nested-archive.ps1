<# Synthetic EICAR ZIP scan using existing ClamAV; no mail, no restart, no config edits. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [Parameter(Mandatory = $true)][string]$ComposeDir,
    [switch]$ConfirmLaptop
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop is required.' }
$tag = [guid]::NewGuid().ToString('N')
$temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-av-nested-' + $tag)
$remote = '/tmp/corvex-av-nested-' + $tag
$hostOwned = $false
$remoteOwned = $false
$container = $null
$probeExit = 2
try {
    foreach ($cmd in @('docker', 'mvn', 'javac')) {
        if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "FAIL: $cmd not found." }
    }
    $source = Join-Path $SourceRoot 'scripts/src/AvNestedArchiveProbe.java'
    $module = Join-Path $SourceRoot 'services/mail-storage-service'
    if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $module 'pom.xml') -PathType Leaf)) {
        throw 'FAIL: incomplete source archive.'
    }
    Push-Location -LiteralPath $ComposeDir
    try {
        $ids = @(& docker compose ps --status running -q mail-storage-service)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -notmatch '^[0-9a-f]{12,64}$') {
            throw 'FAIL: expected one running mail-storage container.'
        }
        $container = $ids[0]
        if (Test-Path -LiteralPath $temp) { throw 'FAIL: temporary host path exists.' }
        New-Item -ItemType Directory -Path $temp -ErrorAction Stop | Out-Null
        $hostOwned = $true
        $out = Join-Path $temp 'classes'
        New-Item -ItemType Directory -Path $out | Out-Null
        $classpathFile = Join-Path $temp 'classpath.txt'
        & mvn -q -B -f (Join-Path $module 'pom.xml') '-DskipTests' compile dependency:build-classpath "-Dmdep.outputFile=$classpathFile"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $classpathFile -PathType Leaf)) {
            throw 'FAIL: Maven dependency preparation.'
        }
        $classpath = ([IO.File]::ReadAllText($classpathFile)).Trim().Split([IO.Path]::PathSeparator)
        $springJars = @()
        foreach ($pattern in @('^spring-beans-[0-9].*\.jar$', '^spring-context-[0-9].*\.jar$', '^spring-core-[0-9].*\.jar$', '^spring-jcl-[0-9].*\.jar$')) {
            $found = @($classpath | Where-Object { [IO.Path]::GetFileName($_) -match $pattern })
            if ($found.Count -ne 1 -or -not (Test-Path -LiteralPath $found[0] -PathType Leaf)) {
                throw 'FAIL: required Spring jar missing.'
            }
            $springJars += $found[0]
        }
        $classes = Join-Path $module 'target/classes'
        & javac --release 17 -cp ((@($classes) + $springJars) -join [IO.Path]::PathSeparator) -d $out $source
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: Java probe compilation.' }
        $package = 'ru/corvex/mailstorage/av'
        $dest = Join-Path $out $package
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
        $files = @(Get-ChildItem -LiteralPath (Join-Path $classes $package) -Filter 'ClamAvClient*.class' -File)
        if ($files.Count -lt 3) { throw 'FAIL: compiled client classes missing.' }
        foreach ($file in $files) { Copy-Item -LiteralPath $file.FullName -Destination $dest }
        $lib = Join-Path $out 'lib'
        New-Item -ItemType Directory -Path $lib | Out-Null
        foreach ($jar in $springJars) { Copy-Item -LiteralPath $jar -Destination $lib }
        # Never delete an existing path: create an exclusive random directory.
        & docker exec $container mkdir $remote
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: container temp directory creation.' }
        $remoteOwned = $true
        & docker cp (Join-Path $out '.') "${container}:${remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: probe copy.' }
        & docker exec $container java -cp "$remote`:$remote/lib/*" AvNestedArchiveProbe clamav 3310
        $probeExit = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    if ($remoteOwned -and $container -and $remote -match '^/tmp/corvex-av-nested-[0-9a-f]{32}$') {
        try {
            & docker exec -u 0 $container sh -c 'find "$1" -depth -delete' sh $remote
            if ($LASTEXITCODE -ne 0) { Write-Warning 'Container temporary cleanup failed.' }
        } catch { Write-Warning 'Container temporary cleanup failed.' }
    }
    if ($hostOwned -and (Test-Path -LiteralPath $temp)) {
        try { Remove-Item -LiteralPath $temp -Recurse -Force } catch { Write-Warning 'Host temporary cleanup failed.' }
    }
}
if ($probeExit -eq 3) { Write-Host 'WARNING: incomplete scan observed; this is not AV acceptance.'; exit 3 }
if ($probeExit -ne 0) { throw "INCONCLUSIVE: synthetic AV probe exit $probeExit." }
Write-Host 'Probe completed; this is not James AV or cutover acceptance.'
