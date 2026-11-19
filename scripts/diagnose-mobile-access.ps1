<#
.SYNOPSIS
    Диагностика доступа к веб-интерфейсу с телефона: почему на телефоне «переписка» другая.

.DESCRIPTION
    Всё, что делает скрипт, — читает. Он не меняет .env, не перезапускает контейнеры,
    не пишет в базу.

    Причина «на телефоне другой чат» почти всегда одна и та же: телефон открывает стенд
    по другому origin. Ноутбук — http://localhost:8080, телефон — http://192.168.x.x:8080.
    Для браузера это разные сайты, и дальше расходятся две вещи:

    1) WebSocket чата. chat-service принимает соединение только с origin из
       CORVEX_WEB_ORIGINS (по умолчанию — https://<CORVEX_DOMAIN>). SockJS отвечает 403
       на GET /ws/chat/info, если origin не совпал: комната откроется, история
       подтянется по REST, а новые сообщения в реальном времени не придут.
    2) Refresh-cookie. При CORVEX_AUTH_COOKIE_SECURE=true браузер не отправит cookie по
       http. Через 15 минут после входа телефон молча выйдет из сессии, а ноутбук — нет,
       если открыт по https или если cookie уже стоит.

    Скрипт печатает: адреса, по которым телефон может достучаться до ноутбука, какие
    origin реально разрешены в .env, и результат живой проверки /ws/chat/info с заголовком
    Origin — отдельно для localhost и для каждого LAN-адреса.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\diagnose-mobile-access.ps1

.EXAMPLE
    # другой порт фронтенда
    powershell -ExecutionPolicy Bypass -File scripts\diagnose-mobile-access.ps1 -Port 8443
#>
param(
  [int]$Port = 8080,
  [string]$PhoneOrigin = ""
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$findings = New-Object System.Collections.Generic.List[object]

function Add-Finding([string]$Name, [string]$Status, [string]$Detail) {
  $findings.Add([pscustomobject]@{ check = $Name; status = $Status; detail = $Detail }) | Out-Null
  $color = switch ($Status) { "PASS" { "Green" } "WARN" { "Yellow" } "ACTION" { "Yellow" } default { "Red" } }
  Write-Host ("[{0}] {1}{2}" -f $Status, $Name, $(if ($Detail) { " - $Detail" } else { "" })) -ForegroundColor $color
}

function Read-EnvMap {
  $map = @{}
  if (-not (Test-Path .env)) { return $map }
  foreach ($line in ([System.IO.File]::ReadAllText((Join-Path (Get-Location).Path ".env")) -split "`n")) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) { continue }
    $parts = $trimmed.Split("=", 2)
    $map[$parts[0].Trim()] = $parts[1].Trim()
  }
  return $map
}

function Get-LanAddresses {
  $result = @()
  try {
    $addresses = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
      Where-Object { $_.OperationalStatus -eq 'Up' -and $_.NetworkInterfaceType -ne 'Loopback' } |
      ForEach-Object { $_.GetIPProperties().UnicastAddresses } |
      Where-Object { $_.Address.AddressFamily -eq 'InterNetwork' } |
      ForEach-Object { $_.Address.ToString() } |
      Where-Object { $_ -notlike '169.254.*' -and $_ -ne '127.0.0.1' }
    $result = @($addresses | Select-Object -Unique)
  } catch {
    $result = @()
  }
  return $result
}

# Запрос с заголовком Origin. 403 — это и есть «origin не разрешён», и именно его
# надо увидеть, а не сообщение об исключении.
function Invoke-OriginProbe([string]$Url, [string]$Origin) {
  try {
    $response = Invoke-WebRequest -Uri $Url -Headers @{ Origin = $Origin } -UseBasicParsing -TimeoutSec 5
    return @{ code = [int]$response.StatusCode; text = "" }
  } catch {
    $status = 0
    $text = $_.Exception.Message
    try { $status = [int]$_.Exception.Response.StatusCode } catch { }
    if ($status -eq 0 -and $_.Exception.InnerException) {
      try { $status = [int]$_.Exception.InnerException.Response.StatusCode } catch { }
    }
    return @{ code = $status; text = $text }
  }
}

$envMap = Read-EnvMap
$lan = Get-LanAddresses

Write-Host "Corvex: доступ с телефона, порт $Port, $(Get-Date -Format 'yyyy-MM-dd HH:mm')" -ForegroundColor White
Write-Host ""

$domain = $envMap['CORVEX_DOMAIN']
if (-not $domain) { $domain = "mail.company.local" }
$configured = $envMap['CORVEX_WEB_ORIGINS']
if (-not $configured) { $configured = "https://$domain" }
$allowed = @($configured.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ })
Add-Finding "разрешённые origin" "INFO" ($allowed -join ", ")

$cookieSecure = $envMap['CORVEX_AUTH_COOKIE_SECURE']
if ($cookieSecure -eq "true") {
  Add-Finding "refresh-cookie" "WARN" "CORVEX_AUTH_COOKIE_SECURE=true: по http cookie не сохранится, телефон выйдет из сессии через ~15 минут"
} elseif ($cookieSecure -eq "false") {
  Add-Finding "refresh-cookie" "PASS" "CORVEX_AUTH_COOKIE_SECURE=false: cookie работает по http"
} else {
  Add-Finding "refresh-cookie" "WARN" "CORVEX_AUTH_COOKIE_SECURE не задан: по умолчанию true"
}

if ($lan.Count -eq 0) {
  Add-Finding "адрес ноутбука в сети" "FAIL" "не найден ни один IPv4, кроме loopback: телефон не увидит стенд"
} else {
  foreach ($address in $lan) {
    Add-Finding "адрес ноутбука в сети" "INFO" "http://${address}:$Port"
  }
}

$localOrigin = "http://localhost:$Port"
$probeUrl = "http://localhost:$Port/ws/chat/info?t=1"
$probe = Invoke-OriginProbe $probeUrl $localOrigin
if ($probe.code -eq 0) {
  Add-Finding "чат с localhost" "WARN" "стенд не отвечает на $probeUrl ($($probe.text))"
} elseif ($probe.code -eq 200) {
  Add-Finding "чат с localhost" "PASS" "origin $localOrigin разрешён"
} elseif ($probe.code -eq 403) {
  Add-Finding "чат с localhost" "FAIL" "origin $localOrigin запрещён: добавьте его в CORVEX_WEB_ORIGINS"
} else {
  Add-Finding "чат с localhost" "WARN" "код $($probe.code) на $probeUrl"
}

if ($PhoneOrigin) {
  $lan = @($PhoneOrigin -replace "^https?://", "" -replace ":$Port$", "")
}

foreach ($address in $lan) {
  $origin = "http://${address}:$Port"
  $url = "http://${address}:$Port/ws/chat/info?t=1"
  $probe = Invoke-OriginProbe $url $origin
  $inList = $allowed -contains $origin
  switch ($probe.code) {
    200 { Add-Finding "чат с телефона ($address)" "PASS" "origin $origin разрешён" }
    403 {
      if ($inList) {
        Add-Finding "чат с телефона ($address)" "FAIL" "origin есть в .env, но сервис его не принял: перезапустите chat-service после правки .env"
      } else {
        Add-Finding "чат с телефона ($address)" "FAIL" "origin $origin не разрешён — новые сообщения не придут, история останется"
      }
    }
    0 { Add-Finding "чат с телефона ($address)" "WARN" "нет ответа на ${url}: либо стенд не поднят, либо порт закрыт брандмауэром" }
    default { Add-Finding "чат с телефона ($address)" "WARN" "код $($probe.code) на $url" }
  }
}

$missing = @()
foreach ($address in $lan) {
  $origin = "http://${address}:$Port"
  if (-not ($allowed -contains $origin)) { $missing += $origin }
}
if ($missing.Count -gt 0) {
  Add-Finding "правка .env" "ACTION" ("добавьте в CORVEX_WEB_ORIGINS через запятую: " + (($allowed + $missing) -join ","))
}

Write-Host ""
Write-Host "Итого: $($findings.Count) проверок." -ForegroundColor White
Write-Host "Если чат с телефона получил 403, после правки .env выполните:" -ForegroundColor White
Write-Host "  docker compose up -d --force-recreate chat-service web-gateway"
Write-Host "Открывать на телефоне нужно тот же адрес, который проверен выше, и не localhost."
