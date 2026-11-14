#!/usr/bin/env bash
#
# AV-001: Проверка трёх клиентов ClamAV с настоящим clamd через существующий Docker Compose.
#
# Использует ТОЛЬКО синтетические данные: чистый поток и EICAR test string.
# Не отправляет реальных писем, не публикует порт 3310 наружу,
# не пересоздаёт контейнеры, не меняет James, Compose или почтовые маршруты.
#
# Требования к среде запуска:
#   - Docker Compose развёрнут (сервис clamav уже запущен)
#   - docker compose доступен в PATH
#
# Выходной код: 0 — все проверки PASS, 1 — хотя бы одна FAIL.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.yml"
CLAMAV_SERVICE="clamav"
CLAMAV_PORT=3310
UNREACHABLE_PORT=13310

# Состояние проверок
CHECKS_PASSED=0
CHECKS_FAILED=0
CHECK_OUTPUT=""

log() {
    echo "[av-001] $*"
}

record_pass() {
    CHECKS_PASSED=$((CHECKS_PASSED + 1))
    CHECK_OUTPUT="${CHECK_OUTPUT}  PASS: $1
"
}

record_fail() {
    CHECKS_FAILED=$((CHECKS_FAILED + 1))
    CHECK_OUTPUT="${CHECK_OUTPUT}  FAIL: $1
"
}

require_docker_compose() {
    if ! docker compose version >/dev/null 2>&1 && ! docker-compose version >/dev/null 2>&1; then
        echo "ERROR: docker compose не найден в PATH"
        exit 2
    fi
}

compose_cmd() {
    if docker compose version >/dev/null 2>&1; then
        docker compose "$@"
    else
        docker-compose "$@"
    fi
}

check_clamav_running() {
    local status
    status="$(compose_cmd -f "${COMPOSE_FILE}" ps --services --filter "name=${CLAMAV_SERVICE}" 2>/dev/null | head -n1)"
    if [[ -z "${status}" ]]; then
        # Альтернативная проверка: ищем контейнер clamav в выводе ps
        if ! compose_cmd -f "${COMPOSE_FILE}" ps "${CLAMAV_SERVICE}" 2>/dev/null | grep -q "running\|healthy\|Up"; then
            log "Сервис clamav не найден или не запущен в docker-compose"
            return 1
        fi
    fi
    return 0
}

copy_client_to_container() {
    # Копируем Python-клиент в работающий контейнер clamav
    local container_id
    container_id="$(compose_cmd -f "${COMPOSE_FILE}" ps -q "${CLAMAV_SERVICE}" 2>/dev/null | head -n1)"
    if [[ -z "${container_id}" ]]; then
        log "Не удалось получить ID контейнера clamav"
        return 1
    fi

    docker cp "${SCRIPT_DIR}/av-001-zinstream-client.py" "${container_id}:/tmp/av-001-zinstream-client.py" 2>/dev/null || {
        log "Не удалось скопировать клиент в контейнер clamav"
        return 1
    }
    echo "${container_id}"
}

run_in_container() {
    local container_id="$1"
    shift
    docker exec -i "${container_id}" python3 "$@" 2>&1
}

run_check() {
    local label="$1"
    local container_id="$2"
    local host="$3"
    local port="$4"
    local test_type="$5"

    local output
    output="$(run_in_container "${container_id}" "/tmp/av-001-zinstream-client.py" "${host}" "${port}" "${test_type}" 2>&1)"
    local exit_code=$?

    if [[ ${exit_code} -eq 0 ]]; then
        record_pass "${label} — ${output}"
    else
        record_fail "${label} — ${output}"
    fi
}

main() {
    echo "============================================================"
    echo "AV-001: Проверка клиентов ClamAV с настоящим clamd"
    echo "============================================================"
    echo ""

    require_docker_compose

    if ! check_clamav_running; then
        echo "FAIL: сервис clamav недоступен в docker-compose"
        echo ""
        echo "Убедитесь, что docker-compose запущен:"
        echo "  docker compose up -d clamav"
        exit 1
    fi

    log "Сервис clamav найден и запущен"

    CONTAINER_ID="$(copy_client_to_container)"
    if [[ -z "${CONTAINER_ID}" ]]; then
        exit 1
    fi
    log "Тестовый клиент скопирован в контейнер clamav (${CONTAINER_ID})"
    echo ""

    # Проверка 1: чистый поток → CLEAN
    echo "--- Проверка 1: чистый поток (ожидается CLEAN) ---"
    run_check "clean-stream" "${CONTAINER_ID}" "127.0.0.1" "${CLAMAV_PORT}" "clean"
    echo ""

    # Проверка 2: EICAR → INFECTED
    echo "--- Проверка 2: EICAR test string (ожидается INFECTED) ---"
    run_check "eicar-infected" "${CONTAINER_ID}" "127.0.0.1" "${CLAMAV_PORT}" "infected"
    echo ""

    # Проверка 3: недоступный сканер → ERROR (без остановки общего ClamAV)
    echo "--- Проверка 3: недоступный сканер (ожидается ERROR) ---"
    run_check "unavailable-scanner" "${CONTAINER_ID}" "127.0.0.1" "${UNREACHABLE_PORT}" "error"
    echo ""

    # Итог
    echo "============================================================"
    echo "ИТОГОВЫЙ ОТЧЁТ AV-001"
    echo "============================================================"
    echo -e "${CHECK_OUTPUT}"
    echo "------------------------------------------------------------"
    echo "Пройдено: ${CHECKS_PASSED}, провалено: ${CHECKS_FAILED}"
    echo "------------------------------------------------------------"

    if [[ ${CHECKS_FAILED} -eq 0 ]]; then
        echo "ИТОГ: PASS — все проверки AV-001 пройдены с настоящим ClamAV"
        exit 0
    else
        echo "ИТОГ: FAIL — есть непройденные проверки AV-001"
        exit 1
    fi
}

main "$@"
