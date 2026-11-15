<# Read-only TLS / unauthenticated HTTP probe from the existing mail-storage
   container. No service starts/restarts, account changes, credentials or mail. #>
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Source = Join-Path $PSScriptRoot 'JamesTransitionTlsProbe.java'
$ComposeEnv = Join-Path $Repo 'infra\secrets\james\compose.env'
$MainEnv = Join-Path $Repo '.env'
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'FAIL: JDK 17 javac is required.' }
if (-not (Test-Path $MainEnv) -or -not (Test-Path $ComposeEnv)) { throw 'FAIL: expected Compose env files missing.' }
$Temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-james-probe-' + [guid]::NewGuid().ToString('N'))
$Remote = '/tmp/corvex-james-probe-' + [guid]::NewGuid().ToString('N')
$Container = $null
try {
    New-Item -ItemType Directory -Path $Temp | Out-Null
    & javac --release 17 -d $Temp $Source
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: probe compilation' }
    Push-Location $Repo
    try {
        $ids = @(docker compose --env-file $MainEnv --env-file $ComposeEnv --profile james-transition ps --status running -q mail-storage-service)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or [string]::IsNullOrWhiteSpace($ids[0])) { throw 'FAIL: running mail-storage-service not found' }
        $Container = $ids[0].Trim()
        & docker exec $Container mkdir $Remote
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: create probe directory inside running container' }
        foreach ($class in @('JamesTransitionTlsProbe.class', 'JamesTransitionTlsProbe$1.class')) {
            & docker cp (Join-Path $Temp $class) "${Container}:${Remote}/"
            if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy compiled probe into running container' }
        }
        & docker exec $Container java -cp $Remote JamesTransitionTlsProbe
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: TLS/HTTP probe; no mail or configuration changed' }
    } finally { Pop-Location }
} finally {
    if ($Container) {
        & docker exec $Container rm -f "$Remote/JamesTransitionTlsProbe.class" ($Remote + '/JamesTransitionTlsProbe$1.class')
        & docker exec $Container rmdir $Remote
    }
    if (Test-Path $Temp) { Remove-Item -LiteralPath $Temp -Recurse -Force }
}
