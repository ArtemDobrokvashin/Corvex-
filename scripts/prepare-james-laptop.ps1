<# Prepare dedicated James config in an empty ignored local directory.
   Does not run Docker, change .env, enable a service or touch mail data.
   Read docs/JAMES-CORVEX-TRANSITION.md before use. #>
param([switch]$ConfirmLaptop)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'Refusing: -ConfirmLaptop required; configuration is only for the current laptop transition.' }
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Root = Join-Path $Repo 'infra\secrets\james'
$Adapter = Join-Path $Repo 'infra\secrets\james-adapter'
$Helper = Join-Path $PSScriptRoot 'PrepareJamesKeys.java'
if (-not (Get-Command java -ErrorAction SilentlyContinue) -or -not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw 'FAIL: JDK 17 java and keytool are required.'
}
if (Test-Path $Root) { throw 'FAIL: James configuration directory already exists; nothing overwritten.' }
if ((Test-Path $Adapter) -and (-not (Test-Path $Adapter -PathType Container) -or @(Get-ChildItem -Force $Adapter).Count -ne 0)) {
    throw 'FAIL: adapter directory is not empty; nothing overwritten.'
}
if (-not (Test-Path (Join-Path $Repo 'infra\secrets'))) { throw 'FAIL: expected infra/secrets directory missing.' }

function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
}
function Write-NewAscii([string]$File, [string]$Value) {
    $stream = [IO.File]::Open($File, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = [Text.Encoding]::ASCII.GetBytes($Value)
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    } finally { $stream.Dispose() }
}

$DbPassword = New-Secret
$TlsPassword = New-Secret
$old = $env:CORVEX_JAMES_PREPARE_KEYSTORE_PASSWORD
try {
    New-Item -ItemType Directory -Path $Root | Out-Null
    if (-not (Test-Path $Adapter)) { New-Item -ItemType Directory -Path $Adapter | Out-Null }
    # The password is random and exists only during setup; keytool may expose
    # command arguments to local administrators/process inspection.
    & keytool -genkeypair -alias jmap -keyalg RSA -keysize 3072 -storetype PKCS12 `
        -keystore (Join-Path $Root 'jmap-keystore.p12') -storepass $TlsPassword -keypass $TlsPassword `
        -dname 'CN=james-jmap' -ext 'SAN=dns:james-jmap' -validity 730
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: generate JMAP TLS identity' }
    $env:CORVEX_JAMES_PREPARE_KEYSTORE_PASSWORD = $TlsPassword
    & java $Helper $Root $Adapter
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: generate James and adapter keys' }
    Write-NewAscii (Join-Path $Root 'postgres.properties') ("database.name=james`ndatabase.schema=public`ndatabase.host=james-postgres`ndatabase.port=5432`ndatabase.username=james`ndatabase.password=$DbPassword`n")
    Write-NewAscii (Join-Path $Root 'jmap.properties') "enabled=true`njmap.port=80`nuser.provisioning.enabled=false`njwt.publickeypem.url=file://conf/jwt_publickey`nauthentication.strategy.rfc8621=JWTAuthenticationStrategy`n"
    Write-NewAscii (Join-Path $Root 'webadmin.properties') "enabled=false`n"
    # Single-node scanning is suitable ONLY for the agreed laptop testing and
    # must be replaced before deployment or performance claims.
    Write-NewAscii (Join-Path $Root 'search.properties') "implementation=scanning`n"
    Write-NewAscii (Join-Path $Root 'blob.properties') "implementation=postgres`ndeduplication.enable=false`nencryption.aes.enable=false`nblob.export.implementation=localFile`nblob.export.localFile.directory=file://var/blobExporting`n"
    Write-NewAscii (Join-Path $Root 'compose.env') ("CORVEX_JAMES_DATABASE_PASSWORD=$DbPassword`n")
    Write-Host 'PASS: dedicated James credentials and laptop configuration created; nothing started or switched.'
    Write-Host 'Protect and back up the directory and its keys; scanning search is NOT for production.'
} catch {
    Write-Warning "Setup incomplete. No existing data was replaced. Preserve $Root and $Adapter and inspect before retrying; do not delete it automatically."
    throw
} finally {
    $env:CORVEX_JAMES_PREPARE_KEYSTORE_PASSWORD = $old
    $DbPassword = $null
    $TlsPassword = $null
}
