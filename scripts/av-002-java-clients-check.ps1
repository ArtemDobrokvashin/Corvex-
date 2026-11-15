<# Read-only synthetic client probe. No container restart, mail, port publishing or configuration changes. #>
param([switch]$ConfirmLaptop)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Tag = [guid]::NewGuid().ToString('N')
$Temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-av002-' + $Tag)
$Remote = '/tmp/corvex-av002-' + $Tag
$HostOwned = $false
$RemoteOwned = $false
$Container = $null
$ResultsOk = $false
try {
    Push-Location $Repo
    try {
        foreach ($cmd in @('docker','mvn','javac')) {
            if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "FAIL: $cmd not found." }
        }
        $ids = @(& docker compose ps --status running -q mail-storage-service)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -notmatch '^[0-9a-f]{12,64}$') { throw 'FAIL: expected running mail-storage container.' }
        $Container = $ids[0]
        if (Test-Path -LiteralPath $Temp) { throw 'FAIL: temporary host path exists.' }
        New-Item -ItemType Directory -Path $Temp -ErrorAction Stop | Out-Null
        $HostOwned = $true
        $Out = Join-Path $Temp 'classes'
        New-Item -ItemType Directory -Path $Out | Out-Null
        $classDirs = @()
        $springJars = @()
        foreach ($svc in @('mail-antivirus-service','mail-storage-service','chat-file-service')) {
            $module = Join-Path $Repo "services/$svc"
            $cp = Join-Path $Temp ("$svc-classpath.txt")
            & mvn -q -B -f (Join-Path $module 'pom.xml') '-DskipTests' compile dependency:build-classpath "-Dmdep.outputFile=$cp"
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $cp -PathType Leaf)) { throw "FAIL: Maven preparation $svc." }
            $classDirs += (Join-Path $module 'target/classes')
            if ($svc -eq 'mail-storage-service') {
                $classpath = ([IO.File]::ReadAllText($cp)).Trim().Split([IO.Path]::PathSeparator)
                foreach ($pattern in @('^spring-beans-[0-9].*\.jar$', '^spring-context-[0-9].*\.jar$', '^spring-core-[0-9].*\.jar$', '^spring-jcl-[0-9].*\.jar$')) {
                    $found = @($classpath | Where-Object { [IO.Path]::GetFileName($_) -match $pattern })
                    if ($found.Count -ne 1 -or -not (Test-Path -LiteralPath $found[0] -PathType Leaf)) { throw 'FAIL: missing Spring runtime jar.' }
                    $springJars += $found[0]
                }
            }
        }
        & javac --release 17 -cp (($classDirs + $springJars) -join [IO.Path]::PathSeparator) -d $Out (Join-Path $PSScriptRoot 'src/Av002ClientCheck.java')
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: Java probe compilation.' }
        foreach ($item in @(
            @{ module='mail-antivirus-service'; path='ru/corvex/mailav' },
            @{ module='mail-storage-service'; path='ru/corvex/mailstorage/av' },
            @{ module='chat-file-service'; path='ru/corvex/chatfile/av' }
        )) {
            $source = Join-Path $Repo ("services/$($item.module)/target/classes/$($item.path)")
            $dest = Join-Path $Out $item.path
            New-Item -ItemType Directory -Path $dest -Force | Out-Null
            $files = @(Get-ChildItem -LiteralPath $source -Filter 'ClamAvClient*.class' -File)
            if ($files.Count -lt 3) { throw 'FAIL: compiled client classes missing.' }
            foreach ($file in $files) { Copy-Item -LiteralPath $file.FullName -Destination $dest }
        }
        $Lib = Join-Path $Out 'lib'
        New-Item -ItemType Directory -Path $Lib | Out-Null
        foreach ($jar in $springJars) { Copy-Item -LiteralPath $jar -Destination $Lib }
        # mkdir without -p is atomic: never delete an existing directory.
        & docker exec $Container mkdir $Remote
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: could not create exclusive container directory.' }
        $RemoteOwned = $true
        & docker cp (Join-Path $Out '.') "${Container}:${Remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: probe copy.' }
        & docker exec $Container java -cp "$Remote`:$Remote/lib/*" Av002ClientCheck clamav 3310
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: AV-002; inspect output. No mail sent.' }
        $ResultsOk = $true
    } finally { Pop-Location }
} finally {
    if ($RemoteOwned -and $Container -and $Remote -match '^/tmp/corvex-av002-[0-9a-f]{32}$') {
        try {
            & docker exec -u 0 $Container sh -c 'find "$1" -depth -delete' sh $Remote
            if ($LASTEXITCODE -ne 0) { Write-Warning 'Container temporary cleanup failed.' }
        } catch { Write-Warning 'Container temporary cleanup failed.' }
    }
    if ($HostOwned -and (Test-Path -LiteralPath $Temp)) {
        try { Remove-Item -LiteralPath $Temp -Recurse -Force } catch { Write-Warning 'Host temporary cleanup failed.' }
    }
}
if ($ResultsOk) { Write-Host 'PASS: clients with real ClamAV; simulated error only. Not James AV or cutover acceptance.' }
