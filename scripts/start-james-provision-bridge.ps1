<# Laptop-only activation of the isolated WebAdmin mTLS bridge, NOT Corvex
   provisioning or mail cutover. Restarts James and its two proxies; leaves
   existing mail-storage and other Corvex containers untouched. #>
param([switch]$ConfirmLaptop)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$oldWeb = $env:CORVEX_JAMES_WEBADMIN_FILE
$oldEnabled = $env:CORVEX_JAMES_PROVISION_ENABLED
try {
    Push-Location $Repo
    try {
        foreach ($path in @('.env', 'infra/secrets/james/compose.env', 'infra/secrets/james-provision/compose.env',
                             'infra/secrets/james-provision/webadmin.properties', 'infra/secrets/james-provision/admin.crt',
                             'infra/secrets/james-provision/admin.key', 'infra/secrets/james-provision/client-ca.crt',
                             'infra/secrets/james-adapter/admin-client.p12', 'infra/secrets/james-adapter/admin-client-password',
                             'infra/secrets/james-adapter/admin-secret', 'infra/secrets/james-adapter/admin-trust.crt')) {
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "FAIL: required file missing: $path" }
        }
        # Explicit, temporary selection: never change .env or enable the API.
        $env:CORVEX_JAMES_WEBADMIN_FILE = './infra/secrets/james-provision/webadmin.properties'
        $env:CORVEX_JAMES_PROVISION_ENABLED = 'false'
        $composeArgs = @('compose','--env-file','.env','--env-file','infra/secrets/james/compose.env',
                         '--env-file','infra/secrets/james-provision/compose.env','--profile','james-transition','--profile','james-provision')
        & docker @composeArgs config --quiet
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: Compose configuration; no service restarted' }
        $mail = @(& docker @composeArgs ps --status running -q mail-storage-service)
        if ($LASTEXITCODE -ne 0 -or $mail.Count -ne 1 -or [string]::IsNullOrWhiteSpace($mail[0])) { throw 'FAIL: running mail-storage service missing' }
        $state = & docker @composeArgs exec -T mail-storage-service sh -c 'printenv CORVEX_JAMES_PROVISION_ENABLED || true'
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: cannot verify running mail-storage state' }
        if ($state -and ("$state".Trim().ToLowerInvariant() -ne 'false')) { throw 'FAIL: provisioning API is not disabled in running mail-storage' }
        $db = @(& docker @composeArgs ps --status running -q james-postgres)
        if ($LASTEXITCODE -ne 0 -or $db.Count -ne 1 -or [string]::IsNullOrWhiteSpace($db[0])) { throw 'FAIL: running James PostgreSQL missing' }
        $health = & docker inspect --format '{{.State.Health.Status}}' $db[0]
        if ($LASTEXITCODE -ne 0 -or "$health".Trim() -ne 'healthy') { throw 'FAIL: James PostgreSQL is not healthy' }
        $james = @(& docker @composeArgs ps --status running -q james)
        if ($LASTEXITCODE -ne 0 -or $james.Count -ne 1 -or [string]::IsNullOrWhiteSpace($james[0])) { throw 'FAIL: running James missing' }

        Write-Host 'Restarting only James; the old Corvex mail engine is not switched.'
        & docker @composeArgs up -d --no-deps --no-build --force-recreate james
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: James recreation; inspect before retry' }
        # Suppress CLI readiness output: it contains no secret and keeps one
        # summary; cmd redirects native stderr without PowerShell NativeCommandError.
        $ready = $false
        for ($i = 0; $i -lt 40; $i++) {
            & cmd.exe /c 'docker compose --env-file .env --env-file infra/secrets/james/compose.env --env-file infra/secrets/james-provision/compose.env --profile james-transition --profile james-provision exec -T james james-cli ListDomains 1>nul 2>nul'
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 3
        }
        if (-not $ready) { throw 'FAIL: James not ready; do not repeat key preparation' }
        Write-Host 'PASS: James ready; recreating only its JMAP proxy and starting the admin bridge.'
        & docker @composeArgs up -d --no-deps --no-build --force-recreate james-jmap james-admin
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: proxy creation; inspect before retry' }
        & docker @composeArgs exec -T james-admin nginx -t
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: admin proxy nginx configuration' }
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'test-james-provision-mtls.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: admin mTLS/route probe; do not repeat key preparation' }
        Write-Host 'PASS: bridge probe complete. Corvex provisioning API remains disabled; no mailbox was created or switched.'
    } finally { Pop-Location }
} finally {
    $env:CORVEX_JAMES_WEBADMIN_FILE = $oldWeb
    $env:CORVEX_JAMES_PROVISION_ENABLED = $oldEnabled
}
