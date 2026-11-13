<# Read-only mTLS / route check from running mail-storage. No restart or user changes. #>
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Source = Join-Path $PSScriptRoot 'JamesProvisionMtlsProbe.java'
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'FAIL: JDK 17 javac required.' }
$Temp = Join-Path ([IO.Path]::GetTempPath()) ('corvex-admin-probe-' + [guid]::NewGuid().ToString('N'))
$Remote = '/tmp/corvex-admin-probe-' + [guid]::NewGuid().ToString('N')
$Container = $null
try {
    New-Item -ItemType Directory -Path $Temp | Out-Null
    & javac --release 17 -d $Temp $Source
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: compile mTLS probe' }
    Push-Location $Repo
    try {
        $ids = @(docker compose --env-file .env --env-file infra/secrets/james/compose.env --env-file infra/secrets/james-provision/compose.env --profile james-transition --profile james-provision ps --status running -q mail-storage-service)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or [string]::IsNullOrWhiteSpace($ids[0])) { throw 'FAIL: running mail-storage-service not found' }
        $Container = $ids[0].Trim()
        & docker exec $Container mkdir $Remote
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: create probe directory' }
        & docker cp (Join-Path $Temp 'JamesProvisionMtlsProbe.class') "${Container}:${Remote}/"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: copy probe' }
        & docker exec $Container java -cp $Remote JamesProvisionMtlsProbe
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: mTLS/route probe; no user or mail was changed' }
    } finally { Pop-Location }
} finally {
    if ($Container) {
        & docker exec $Container rm -f "$Remote/JamesProvisionMtlsProbe.class"
        & docker exec $Container rmdir $Remote
    }
    if (Test-Path $Temp) { Remove-Item -LiteralPath $Temp -Recurse -Force }
}
