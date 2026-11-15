<# Read-only against James state: one separate JVM, no restart, no message or config change. #>
param([switch]$ConfirmLaptop, [string]$ComposeDir = (Get-Location).Path)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path -LiteralPath $ComposeDir).Path
if (-not (Test-Path -LiteralPath (Join-Path $Repo 'docker-compose.yml') -PathType Leaf)) { throw 'FAIL: run from the Corvex Compose directory.' }
$remote = '/tmp/corvex-james-avcp-' + [guid]::NewGuid().ToString('N')
$container = $null
$owned = $false
try {
    Push-Location $Repo
    try {
        $ids = @(& docker compose ps --status running -q james)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -notmatch '^[0-9a-f]{12,64}$') { throw 'FAIL: expected one running James container.' }
        $container = $ids[0]
        $jar = Join-Path $PSScriptRoot 'target/james-av-gate-0.0.1-SNAPSHOT.jar'
        $parent = Join-Path $PSScriptRoot 'target/extension-deps/apache-james-clamav-3.9.1.jar'
        foreach ($f in @($jar, $parent)) { if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { throw 'FAIL: package artifacts missing.' } }
        & docker exec $container sh -c 'test -d /root/libs && test -d /root/classes && test -d /root/resources'
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: unexpected James image layout; not changing it.' }
        & docker exec $container mkdir $remote
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: could not create unique temporary directory.' }
        $owned = $true
        & docker cp $jar "${container}:${remote}/gate.jar"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: extension copy.' }
        & docker cp $parent "${container}:${remote}/parent.jar"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: parent copy.' }
        $base = "$remote/gate.jar`:/root/resources`:/root/classes`:/root/libs/*"
        $parentResult = @(& docker exec $container java -cp $base ru.corvex.james.av.JamesAvClasspathCheck parent)
        if ($LASTEXITCODE -ne 0 -or $parentResult.Count -ne 1) { throw 'FAIL: parent classpath check.' }
        if ($parentResult[0] -eq 'CLAMAV_PARENT_PRESENT') {
            Write-Host 'Existing James runtime contains ClamAV parent; not adding a duplicate.'
            $cp = $base
        } elseif ($parentResult[0] -eq 'CLAMAV_PARENT_ABSENT') {
            Write-Host 'ClamAV parent absent from runtime; using the separately packaged James 3.9.1 jar for this JVM only.'
            $cp = "$remote/parent.jar`:$base"
        } else { throw 'FAIL: unexpected classpath probe output.' }
        & docker exec $container java -cp $cp ru.corvex.james.av.JamesAvClasspathCheck
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: candidate could not load against container runtime.' }
    } finally { Pop-Location }
} finally {
    if ($owned -and $container -and $remote -match '^/tmp/corvex-james-avcp-[0-9a-f]{32}$') {
        try {
            & docker exec -u 0 $container sh -c 'find "$1" -depth -delete' sh $remote
            if ($LASTEXITCODE -ne 0) { Write-Warning 'Temporary container cleanup failed.' }
        } catch { Write-Warning 'Temporary container cleanup failed.' }
    }
}
