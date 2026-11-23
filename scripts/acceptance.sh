#!/usr/bin/env bash
#
# Один прогон приёмки Corvex на сервере: по шагам, PASS/FAIL, один лог.
#
# Аналог scripts/acceptance.ps1. Вместо ручного чек-листа останавливается на первом
# упавшем обязательном шаге и пишет отчёт в artifacts/smoke. Ничего не настраивает,
# не удаляет данные и не пересоздаёт тома.
#
# Шаги:
#   0. инструменты и SHA текущего коммита
#   1. compose config и состояние стека (runtime_smoke.py)
#   2. полный Maven reactor — только с --maven (это G1, долго)
#   3. почта сквозь SMTP → INBOX → Sent с вложением (mail_e2e.py)
#   4. календарь: событие, 409 на устаревшую ревизию, напоминание в INBOX (calendar_smoke.py)
#   5. синтетический алерт → письмо администратору (alert_e2e.py, с --alert)
#
# Пароли не передаются аргументами: вспомогательные скрипты спрашивают их скрытым
# вводом или читают CORVEX_TEST_*. В лог попадают имена шагов, длительности и текст
# ошибок без секретов.
#
# Пример:
#   ./scripts/acceptance.sh --base-url https://mail.company.ru \
#     --sender i.ivanov@company.ru --recipient a.petrova@company.ru
#
#   ./scripts/acceptance.sh --maven --alert \
#     --sender i.ivanov@company.ru --recipient a.petrova@company.ru \
#     --calendar-user i.ivanov@company.ru --alert-user admin@company.ru

set -Euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASE_URL="https://mail.company.local"
CA_FILE=""
SENDER=""
RECIPIENT=""
CALENDAR_USER=""
ALERT_USER=""
REPORT_DIR="artifacts/smoke"
RUN_MAVEN=0
RUN_ALERT=0
SKIP_MAIL=0
SKIP_CALENDAR=0
MAVEN_TIMEOUT_MINUTES=60

usage() {
  sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() {
  printf 'ОШИБКА: %s\n' "$*" >&2
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url) BASE_URL="${2:?}"; shift 2 ;;
    --ca-file) CA_FILE="${2:?}"; shift 2 ;;
    --sender) SENDER="${2:?}"; shift 2 ;;
    --recipient) RECIPIENT="${2:?}"; shift 2 ;;
    --calendar-user) CALENDAR_USER="${2:?}"; shift 2 ;;
    --alert-user) ALERT_USER="${2:?}"; shift 2 ;;
    --report-dir) REPORT_DIR="${2:?}"; shift 2 ;;
    --maven) RUN_MAVEN=1; shift ;;
    --alert) RUN_ALERT=1; shift ;;
    --skip-mail) SKIP_MAIL=1; shift ;;
    --skip-calendar) SKIP_CALENDAR=1; shift ;;
    --maven-timeout-minutes) MAVEN_TIMEOUT_MINUTES="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "неизвестный аргумент: $1 (см. --help)" ;;
  esac
done

[[ "$MAVEN_TIMEOUT_MINUTES" =~ ^[0-9]+$ ]] && [[ "$MAVEN_TIMEOUT_MINUTES" -gt 0 ]] \
  || die "--maven-timeout-minutes должен быть целым > 0"

cd "$REPO_ROOT"
mkdir -p "$REPORT_DIR"
REPORT_DIR="$(cd "$REPORT_DIR" && pwd)"
STAMP="$(date +%Y-%m-%d_%H%M%S)"
LOG="$REPORT_DIR/acceptance-$STAMP.log"
ROWS="$REPORT_DIR/.acceptance-$STAMP.rows"
: > "$LOG"
: > "$ROWS"

PYTHON="$(command -v python3 || command -v python || true)"
[[ -n "$PYTHON" ]] || die "python3 не найден: без него не запускаются smoke-скрипты"

FAILED_STEPS=()
SKIPPED_STEPS=()
STEP_ROWS=()

log() {
  local color="$1"; shift
  local line
  line="[$(date +%H:%M:%S)] $*"
  printf '%s\n' "$line" >> "$LOG"
  if [[ -t 1 ]]; then printf '\033[%sm%s\033[0m\n' "$color" "$line"; else printf '%s\n' "$line"; fi
}

git_sha() {
  git rev-parse --short HEAD 2>/dev/null || echo unknown
}

# Шаг — это функция, а не строка с кодом: аргументы передаются как аргументы, и
# `"$@"` не приходится собирать кавычками внутри кавычек.
invoke_step() {
  local name="$1" required="$2"; shift 2
  log Cyan "START $name"
  local started=$SECONDS code=0
  set +e
  "$@" 2>&1 | tee -a "$LOG"
  code=${PIPESTATUS[0]}
  set -e
  local status color
  case "$code" in
    0) status="PASS"; color=32 ;;
    3) status="SKIP"; color=33 ;;
    *) status="FAIL"; color=31 ;;
  esac
  local seconds=$((SECONDS - started))
  log "$color" "$status $name (${seconds}s)"
  STEP_ROWS+=("$status|$name|$seconds")
  printf '%s|%s|%s\n' "$status" "$name" "$seconds" >> "$ROWS"
  [[ "$status" == "SKIP" ]] && SKIPPED_STEPS+=("$name")
  if [[ "$status" == "FAIL" ]]; then
    FAILED_STEPS+=("$name")
    if [[ "$required" == "required" ]]; then
      finish
      die "обязательный шаг упал: $name. Лог: $LOG"
    fi
  fi
  return 0
}

step_toolchain() {
  docker --version || return 2
  docker compose version || return 2
  "$PYTHON" --version || return 2
}

step_compose_and_runtime() {
  docker compose config -q || return 2
  local args=("--url" "$BASE_URL" "--report" "$(new_report_path runtime)")
  [[ -n "$CA_FILE" ]] && args+=("--ca-file" "$CA_FILE")
  "$PYTHON" scripts/runtime_smoke.py "${args[@]}"
}

step_maven() {
  timeout "$((MAVEN_TIMEOUT_MINUTES * 60))" mvn -B clean verify
}

step_mail() {
  local args=("--host" "$HOST_NAME" "--sender" "$SENDER" "--recipient" "$RECIPIENT"
              "--report" "$(new_report_path mail-e2e)")
  [[ -n "$CA_FILE" ]] && args+=("--ca-file" "$CA_FILE")
  "$PYTHON" scripts/mail_e2e.py "${args[@]}"
}

step_calendar() {
  local args=("--base-url" "$BASE_URL" "--username" "$CALENDAR_USER"
              "--report" "$(new_report_path calendar)")
  [[ -n "$CA_FILE" ]] && args+=("--ca-file" "$CA_FILE")
  "$PYTHON" scripts/calendar_smoke.py "${args[@]}"
}

step_alert() {
  local args=("--host" "$HOST_NAME" "--username" "$ALERT_USER"
              "--report" "$(new_report_path alert-e2e)")
  [[ -n "$CA_FILE" ]] && args+=("--ca-file" "$CA_FILE")
  "$PYTHON" scripts/alert_e2e.py "${args[@]}"
}

new_report_path() {
  printf '%s/%s-%s.json' "$REPORT_DIR" "$1" "$STAMP"
}

finish() {
  local summary="$REPORT_DIR/acceptance-$STAMP.json"
  "$PYTHON" - "$summary" "$ROWS" "$(git_sha)" "$BASE_URL" "$LOG" <<'PY' || true
import json, sys
from datetime import datetime, timezone

summary, rows, sha, base_url, log = sys.argv[1:6]
steps = []
for line in open(rows, encoding="utf-8"):
    status, name, seconds = line.rstrip("\n").split("|")
    steps.append({"step": name, "status": status, "seconds": float(seconds)})
with open(summary, "w", encoding="utf-8") as handle:
    json.dump(
        {
            "suite": "acceptance",
            "created_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "git_sha": sha,
            "base_url": base_url,
            "log": log,
            "steps": steps,
        },
        handle,
        ensure_ascii=False,
        indent=2,
    )
PY
  rm -f "$ROWS"
  log White ""
  log White "Summary:"
  local row status name seconds color
  for row in "${STEP_ROWS[@]}"; do
    IFS='|' read -r status name seconds <<< "$row"
    color=32
    [[ "$status" == "SKIP" ]] && color=33
    [[ "$status" == "FAIL" ]] && color=31
    log "$color" "$(printf '  %-4s %-45s %4ss' "$status" "$name" "$seconds")"
  done
  log White "JSON: $summary"
}

HOST_NAME="$(printf '%s' "$BASE_URL" | sed -E 's#^[a-z]+://([^/:]+).*#\1#')"

log White "Corvex acceptance, SHA $(git_sha), base URL $BASE_URL"
log White "Log: $LOG"

invoke_step "toolchain" required step_toolchain
invoke_step "compose and runtime" required step_compose_and_runtime

if [[ "$RUN_MAVEN" -eq 1 ]]; then
  invoke_step "maven full reactor" required step_maven
fi

if [[ "$SKIP_MAIL" -eq 0 ]]; then
  if [[ -z "$SENDER" || -z "$RECIPIENT" ]]; then
    log Yellow "Шаг mail e2e пропущен: передайте --sender и --recipient"
  else
    invoke_step "mail e2e smtp, inbox, sent, attachment" required step_mail
  fi
fi

if [[ "$SKIP_CALENDAR" -eq 0 ]]; then
  if [[ -z "$CALENDAR_USER" ]]; then
    log Yellow "Шаг calendar пропущен: передайте --calendar-user"
  else
    invoke_step "calendar and reminder" required step_calendar
  fi
fi

if [[ "$RUN_ALERT" -eq 1 ]]; then
  if [[ -z "$ALERT_USER" ]]; then
    log Yellow "Шаг alert пропущен: передайте --alert-user"
  else
    invoke_step "alertmanager to mail" required step_alert
  fi
fi

finish

if [[ "${#FAILED_STEPS[@]}" -gt 0 ]]; then
  exit 1
fi
if [[ "${#SKIPPED_STEPS[@]}" -gt 0 ]]; then
  exit 3
fi
exit 0
