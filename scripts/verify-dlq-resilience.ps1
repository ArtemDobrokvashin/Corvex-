<#
.SYNOPSIS
  Проверяет, что перезапуск mail-storage-service не теряет письма в dead-letter очередь.

.DESCRIPTION
  Воспроизводит сбой, найденный 2026-10-19: при пересоздании mail-storage-service
  спам- и антивирусное звенья не могли скачать .eml из spool, исчерпывали ретраи и
  отправляли сообщения в DLQ. Оттуда они не возвращаются: курсор синхронизации внешнего
  ящика к этому моменту уже сдвинут.

  Скрипт снимает счётчики очередей, перезапускает сервис, ждёт healthy, ждёт опустошения
  рабочих очередей и выносит вердикт по росту DLQ. Очереди не очищаются и не
  переотправляются: автоочистка DLQ запрещена docs/OPERATIONS.md.

  Bash-близнец: scripts/verify-dlq-resilience.sh — для Ubuntu-VM, где контейнеры и живут,
  если сервер на Windows Server с Hyper-V. Контракт у обоих одинаковый.

.PARAMETER StorageService
  Имя сервиса compose, который перезапускается. По умолчанию mail-storage-service.

.PARAMETER WaitSeconds
  Сколько ждать healthy и опустошения очередей.

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

# Пропуск очереди в этом списке делает тест слепым к части потерь.
$watched = @(
  "mail.spam.inbound",
  "mail.spam.inbound.dlq",
  "mail.av.spam-checked",
  "mail.av.spam-checked.dlq",
  "mail.storage.av-checked",
  "mail.storage.av-checked.dlq"
)

function Get-QueueTable {
  # Обычный текстовый вывод rabbitmqctl: строки «имя<TAB>число», без заголовка.
  # JSON-форматтер сознательно не используется: в старых образах его поведение
  # менялось, а разбор строк одинаково работает и в PS 5.1, и в PS 7.
  $raw = @(& docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages 2>$null)
  if ($LASTEXITCODE -ne 0 -or $raw.Count -eq 0) {
    throw "rabbitmqctl недоступен: проверьте, что контейнер rabbitmq запущен"
  }
  $table = @{}
  foreach ($line in $raw) {
    if ($line -match '^\s*([^\s]+)\s+(\d+)\s*$') {
      $table[$Matches[1]] = [int]$Matches[2]
    }
  }
  return $table
}

function Get-Count([hashtable]$table, [string]$name) {
  if ($table.ContainsKey($name)) { return $table[$name] }
  return 0
}

function Show-Queues([hashtable]$table) {
  foreach ($name in $watched) {
    Write-Host ("  {0,-38} {1,6}" -f $name, (Get-Count $table $name))
  }
}

function Get-Total([hashtable]$table, [bool]$dlqOnly) {
  $sum = 0
  foreach ($name in $watched) {
    $isDlq = $name.EndsWith(".dlq")
    if ($isDlq -eq $dlqOnly) {
      $sum += Get-Count $table $name
    }
  }
  return $sum
}

Write-Host "=== Очереди до перезапуска ==="
$before = Get-QueueTable
Show-Queues $before
$dlqBefore = Get-Total $before $true

if ($BaselineOnly) {
  Write-Host ""
  Write-Host ("Базовый уровень DLQ: {0}. Перезапуск не выполнялся." -f $dlqBefore)
  exit 0
}

Write-Host ""
Write-Host ("=== Перезапуск {0} ===" -f $StorageService)
docker compose restart $StorageService | Out-Host
if ($LASTEXITCODE -ne 0) {
  Write-Host "docker compose restart завершился с ошибкой"
  exit 2
}

Write-Host ""
Write-Host ("=== Ожидание healthy (до {0} с) ===" -f $WaitSeconds)
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$healthy = $false
while ((Get-Date) -lt $deadline) {
  $state = docker compose ps --format "{{.Service}} {{.Health}}" 2>$null
  if ($state -contains "$StorageService healthy") {
    $healthy = $true
    break
  }
  Start-Sleep -Seconds 2
}
if (-not $healthy) {
  Write-Host ("[FAIL] {0} не стал healthy за {1} с" -f $StorageService, $WaitSeconds)
  exit 2
}
Write-Host "[OK] сервис healthy"

Write-Host ""
Write-Host ("=== Ожидание пустых рабочих очередей (до {0} с) ===" -f $WaitSeconds)
$after = $before
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
  try {
    $table = Get-QueueTable
    $after = $table
    if ((Get-Total $table $false) -eq 0) { break }
  } catch {
    # RabbitMQ мог кратковременно отвалиться во время перезапуска: пробуем снова.
  }
  Start-Sleep -Seconds 5
}

Write-Host ""
Write-Host "=== Очереди после перезапуска ==="
Show-Queues $after

$dlqAfter = Get-Total $after $true
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
