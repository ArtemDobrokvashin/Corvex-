#!/usr/bin/env bash
#
# Проверяет, что перезапуск mail-storage-service не теряет письма в dead-letter очередь.
#
# Bash-версия scripts/verify-dlq-resilience.ps1. Нужна потому, что на Windows Server
# контейнеры работают внутри Ubuntu-VM в Hyper-V: PowerShell на хосте до docker compose
# внутри VM не достанет. Контракт, очереди и вердикт совпадают с .ps1 один в один.
#
# Как проверяет:
#   1) снимает счётчики очередей;
#   2) перезапускает сервис хранения;
#   3) ждёт healthy;
#   4) ждёт, пока рабочие очереди опустеют;
#   5) сравнивает сумму DLQ до и после. Рост = провал.
#
# Очереди не очищаются и не переотправляются: автоочистка DLQ запрещена
# docs/OPERATIONS.md, потому что сообщение может ссылаться на уже удалённый spool.
#
# Использование:
#   ./scripts/verify-dlq-resilience.sh                # полный тест
#   ./scripts/verify-dlq-resilience.sh --baseline     # только текущие числа
#   ./scripts/verify-dlq-resilience.sh --service mail-storage-service --wait 300
#
# Коды возврата: 0 — DLQ не вырос; 1 — вырос; 2 — сервис не стал healthy.
#
set -uo pipefail
cd "$(dirname "$0")/.."

STORAGE_SERVICE="mail-storage-service"
WAIT_SECONDS=180
BASELINE_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --baseline) BASELINE_ONLY=1; shift ;;
    --service) STORAGE_SERVICE="${2:-}"; shift 2 ;;
    --wait) WAIT_SECONDS="${2:-180}"; shift 2 ;;
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Пропуск очереди в этом списке делает тест слепым к части потерь.
WATCHED="mail.spam.inbound mail.spam.inbound.dlq mail.av.spam-checked mail.av.spam-checked.dlq mail.storage.av-checked mail.storage.av-checked.dlq"

queue_count() {
  # $1 — имя очереди; печатает число сообщений или 0, если очереди нет.
  local name="$1" table="$2"
  local value
  value="$(printf '%s\n' "$table" | awk -F'\t' -v q="$name" '$1 == q { print $2; exit }')"
  [ -z "$value" ] && value=0
  printf '%s' "$value"
}

read_queues() {
  local raw
  raw="$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages 2>/dev/null)" || return 1
  [ -z "$raw" ] && return 1
  printf '%s' "$raw"
}

show_queues() {
  local table="$1" name count
  for name in $WATCHED; do
    count="$(queue_count "$name" "$table")"
    printf '  %-38s %6s\n' "$name" "$count"
  done
}

dlq_total() {
  local table="$1" name sum=0
  for name in $WATCHED; do
    case "$name" in
      *.dlq) sum=$((sum + $(queue_count "$name" "$table"))) ;;
    esac
  done
  printf '%s' "$sum"
}

working_total() {
  local table="$1" name sum=0
  for name in $WATCHED; do
    case "$name" in
      *.dlq) ;;
      *) sum=$((sum + $(queue_count "$name" "$table"))) ;;
    esac
  done
  printf '%s' "$sum"
}

echo "=== Очереди до перезапуска ==="
before="$(read_queues)" || { echo "rabbitmqctl недоступен: проверьте контейнер rabbitmq" >&2; exit 2; }
show_queues "$before"
dlq_before="$(dlq_total "$before")"

if [ "$BASELINE_ONLY" -eq 1 ]; then
  echo ""
  echo "Базовый уровень DLQ: $dlq_before. Перезапуск не выполнялся."
  exit 0
fi

echo ""
echo "=== Перезапуск ${STORAGE_SERVICE} ==="
docker compose restart "$STORAGE_SERVICE" || {
  echo "docker compose restart завершился с ошибкой" >&2
  exit 2
}

echo ""
echo "=== Ожидание healthy (до ${WAIT_SECONDS} с) ==="
healthy=0
deadline=$(( $(date +%s) + WAIT_SECONDS ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if docker compose ps --format '{{.Service}} {{.Health}}' 2>/dev/null \
      | grep -q "^${STORAGE_SERVICE} healthy$"; then
    healthy=1
    break
  fi
  sleep 2
done
if [ "$healthy" -ne 1 ]; then
  echo "[FAIL] ${STORAGE_SERVICE} не стал healthy за ${WAIT_SECONDS} с"
  exit 2
fi
echo "[OK] сервис healthy"

echo ""
echo "=== Ожидание пустых рабочих очередей (до ${WAIT_SECONDS} с) ==="
after="$before"
deadline=$(( $(date +%s) + WAIT_SECONDS ))
while [ "$(date +%s)" -lt "$deadline" ]; do
  if table="$(read_queues)"; then
    after="$table"
    [ "$(working_total "$table")" -eq 0 ] && break
  fi
  sleep 5
done

echo ""
echo "=== Очереди после перезапуска ==="
show_queues "$after"

dlq_after="$(dlq_total "$after")"
growth=$((dlq_after - dlq_before))

echo ""
echo "DLQ до: ${dlq_before}; после: ${dlq_after}; рост: ${growth}"
if [ "$growth" -gt 0 ]; then
  echo "[FAIL] Перезапуск отправил письма в DLQ. Соберите логи:"
  echo "       docker compose logs --tail 200 ${STORAGE_SERVICE}"
  echo "       docker compose logs --tail 200 mail-spam-service"
  echo "       docker compose logs --tail 200 mail-antivirus-service"
  exit 1
fi

echo "[PASS] DLQ не вырос: письма пережили перезапуск."
echo "       Это доказывает обработку сбоя при активном сборе; недельную устойчивость — нет."
exit 0
