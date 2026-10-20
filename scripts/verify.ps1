<#
 Corvex: локальные ворота готовности на Windows Server с JDK 17, Maven 3.9+, Node.js 22.12+ и Docker.
 Использование:
   powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 [-DockerBuild]
   без флагов    — frontend-тесты/сборка, mvn verify всех сервисов, проверка compose-конфига
   -DockerBuild  — дополнительно собрать все Docker-образы (долго)
#>
param([switch]$DockerBuild)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

function Step($Text) { Write-Host "`n=== $Text ===" -ForegroundColor Cyan }
function Run($File, $Arguments) {
  & $File @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Command failed: $File $Arguments (exit $LASTEXITCODE)" }
}

Step "Toolchain"
Run "java" @("-version")
Run "mvn" @("-version")
Run "node" @("--version")
Run "npm" @("--version")
Run "docker" @("--version")
Run "docker" @("compose", "version")

Step "Frontend: tests + production build (modern + legacy Chrome 109)"
Push-Location frontend
try {
  Run "npm" @("ci")
  Run "npm" @("test")
  Run "npm" @("run", "build")
  Run "npm" @("audit", "--omit=dev", "--audit-level=moderate")
} finally { Pop-Location }

Step "Java: mvn verify for all 12 services (tests + JaCoCo gates)"
Run "mvn" @("-B", "clean", "verify")

Step "Docker Compose: config validation"
Run "docker" @("compose", "config", "-q")

if ($DockerBuild) {
  Step "Docker Compose: build all images"
  Run "docker" @("compose", "build")
}

Step "ALL LOCAL GATES PASSED"
Write-Host "Next: docker compose up -d on a test host, then the runtime smokes in docs/READINESS.md."
