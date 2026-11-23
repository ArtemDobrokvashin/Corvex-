#!/usr/bin/env bash
#
# Проверка изолированного восстановления (`scripts/restore.sh --data-only`) против
# самой копии. Рабочий проект corvex запрещён: приложения не запускаются и не
# останавливаются.
#
# Пример:
#   ./scripts/restore_verify.sh --backup-dir /srv/backup/2026-11-23_030000 \
#     --project corvex-restore-drill
#
# Правила — те же, что в scripts/restore_verify.ps1, и каждое появилось после
# настоящей ошибки, а не из любви к строгости:
#
#   1. Тома сверяются с архивами копии, а не с рабочими томами: иначе проверка
#      сравнивает изменяемый источник сам с собой.
#   2. Пароль берётся `printenv` у уже подключённого контейнера, а не из .env:
#      `docker run` с файлом окружения — другой парсер (BOM, CRLF дают мусор в конце).
#   3. Проверка идёт по TCP: официальный образ postgres разрешает unix-сокету
#      `trust`, поэтому `exec psql` проходит и с неверным паролем.
#   4. Базы сравниваются через `pg_dump -Fp` обеих сторон и нормализацию, потому
#      что `pg_restore` и `pg_dump` рисуют эквивалентный DDL по-разному.
#   5. Успех засчитывается только по маркеру `<db>_logical_match`. Скрипт однажды
#      напечатал «OK», ничего не сверив: многострочная команда доехала до sh
#      усечённой и вернула 0. Здесь маркер — второй рубеж, а `set -e` в sh — первый.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKUP_DIR=""
PROJECT_NAME="corvex-restore-drill"
DATABASES=(auth_db admin_db mail_db chat_db)

usage() {
  sed -n '2,22p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() {
  printf 'ОШИБКА: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup-dir) BACKUP_DIR="${2:?--backup-dir требует значение}"; shift 2 ;;
    -p|--project) PROJECT_NAME="${2:?--project требует значение}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "неизвестный аргумент: $1 (см. --help)" ;;
  esac
done

[[ -n "$BACKUP_DIR" ]] || die "нужен --backup-dir (см. --help)"
[[ "$PROJECT_NAME" != "corvex" ]] \
  || die "проверять рабочий corvex нельзя: нужен изолированный проект из --data-only"
[[ "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "недопустимое имя compose-проекта: $PROJECT_NAME"
[[ -d "$BACKUP_DIR" ]] || die "каталог копии не найден: $BACKUP_DIR"
command -v docker >/dev/null 2>&1 || die "docker не найден в PATH"

BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"
cd "$REPO_ROOT"

for db in "${DATABASES[@]}"; do
  [[ -f "$BACKUP_DIR/$db.dump" ]] || die "нет дампа базы: $db.dump"
done

VOLUME_ARCHIVES=(mail-data.tar.gz attachments.tar.gz chat-files.tar.gz)
VOLUME_NAMES=(mail-data attachment-data chat-file-data)
VOLUME_MARKERS=(mail_data_match attachment_data_match chat_file_data_match)
if [[ -f "$BACKUP_DIR/alert-relay-data.tar.gz" ]]; then
  VOLUME_ARCHIVES+=(alert-relay-data.tar.gz)
  VOLUME_NAMES+=(alert-relay-data)
  VOLUME_MARKERS+=(alert_relay_data_match)
fi
for archive in "${VOLUME_ARCHIVES[@]}"; do
  [[ -f "$BACKUP_DIR/$archive" ]] || die "нет архива тома: $archive"
done

# Метка проекта проверяется до создания хоть одной справочной базы.
postgres_id="$(docker compose -p "$PROJECT_NAME" ps -q postgres)"
[[ -n "$postgres_id" ]] || die "не найден контейнер postgres изолированного проекта"
actual_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$postgres_id")"
[[ "$actual_project" == "$PROJECT_NAME" ]] \
  || die "проект не совпал: ожидался $PROJECT_NAME, получен ${actual_project:-<пусто>}"

for index in "${!VOLUME_ARCHIVES[@]}"; do
  archive="${VOLUME_ARCHIVES[$index]}"
  volume="${PROJECT_NAME}_${VOLUME_NAMES[$index]}"
  marker="${VOLUME_MARKERS[$index]}"
  echo "Сверяю $volume с $archive..."
  output="$(docker run --rm -v "$volume:/restored:ro" -v "$BACKUP_DIR:/backup:ro" alpine \
    sh -c "set -e; mkdir /expected && tar xzf /backup/$archive -C /expected && diff -qr /expected /restored && printf '%s files=%s\\n' '$marker' \"\$(find /restored -type f | wc -l)\"" )" \
    || die "том $volume не совпал с архивом $archive (см. вывод diff выше)"
  # `diff -qr` возвращает 0 и на двух пустых каталогах, а пустой том против пустого
  # архива — не доказательство. Поэтому маркер печатается вместе с числом файлов,
  # и число попадает в вывод приёмки.
  [[ "$output" == *"$marker"* ]] || die "маркер $marker не найден: успех без маркера не засчитывается"
  [[ "$output" == *"files=0"* ]] && die "том $volume пуст: сверять нечего"
  printf '%s\n' "$output" | tail -n 1
done

# Пользователь и пароль — у работающего контейнера, не из .env.
PG_USER="$(docker compose -p "$PROJECT_NAME" exec -T postgres printenv POSTGRES_USER | tr -d '\r')" \
  || die "не удалось прочитать POSTGRES_USER"
PG_PASSWORD="$(docker compose -p "$PROJECT_NAME" exec -T postgres printenv POSTGRES_PASSWORD | tr -d '\r')" \
  || die "не удалось прочитать POSTGRES_PASSWORD"
[[ -n "$PG_USER" && -n "$PG_PASSWORD" ]] || die "POSTGRES_USER или POSTGRES_PASSWORD пусты"

# Проверка входа по TCP — тем же путём, что и сверка ниже. Пароль не попадает в
# аргументы: `-e PGPASSWORD` без значения берёт его из окружения процесса.
network="${PROJECT_NAME}_data"
docker run --rm --network "$network" -e PGPASSWORD="$PG_PASSWORD" -e PGUSER="$PG_USER" \
  postgres:16-alpine psql -h postgres -U "$PG_USER" -d postgres -tAc "select 1" >/dev/null \
  || die "вход по TCP в изолированный postgres не прошёл. Пароль контейнера не совпадает \
с паролем базы: drill создан при одном POSTGRES_PASSWORD, сейчас в .env другое. \
Пересоздайте drill: docker compose -p $PROJECT_NAME down -v"

suffix="$(date +%Y%m%d%H%M%S)"
verified=()

for db in "${DATABASES[@]}"; do
  reference_db="verify_${db}_${suffix}"
  echo "Сверяю $db через временную базу $reference_db..."
  # Команда собирается в одну строку без переводов: `sh -c` получает её целиком,
  # а `set -e` останавливает сценарий на первой ошибке.
  script="set -e; "
  script+="createdb -h postgres -U '$PG_USER' '$reference_db'; "
  script+="pg_restore -h postgres -U '$PG_USER' -d '$reference_db' -Fc --no-owner --no-privileges --exit-on-error /backup/${db}.dump; "
  script+="pg_dump -h postgres -U '$PG_USER' -Fp --no-owner --no-privileges -f /tmp/actual.sql ${db}; "
  script+="pg_dump -h postgres -U '$PG_USER' -Fp --no-owner --no-privileges -f /tmp/expected.sql '$reference_db'; "
  script+="sed -e '/^--/d' -e '/^SET/d' -e '/^SELECT pg_catalog.set_config/d' -e '/^\\\\restrict/d' -e '/^\\\\unrestrict/d' /tmp/expected.sql > /tmp/expected.norm; "
  script+="sed -e '/^--/d' -e '/^SET/d' -e '/^SELECT pg_catalog.set_config/d' -e '/^\\\\restrict/d' -e '/^\\\\unrestrict/d' /tmp/actual.sql > /tmp/actual.norm; "
  script+="if diff -q /tmp/expected.norm /tmp/actual.norm >/dev/null; then dropdb -h postgres -U '$PG_USER' '$reference_db'; echo '${db}_logical_match'; "
  script+="else echo 'MISMATCH in ${db}: первые отличия (expected < > actual):'; diff /tmp/expected.norm /tmp/actual.norm | head -n 40 || true; "
  script+="echo 'Справочная база $reference_db оставлена для разбора. Восстановленная ${db} не тронута.'; exit 3; fi"

  set +e
  output="$(docker run --rm --network "$network" -e PGPASSWORD="$PG_PASSWORD" -e PGUSER="$PG_USER" \
    -v "$BACKUP_DIR:/backup:ro" postgres:16-alpine sh -c "$script" 2>&1)"
  code=$?
  set -e
  printf '%s\n' "$output"
  [[ "$code" -eq 0 ]] || die "сверка $db не прошла (код $code): смотрите шаг и отличия выше"
  [[ "$output" == *"${db}_logical_match"* ]] \
    || die "сверка $db не подтвердила совпадение: маркера ${db}_logical_match нет. Успех без маркера не засчитывается."
  verified+=("$db")
done

[[ "${#verified[@]}" -eq "${#DATABASES[@]}" ]] \
  || die "проверено баз: ${#verified[@]} из ${#DATABASES[@]}. Сверка неполная."

echo "Restore verification OK: ${#DATABASES[@]} databases and ${#VOLUME_ARCHIVES[@]} file volumes match the backup."
echo "Приложения не запускались. Изолированный PostgreSQL и тома оставлены на месте."
