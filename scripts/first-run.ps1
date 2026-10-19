<#
  Corvex first-run initializer (run ONCE, before the first 'docker compose up'):
    1) generates RSA key pairs for JWT and DKIM signing (infra\secrets),
    2) creates a 90-day self-signed TLS certificate for pilots (infra\certs),
    3) creates .env from .env.example with a DIFFERENT random secret per variable.
  Safe to re-run: existing keys/certs/.env are never overwritten.
  Requires: Docker running (used only as an openssl toolbox, nothing is installed on Windows).
#>
$ErrorActionPreference = "Stop"

function New-RandomSecret([int]$Length = 32) {
  $chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
  $bytes = New-Object byte[] $Length
  [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

New-Item -ItemType Directory -Force infra\certs, infra\secrets | Out-Null

# --- 1. JWT signing key (RS256) ---
if (-not (Test-Path infra\secrets\jwt_private.pem)) {
  docker run --rm -v "${PWD}\infra\secrets:/out" alpine sh -c "apk add --no-cache openssl >/dev/null && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /out/jwt_private.pem && openssl rsa -in /out/jwt_private.pem -pubout -out /out/jwt_public.pem"
  Write-Host "[OK] JWT key pair: infra\secrets\jwt_private.pem (+ jwt_public.pem)"
} else { Write-Host "[skip] infra\secrets\jwt_private.pem already exists" }

# --- 1b. DKIM signing key (selector corvex1) ---
if (-not (Test-Path infra\secrets\dkim_private.pem)) {
  docker run --rm -v "${PWD}\infra\secrets:/out" alpine sh -c "apk add --no-cache openssl >/dev/null && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /out/dkim_private.pem && openssl rsa -in /out/dkim_private.pem -pubout -out /out/dkim_public.pem"
  Write-Host "[OK] DKIM key pair: infra\secrets\dkim_private.pem (+ dkim_public.pem)"
} else { Write-Host "[skip] infra\secrets\dkim_private.pem already exists" }
if (Test-Path infra\secrets\dkim_public.pem) {
  $dkimPublicKey = (Get-Content infra\secrets\dkim_public.pem | Where-Object { $_ -notmatch '-----' }) -join ''
  Write-Host "[DNS] corvex1._domainkey TXT `"v=DKIM1; k=rsa; p=$dkimPublicKey`""
}

# --- 2. Pilot self-signed TLS (replace with Let's Encrypt / corporate CA for production) ---
if (-not (Test-Path infra\certs\fullchain.pem)) {
  docker run --rm -v "${PWD}\infra\certs:/out" alpine sh -c "apk add --no-cache openssl >/dev/null && openssl req -x509 -newkey rsa:2048 -keyout /out/privkey.pem -out /out/fullchain.pem -days 90 -nodes -subj '/CN=mail.company.local'"
  Write-Warning "Self-signed certificate created (90 days, CN=mail.company.local) - PILOT ONLY. See docs/INSTALL.md step 6."
} else { Write-Host "[skip] infra\certs\fullchain.pem already exists" }

# --- 3. .env with distinct random secrets (previous version replaced every placeholder with one value - fixed) ---
if (-not (Test-Path .env)) {
  $lines = Get-Content .env.example | ForEach-Object {
    if ($_ -match '^(.*)=change-me-strong-password$') { "$($Matches[1])=$(New-RandomSecret)" }
    elseif ($_ -match '^CORVEX_TOTP_ENC_KEY=') {
      $keyBytes = New-Object byte[] 32
      [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($keyBytes)
      "CORVEX_TOTP_ENC_KEY=$([Convert]::ToBase64String($keyBytes))"
    }
    else { $_ }
  }
  $lines | Set-Content .env -Encoding UTF8
  Write-Host "[OK] .env created with unique random secrets."
} else {
  Write-Warning ".env already exists - secrets NOT regenerated. If it still contains 'change-me-strong-password', delete .env and re-run this script (BEFORE first docker compose up; changing DB passwords later requires changing them inside PostgreSQL too)."
  # Upgrade path: installs created before the internal-API token was introduced
  # receive only this new secret; every existing value remains untouched.
  $existingEnv = Get-Content .env -Raw
  if ($existingEnv -notmatch '(?m)^CORVEX_INTERNAL_API_TOKEN=') {
    Add-Content .env "`nCORVEX_INTERNAL_API_TOKEN=$(New-RandomSecret 48)" -Encoding UTF8
    Write-Host "[OK] Added missing CORVEX_INTERNAL_API_TOKEN to existing .env."
  }
}

Write-Host ""
Write-Host "Next steps:"
Write-Host "  1) Edit .env - set CORVEX_DOMAIN / CORVEX_MAIL_DOMAIN (and CORVEX_LDAP_* if you have Active Directory)"
Write-Host "  2) docker compose up -d --build"
Write-Host "  3) docker compose --profile monitoring up -d   (optional monitoring stack)"
Write-Host "  4) Verify: docker compose ps   -> all services healthy"
