# Synthetic-only integration lab. Creates isolated volumes and test keys; never deletes them.
# Runs from any directory. Does not change the existing Corvex or other James projects.
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Compose = Join-Path $PSScriptRoot 'compose.yaml'
$Pom = Join-Path $Repo 'services\mail-storage-service\pom.xml'
$Suffix = [guid]::NewGuid().ToString('N').Substring(0, 12)
$Project = 'corvex-jwt-lab-' + $Suffix
$Root = Join-Path $env:TEMP ('corvex-jwt-lab-' + $Suffix)
$Port = 18443
$oldDir = $env:JAMES_JWT_LAB_DIR
$oldPort = $env:JAMES_JWT_LAB_PORT
$oldConfirm = $env:JAMES_LIVE_CONFIRM
$oldOrigin = $env:JAMES_LIVE_HTTPS_ORIGIN
$oldPrivate = $env:JAMES_LIVE_TEST_KEY_PKCS8
$oldProject = $env:JAMES_LIVE_TEST_PROJECT
$oldCompose = $env:JAMES_LIVE_TEST_COMPOSE
$Started = $false

function Require-Success($Step) {
    if ($LASTEXITCODE -ne 0) { throw "FAIL: $Step (exit $LASTEXITCODE)" }
}

try {
    if (Test-Path $Root) { throw 'FAIL: key directory already exists' }
    New-Item -ItemType Directory -Path $Root | Out-Null
    $tlsStore = Join-Path $Root 'tls-lab.p12'
    $cert = Join-Path $Root 'tls-lab-cert.pem'
    $trust = Join-Path $Root 'trust-lab.p12'
    & keytool -genkeypair -alias lab -keyalg RSA -keysize 2048 -storetype PKCS12 `
        -keystore $tlsStore -storepass synthetic-lab-only -keypass synthetic-lab-only `
        -dname 'CN=localhost' -ext 'SAN=dns:localhost,ip:127.0.0.1' -validity 2
    Require-Success 'generate test TLS key'
    & keytool -exportcert -rfc -alias lab -keystore $tlsStore -storepass synthetic-lab-only -file $cert
    Require-Success 'export test TLS certificate'
    & keytool -importcert -noprompt -alias lab -file $cert -storetype PKCS12 -keystore $trust -storepass synthetic-lab-only
    Require-Success 'create test trust store'
    & java (Join-Path $PSScriptRoot 'GenerateLabKeys.java') $Root
    Require-Success 'generate dedicated test JWT key'

    $env:JAMES_JWT_LAB_DIR = $Root
    $env:JAMES_JWT_LAB_PORT = "$Port"
    $env:JAMES_LIVE_CONFIRM = 'synthetic-only'
    $env:JAMES_LIVE_HTTPS_ORIGIN = "https://127.0.0.1:$Port"
    $env:JAMES_LIVE_TEST_KEY_PKCS8 = Join-Path $Root 'private\jwt-private.der'
    $env:JAMES_LIVE_TEST_PROJECT = $Project
    $env:JAMES_LIVE_TEST_COMPOSE = $Compose
    Write-Host "Synthetic project: $Project"
    Write-Host "Synthetic keys: $Root"
    $Started = $true
    & docker compose -p $Project -f $Compose up -d
    Require-Success 'start isolated test services'

    # Retry the read-only CLI readiness query; never repeat AddDomain/AddUser.
    $Ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        # PowerShell 5 may wrap native stderr as an error record, so temporarily
        # suppress non-terminating stderr while checking the native exit code.
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & docker compose -p $Project -f $Compose exec -T james james-cli ListDomains 2>$null | Out-Null
        $code = $LASTEXITCODE
        $ErrorActionPreference = $previous
        if ($code -eq 0) { $Ready = $true; break }
        Start-Sleep -Seconds 3
    }
    if (-not $Ready) { throw 'FAIL: James did not become ready; synthetic data preserved' }
    Write-Host 'Checking the loopback-only HTTPS proxy port'
    & docker compose -p $Project -f $Compose port tls-proxy 8443
    Require-Success 'inspect proxy port mapping'
    $tcp = New-Object System.Net.Sockets.TcpClient
    try {
        $connect = $tcp.ConnectAsync('127.0.0.1', $Port)
        if (-not $connect.Wait(5000) -or -not $tcp.Connected) {
            throw 'FAIL: HTTPS proxy loopback port is not reachable'
        }
    } finally {
        $tcp.Dispose()
    }
    & docker compose -p $Project -f $Compose exec -T james james-cli AddDomain james.local
    Require-Success 'create synthetic domain'
    & docker compose -p $Project -f $Compose exec -T james james-cli AddUser user01@james.local 1234
    Require-Success 'create synthetic user01'
    & docker compose -p $Project -f $Compose exec -T james james-cli AddUser user02@james.local 1234
    Require-Success 'create synthetic user02'
    & docker compose -p $Project -f $Compose run --rm --no-deps seed
    Require-Success 'seed synthetic IMAP message'

    $startedAt = [DateTime]::UtcNow.AddSeconds(-2)
    # Pass lab properties to the Surefire profile only, not to Maven's own TLS.
    & mvn -B -f $Pom '-Pjames-live-lab' '-Dtest=*James*Test,AdminMailPermissionClientTest' `
        "-Djames.live.truststore=$trust" '-Djames.live.truststore.password=synthetic-lab-only' test
    Require-Success 'Java tests and live James test'
    $report = Join-Path $Repo 'services\mail-storage-service\target\surefire-reports\TEST-ru.corvex.mailstorage.james.JamesLiveMailboxTest.xml'
    if (-not (Test-Path $report) -or (Get-Item $report).LastWriteTimeUtc -lt $startedAt) {
        throw 'FAIL: no fresh live-test report'
    }
    [xml]$xml = Get-Content -Raw $report
    $suite = $xml.testsuite
    if ([int]$suite.tests -lt 1 -or [int]$suite.skipped -ne 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0) {
        throw 'FAIL: live James test was skipped or failed'
    }
    & docker compose -p $Project -f $Compose run --rm --no-deps --entrypoint python seed /fixture/migration_probe.py
    Require-Success 'synthetic migration byte and flags round-trip'
    Write-Host 'PASS: unit tests and live James JWT/HTTPS test completed'
} finally {
    if ($Started) {
        Write-Host 'Stopping this synthetic project only; volumes and test keys are preserved.'
        & docker compose -p $Project -f $Compose stop
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not stop synthetic project $Project; stop it manually." }
    }
    $env:JAMES_JWT_LAB_DIR = $oldDir
    $env:JAMES_JWT_LAB_PORT = $oldPort
    $env:JAMES_LIVE_CONFIRM = $oldConfirm
    $env:JAMES_LIVE_HTTPS_ORIGIN = $oldOrigin
    $env:JAMES_LIVE_TEST_KEY_PKCS8 = $oldPrivate
    $env:JAMES_LIVE_TEST_PROJECT = $oldProject
    $env:JAMES_LIVE_TEST_COMPOSE = $oldCompose
}
