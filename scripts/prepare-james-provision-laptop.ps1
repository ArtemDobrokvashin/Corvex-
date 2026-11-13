<# Generate dedicated mTLS and WebAdmin credentials for the existing laptop
   transition. Never overwrites files, changes .env, starts Docker, or switches
   mail. WebAdmin and provisioning remain OFF. Partial files are preserved. #>
param([switch]$ConfirmLaptop)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Root = Join-Path $Repo 'infra\secrets\james-provision'
$Adapter = Join-Path $Repo 'infra\secrets\james-adapter'
$Helper = Join-Path $PSScriptRoot 'PrepareJamesProvisionKeys.java'
if (-not (Get-Command java -ErrorAction SilentlyContinue) -or -not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw 'FAIL: JDK 17 java and keytool required.'
}
if (Test-Path $Root) { throw 'FAIL: provisioning directory exists; nothing overwritten.' }
if (-not (Test-Path $Adapter -PathType Container)) { throw 'FAIL: existing James adapter directory not found.' }
foreach ($name in @('admin-client.p12', 'admin-client-password', 'admin-secret', 'admin-trust.crt')) {
    if (Test-Path (Join-Path $Adapter $name)) { throw "FAIL: adapter file already exists ($name); nothing overwritten." }
}
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
$ServerPassword = New-Secret
$ClientPassword = New-Secret
$AdminPassword = New-Secret
$oldServer = $env:CORVEX_JAMES_ADMIN_SERVER_PASSWORD
$oldClient = $env:CORVEX_JAMES_ADMIN_CLIENT_PASSWORD
try {
    New-Item -ItemType Directory -Path $Root | Out-Null
    # keytool arguments may be visible to local administrators during setup.
    & keytool -genkeypair -noprompt -alias server -keyalg RSA -keysize 3072 -storetype PKCS12 `
        -keystore (Join-Path $Root 'admin-server.p12') -storepass $ServerPassword -keypass $ServerPassword `
        -dname 'CN=james-admin' -ext 'SAN=dns:james-admin' -ext 'EKU=serverAuth' -validity 730
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: server TLS identity' }
    # The self-signed client certificate is the sole CA trusted by the proxy;
    # its private key never enters the James/admin-proxy containers.
    & keytool -genkeypair -noprompt -alias client -keyalg RSA -keysize 3072 -storetype PKCS12 `
        -keystore (Join-Path $Root 'admin-client-generated.p12') -storepass $ClientPassword -keypass $ClientPassword `
        -dname 'CN=corvex-james-admin-client' -ext 'bc=ca:true,pathlen:0' -ext 'EKU=clientAuth' `
        -ext 'KU=digitalSignature,keyCertSign' -validity 730
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: client TLS identity' }
    $env:CORVEX_JAMES_ADMIN_SERVER_PASSWORD = $ServerPassword
    $env:CORVEX_JAMES_ADMIN_CLIENT_PASSWORD = $ClientPassword
    & java $Helper $Root $Adapter
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: export TLS identities' }
    Write-NewAscii (Join-Path $Adapter 'admin-client-password') $ClientPassword
    Write-NewAscii (Join-Path $Adapter 'admin-secret') $AdminPassword
    Write-NewAscii (Join-Path $Root 'webadmin.properties') ("enabled=true`nhost=0.0.0.0`nport=8000`nhttps.enabled=false`njwt.enabled=false`npassword.generate=false`npassword=$AdminPassword`n")
    # This file only selects the protected config; it does not enable the API.
    Write-NewAscii (Join-Path $Root 'compose.env') "CORVEX_JAMES_WEBADMIN_FILE=./infra/secrets/james-provision/webadmin.properties`n"
    Write-Host 'PASS: dedicated provisioning keys/config prepared; nothing started or switched.'
    Write-Host 'Restrict and back up both secret directories. Do NOT enable WebAdmin or provisioning until instructed.'
} catch {
    Write-Warning "Setup incomplete. No existing files were replaced. Preserve $Root and $Adapter for inspection; do not retry or delete automatically."
    throw
} finally {
    $env:CORVEX_JAMES_ADMIN_SERVER_PASSWORD = $oldServer
    $env:CORVEX_JAMES_ADMIN_CLIENT_PASSWORD = $oldClient
    $ServerPassword = $null
    $ClientPassword = $null
    $AdminPassword = $null
}
