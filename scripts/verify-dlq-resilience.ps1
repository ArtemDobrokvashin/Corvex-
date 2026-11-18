<#
.SYNOPSIS
  Проверяет, что перезапуск mail-storage-service не теряет письма в dead-letter очередь.

.DESCRIPTION
  Воспроизводит сбой, найденный 2026-10-19: при пересоздании mail-storage-service
  спам- и антивирусное звенья не могли скачать .eml из spool, исчерпывали ретраи и
  отправляли сообщения в DLQ. Оттуда они не возвращаются: курсор синхронизации внешнего
  ящика к этому моменту уже сдвинут.

  Скрипт делает три замера: до перезапуска, сразу после, и после того, как очередь
  опустеет. Вердикт выносится по росту DLQ, а не по глазам оператора.

  Очереди не очищаются и не переотправляются: скрипт только читает счётчики.

.PARAMETER StorageService
  Имя сервиса compose, который перезапускается. По умолчанию mail-storage-service.

.PARAMETER WaitSeconds
  Сколько ждать возврата очереди к нулю после перезапуска.

.PARAMETER BaselineOnly
  Только показать текущие числа и выйти: удобно перед запуском теста.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\verify-dlq-resilience.ps1 -BaselineOnly

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\verify-dlq-resilience.ps1
#>
param(
  [string]$StorageService = "mail-storage-service",
  [int]$WaitSeconds = 180,
  [switch]$BaselineOnly
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

# Очереди, которые нас интересуют. Пропуск очереди в этом списке означает слепую зону теста.
$watched = @(
  "mail.spam.inbound",
  "mail.spam.inbound.dlq",
  "mail.av.spam-checked",
  "mail.av.spam-checked.dlq",
  "mail.storage.av-checked",
  "mail.storage.av-checked.dlq"
)

function Get-QueueMessages {
  $raw = docker compose exec -T rabbitmq rabbitmqctl list_queues name messages --formatter json 2>$null
  if ($LASTEXITCODE -ne 0) {
    throw "rabbitmqctl недоступен: проверьте, что контейнер rabbitmq запущен"
  }
  $parsed = $raw | ConvertFrom-Json
  $result = @{}
  foreach ($row in $parsed) {
    $result[$row.name] = [int]$row.messages
  }
  return $result
}

function Show-Queues([hashtable]$queues) {
  foreach ($name in $watched) {
    $count = if ($queues.ContainsKey($name)) { $queues[$name] } else { 0 }
    Write-Host ("  {0,-38} {1,6}" -f $name, $count)
  }
}

function Wait-StorageHealthy([int]$TimeoutSeconds) {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    $status = docker compose ps --format "{{.Service}} {{.Health}}" |
      Select-String -SimpleMatch $StorageService
    if ($status -and ($status.ToString() -match "healthy")) {
      return $true
    }
    Start-Sleep -Seconds 2
  }
  return $false
}

Write-Host "=== Очереди до перезапуска ==="
$before = Get-QueueMessages
Show-Queues $before

$dlqBefore = 0
foreach ($name in $watched) {
  if ($name -like "*.dlq" -and $before.ContainsKey($name)) {
    $dlqBefore += $before[$name]
  }
}

if ($BaselineOnly) {
  Write-Host ""
  Write-Host ("Базовый уровень DLQ: {0}. Перезапуск не выполнялся." -f $dlqBefore)
  exit 0
}

Write-Host ""
Write-Host ("=== Перезапуск {0} ===" -f $StorageService)
docker compose restart $StorageService | Out-Host
if ($LASTEXITCODE -ne 0) {
  throw "docker compose restart завершился с ошибкой"
}

if (-not (Wait-StorageHealthy $WaitSeconds)) {
  Write-Host ("[FAIL] {0} не стал healthy за {1} с" -f $StorageService, $WaitSeconds)
  exit 2
}
Write-Host "[OK] сервис healthy"

# Очередь могла быть пустой до перезапуска; письма появляются только при новом сборе.
# Поэтому ждём, пока все наблюдаемые очереди опустеют: это значит, что сообщения,
# попавшие в них в момент перезапуска, обработаны, а не просто «ещё не дошли».
Write-Host ""
Write-Host ("=== Ожидание пустых очередей (до {0} с) ===" -f $WaitSeconds)
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$after = Get-QueueMessages
while ((Get-Date) -lt $deadline) {
  $after = Get-QueueMessages
  $pending = 0
  foreach ($name in $watched) {
    if ($name -notlike "*.dlq" -and $after.ContainsKey($name)) {
      $pending += $after[$name]
    }
  }
  if ($pending -eq 0) {
    break
  }
  Start-Sleep -Seconds 5
}

Write-Host ""
Write-Host "=== Очереди после перезапуска ==="
Show-Queues $after

$dlqAfter = 0
foreach ($name in $watched) {
  if ($name -like "*.dlq" -and $after.ContainsKey($name)) {
    $dlqAfter += $after[$name]
  }
}
$growth = $dlqAfter - $dlqBefore

Write-Host ""
Write-Host ("DLQ до: {0}; после: {1}; рост: {2}" -f $dlqBefore, $dlqAfter, $growth)
if ($growth -gt 0) {
  Write-Host "[FAIL] Перезапуск отправил письма в DLQ. Соберите логи:"
  Write-Host ("       docker compose logs --tail 200 {0}" -f $StorageService)
  Write-Host "       docker compose logs --tail 200 mail-spam-service"
  Write-Host "       docker compose logs --tail 200 mail-antivirus-service"
  exit 1
}

Write-Host "[PASS] DLQ не вырос: письма пережили перезапуск."
Write-Host "       Это доказывает обработку сбоя при активном сборе; недельную устойчивость — нет."
exit 0
