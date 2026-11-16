<#
.SYNOPSIS
    Preflight сервера Corvex: проверки, для которых домен и DNS не нужны.

.DESCRIPTION
    Запускается на сервере до появления домена и до включения почты. Проверяет
    только чтение: ничего не устанавливает, не меняет .env, не перезапускает
    контейнеры. Каждая проверка даёт PASS / WARN / FAIL и подсказку, что делать.

    Проверяется:
      0. инструменты: Docker, Compose, JDK 17, Maven, Node, Python;
      1. ресурсы: vCPU, RAM, свободное место по дискам (с учётом числа ящиков);
      2. порты 80/443/25/587/993/995: свободны они или заняты нужным контейнером;
      3. .env: наличие обязательных ключей, длина секретов, плейсхолдеры,
         одинаковость CORVEX_INTERNAL_API_TOKEN у сервисов;
      4. docker-compose.yml: config -q, отсутствие публикации портов БД;
      5. время: расхождение с NTP/UTC (для JWT и TLS это критично);
      6. состояние стека, если он уже запущен (контейнеры и health).

    Что НЕ проверяется и почему: DNS, MX/SPF/DKIM и сертификат требуют домена.
    Их проверяет `scripts/acceptance.ps1` и `runtime_smoke.py` после дня 2.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\preflight-server.ps1 `
        -Mailboxes 50 -DataDisk D:

.EXAMPLE
    # строгий режим: WARN тоже считается провалом (для приёмки сервера)
    powershell -ExecutionPolicy Bypass -File scripts\preflight-server.ps1 `
        -Mailboxes 50 -DataDisk D: -Strict
#>
param(
  [int]$Mailboxes = 50,
  [string]$DataDisk = "",
  [string]$ReportDir = "artifacts\smoke",
  [switch]$Strict
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$results = New-Object System.Collections.Generic.List[object]

function Add-Result([string]$Name, [string]$Status, [string]$Detail) {
  $results.Add([pscustomobject]@{ check = $Name; status = $Status; detail = $Detail }) | Out-Null
  $color = switch ($Status) { "PASS" { "Green" } "WARN" { "Yellow" } default { "Red" } }
  Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $(if ($Detail) { " - $Detail" } else { "" })) -ForegroundColor $color
}

function Get-Version([string]$Command, [string[]]$Arguments) {
  try {
    $output = & $Command @Arguments 2>&1 | Out-String
    return @{ ok = $true; text = ($output.Trim() -split "`n")[0] }
  } catch {
    return @{ ok = $false; text = $_.Exception.Message }
  }
}

Write-Host "Corvex preflight: $Mailboxes mailboxes, $(Get-Date -Format 'yyyy-MM-dd HH:mm')" -ForegroundColor White

# ---------------------------------------------------------------- 0. toolchain
$docker = Get-Version "docker" @("--version")
if (-not $docker.ok) { Add-Result "docker" "FAIL" "Docker не найден; установите Docker Engine/Desktop и перезайдите" }
else { Add-Result "docker" "PASS" $docker.text }

$compose = Get-Version "docker" @("compose", "version")
if (-not $compose.ok) { Add-Result "docker compose" "FAIL" "Плагин compose недоступен: docker compose version" }
else { Add-Result "docker compose" "PASS" $compose.text }

$java = Get-Version "java" @("-version")
if (-not $java.ok) { Add-Result "JDK 17" "FAIL" "Нет java; без JDK не собрать сервисы (mvn verify)" }
elseif ($java.text -match '"(1\.)?(\d+)') {
  $major = [int]$Matches[2]
  if ($major -lt 17) { Add-Result "JDK" "FAIL" "Java $major, нужна 17 (POM: <java.version>17</java.version>)" }
  else { Add-Result "JDK" "PASS" $java.text }
} else { Add-Result "JDK" "WARN" "Версию определить не удалось: $($java.text)" }

$maven = Get-Version "mvn" @("-version")
if (-not $maven.ok) { Add-Result "Maven" "WARN" "Нет mvn; G1 можно выполнить на другом хосте, но лучше поставить 3.9+" }
else { Add-Result "Maven" "PASS" $maven.text }

$node = Get-Version "node" @("--version")
if (-not $node.ok) { Add-Result "Node.js" "WARN" "Нет node; frontend собирается в образе, но npm ci нужен для G0" }
else { Add-Result "Node.js" "PASS" $node.text }

$pythonOk = $false
foreach ($candidate in @("py", "python")) {
  $version = Get-Version $candidate @("--version")
  if ($version.ok -and $version.text -match "3\.(\d+)") {
    if ([int]$Matches[1] -ge 10) {
      Add-Result "Python" "PASS" "$candidate $($version.text)"
      $pythonOk = $true
      break
    }
  }
}
if (-not $pythonOk) { Add-Result "Python" "WARN" "Нужен Python 3.10+ для scripts/*.py" }

# ---------------------------------------------------------------- 1. resources
$cpu = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
if ($cpu -ge 4) { Add-Result "CPU" "PASS" "$cpu логических процессоров" }
else { Add-Result "CPU" "WARN" "$cpu процессора: для $Mailboxes ящиков мало, нужно 4+" }

$ramGb = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
if ($ramGb -ge 24) { Add-Result "RAM" "PASS" "$ramGb ГБ" }
elseif ($ramGb -ge 16) { Add-Result "RAM" "WARN" "$ramGb ГБ: хватает без профиля monitoring; с мониторингом нужно 24 ГБ" }
else { Add-Result "RAM" "FAIL" "$ramGb ГБ: минимум 16 ГБ для полного стека" }

$estimate = $null
if ($pythonOk) {
  try {
    $json = & py -3 scripts/capacity.py --mailboxes $Mailboxes --json 2>$null
    if (-not $json) { $json = & python scripts/capacity.py --mailboxes $Mailboxes --json 2>$null }
    if ($json) { $estimate = $json | ConvertFrom-Json }
  } catch { $estimate = $null }
}

$dataFreeGb = $null
if ($DataDisk) {
  try {
    $volume = Get-Volume -DriveLetter ($DataDisk.TrimEnd(':')) -ErrorAction Stop
    $dataFreeGb = [math]::Round($volume.SizeRemaining / 1GB, 1)
    $dataSizeGb = [math]::Round($volume.Size / 1GB, 1)
    if ($estimate -and (($dataSizeGb -lt $estimate.recommended_data_disk_gib) -or ($dataFreeGb -lt ($estimate.recommended_data_disk_gib * 0.6)))) {
      Add-Result "data disk" "WARN" ("{0}: {1} ГБ всего, {2} ГБ свободно; расчёт для {3} ящиков — {4} ГБ" -f $DataDisk, $dataSizeGb, $dataFreeGb, $Mailboxes, $estimate.recommended_data_disk_gib)
    } else {
      Add-Result "data disk" "PASS" ("{0}: {1} ГБ всего, {2} ГБ свободно" -f $DataDisk, $dataSizeGb, $dataFreeGb)
    }
  } catch { Add-Result "data disk" "WARN" "Не удалось прочитать $DataDisk : $($_.Exception.Message)" }
} else {
  Add-Result "data disk" "WARN" "Не указан -DataDisk: проверка свободного места под данные пропущена"
}

if ($estimate) {
  Add-Result "capacity" "PASS" ("{0} ящиков → данные {1} ГБ, бэкап {2} ГБ, система {3} ГБ" -f $Mailboxes, $estimate.recommended_data_disk_gib, $estimate.recommended_backup_disk_gib, $estimate.recommended_system_disk_gib)
} else {
  Add-Result "capacity" "WARN" "Расчёт не выполнен; вручную: py -3 scripts/capacity.py --mailboxes $Mailboxes"
}

$systemDisk = Get-Volume -DriveLetter C -ErrorAction SilentlyContinue
if ($systemDisk) {
  $systemFree = [math]::Round($systemDisk.SizeRemaining / 1GB, 1)
  if ($systemFree -ge 80) { Add-Result "system disk" "PASS" "C: $systemFree ГБ свободно" }
  else { Add-Result "system disk" "WARN" "C: только $systemFree ГБ свободно; образам и build cache нужно ~85 ГБ" }
}

# ---------------------------------------------------------------- 2. ports
$portMap = @(
  @{ Port = 80; Service = "nginx" },
  @{ Port = 443; Service = "nginx" },
  @{ Port = 25; Service = "mail-smtp-service" },
  @{ Port = 587; Service = "mail-smtp-service" },
  @{ Port = 993; Service = "mail-imap-service" },
  @{ Port = 995; Service = "mail-pop3-service" }
)
$listening = @{}
try {
  Get-NetTCPConnection -State Listen -ErrorAction Stop | ForEach-Object {
    $listening[[int]$_.LocalPort] = $_.OwningProcess
  }
} catch {
  # Linux/macOS или ограниченные права: используем netstat, если он есть.
  $netstat = & netstat -ano 2>$null | Select-String "LISTENING|LISTEN"
  foreach ($line in $netstat) {
    $parts = ($line -split "\s+") | Where-Object { $_ }
    if ($parts.Count -ge 4) {
      $port = ($parts[1] -split ":")[-1]
      if ($port -match "^\d+$") { $listening[[int]$port] = $parts[-1] }
    }
  }
}

$dockerPorts = @{}
try {
  $running = & docker ps --format "{{.Names}}`t{{.Ports}}" 2>$null
  foreach ($line in $running) {
    foreach ($match in [regex]::Matches($line, ":(\d+)->")) {
      $dockerPorts[[int]$match.Groups[1].Value] = ($line -split "`t")[0]
    }
  }
} catch { }

foreach ($entry in $portMap) {
  $port = $entry.Port
  if ($dockerPorts.ContainsKey($port)) {
    Add-Result ("port {0}" -f $port) "PASS" ("занят контейнером {0} (ожидаемо)" -f $dockerPorts[$port])
  } elseif ($listening.ContainsKey($port)) {
    Add-Result ("port {0}" -f $port) "FAIL" ("занят процессом PID {0}; нужен {1}" -f $listening[$port], $entry.Service)
  } else {
    Add-Result ("port {0}" -f $port) "PASS" "свободен"
  }
}

# ---------------------------------------------------------------- 3. .env
$envPath = Join-Path (Get-Location) ".env"
if (-not (Test-Path -LiteralPath $envPath)) {
  Add-Result ".env" "FAIL" "Нет .env: выполните scripts\first-run.ps1 и заполните значения"
} else {
  $envLines = Get-Content -LiteralPath $envPath | Where-Object { $_ -match "^\s*[A-Za-z_][A-Za-z0-9_]*\s*=" }
  $envValues = @{}
  foreach ($line in $envLines) {
    $pair = $line -split "=", 2
    $envValues[$pair[0].Trim()] = $pair[1].Trim().Trim('"').Trim("'")
  }
  $required = @("POSTGRES_PASSWORD", "REDIS_PASSWORD", "RABBITMQ_PASSWORD", "ELASTIC_PASSWORD",
                "CORVEX_INTERNAL_API_TOKEN", "CORVEX_BOOTSTRAP_ADMIN_PASSWORD")
  $missing = @($required | Where-Object { -not $envValues.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($envValues[$_]) })
  if ($missing.Count -gt 0) { Add-Result ".env required" "FAIL" ("не заданы: " + ($missing -join ", ")) }
  else { Add-Result ".env required" "PASS" "все обязательные ключи заполнены" }

  $weak = @()
  foreach ($key in @("POSTGRES_PASSWORD", "REDIS_PASSWORD", "RABBITMQ_PASSWORD", "ELASTIC_PASSWORD",
                     "CORVEX_INTERNAL_API_TOKEN", "CORVEX_BOOTSTRAP_ADMIN_PASSWORD")) {
    if ($envValues.ContainsKey($key)) {
      $value = $envValues[$key]
      if ($value.Length -lt 16) { $weak += "$key (короче 16 символов)" }
      elseif ($value -match "^(change|changeme|password|secret|corvex|example)") { $weak += "$key (похоже на пример)" }
    }
  }
  if ($weak.Count -gt 0) { Add-Result ".env secrets" "WARN" ("слабые значения: " + ($weak -join ", ")) }
  else { Add-Result ".env secrets" "PASS" "длины и значения не выглядят как примеры" }

  $token = $envValues["CORVEX_INTERNAL_API_TOKEN"]
  if ($token) {
    $composeText = Get-Content -LiteralPath "docker-compose.yml" -Raw
    $mentions = ([regex]::Matches($composeText, "CORVEX_INTERNAL_API_TOKEN")).Count
    Add-Result "internal token" "PASS" ("задан один раз в .env, подставляется в {0} местах Compose" -f $mentions)
  } else {
    Add-Result "internal token" "FAIL" "CORVEX_INTERNAL_API_TOKEN пуст: внутренние вызовы вернут 503"
  }

  $placeholders = @($envValues.Keys | Where-Object { $envValues[$_] -match "example\.com|<.*>|CHANGEME" })
  if ($placeholders.Count -gt 0) { Add-Result ".env placeholders" "WARN" ("похожи на заготовки: " + ($placeholders -join ", ")) }
  else { Add-Result ".env placeholders" "PASS" "плейсхолдеров не найдено" }
}

# ---------------------------------------------------------------- 4. compose
try {
  & docker compose config -q 2>&1 | Out-Null
  if ($LASTEXITCODE -eq 0) { Add-Result "compose config" "PASS" "docker-compose.yml валиден" }
  else { Add-Result "compose config" "FAIL" "docker compose config -q вернул $LASTEXITCODE" }
} catch { Add-Result "compose config" "FAIL" $_.Exception.Message }

$composeRaw = Get-Content -LiteralPath "docker-compose.yml" -Raw
$dbPorts = @()
foreach ($pattern in @("`"5432:"", "`"5672:"", "`"6379:"", "`"9200:"", "`"9300:"", "`"3310:"", "`"783:"") ) {
  if ($composeRaw.Contains($pattern)) { $dbPorts += $pattern.Trim('"') }
}
if ($dbPorts.Count -gt 0) { Add-Result "db ports" "WARN" ("порты инфраструктуры опубликованы: " + ($dbPorts -join ", ")) }
else { Add-Result "db ports" "PASS" "PostgreSQL/RabbitMQ/Redis/Elasticsearch/ClamAV не публикуются наружу" }

# ---------------------------------------------------------------- 5. clock
try {
  $utc = (Get-Date).ToUniversalTime()
  $network = & w32tm /stripchart /computer:time.windows.com /samples:1 /dataonly 2>$null | Select-String "([+-]?\d+\.\d+)s"
  if ($network) {
    $offset = [math]::Abs([double]([regex]::Match($network.ToString(), "([+-]?\d+\.\d+)s").Groups[1].Value))
    if ($offset -le 5) { Add-Result "clock" "PASS" ("расхождение с NTP {0:N1} с" -f $offset) }
    else { Add-Result "clock" "WARN" ("расхождение с NTP {0:N1} с: JWT и TLS чувствительны ко времени" -f $offset) }
  } else {
    Add-Result "clock" "WARN" ("UTC {0:HH:mm}; внешнюю синхронизацию проверить не удалось" -f $utc)
  }
} catch { Add-Result "clock" "WARN" "Проверка NTP недоступна; убедитесь, что время синхронизируется" }

# ---------------------------------------------------------------- 6. stack
try {
  $ps = & docker compose ps --format "{{.Name}}`t{{.State}}`t{{.Status}}" 2>$null
  if (-not $ps) {
    Add-Result "stack" "WARN" "Контейнеры не запущены: это нормально до первого запуска"
  } else {
    $unhealthy = @($ps | Where-Object { $_ -match "unhealthy|Restarting|Exited" })
    if ($unhealthy.Count -eq 0) { Add-Result "stack" "PASS" ("{0} контейнеров запущены" -f @($ps).Count) }
    else { Add-Result "stack" "FAIL" ("проблемные: " + ($unhealthy -join "; ")) }
  }
} catch { Add-Result "stack" "WARN" "Состояние стека прочитать не удалось" }

# ---------------------------------------------------------------- summary
if (-not (Test-Path -LiteralPath $ReportDir)) { New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null }
$stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
$report = Join-Path $ReportDir "preflight-$stamp.json"
[pscustomobject]@{
  suite = "preflight"
  created_at = (Get-Date).ToUniversalTime().ToString("o")
  git_sha = (try { (git rev-parse --short HEAD).Trim() } catch { "unknown" })
  mailboxes = $Mailboxes
  checks = $results
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $report -Encoding UTF8

$fails = @($results | Where-Object { $_.status -eq "FAIL" }).Count
$warns = @($results | Where-Object { $_.status -eq "WARN" }).Count
Write-Host ""
Write-Host ("Итог: FAIL={0} WARN={1} PASS={2}" -f $fails, $warns, (@($results).Count - $fails - $warns)) -ForegroundColor White
Write-Host "JSON: $report"
Write-Host "DNS, MX/SPF/DKIM и TLS проверяются после появления домена: scripts\acceptance.ps1"

if ($fails -gt 0) { exit 1 }
if ($Strict -and $warns -gt 0) { exit 3 }
exit 0
