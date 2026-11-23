#!/usr/bin/env bash
#
# Corvex restore из копии, сделанной scripts/backup.sh (или scripts/backup.ps1).
#
# Полное восстановление (перезаписывает данные проекта corvex):
#   ./scripts/restore.sh --backup-dir /srv/backup/2026-11-23_030000 --force
#   ./scripts/restore.sh --backup-dir ... --force --restore-config
#
# Изолированный прогон только данных (рабочий проект не трогается):
#   ./scripts/restore.sh --backup-dir /srv/backup/2026-11-23_030000 \
#     --project corvex-restore-drill --data-only --force
#
# Правила те же, что в scripts/restore.ps1, и они проверяются до любого изменения:
#   - без --force ничего не делаем;
#   - --data-only требует отдельного имени проекта, иначе он перезапишет рабочие тома;
#   - изолированный проект умеет только --data-only: иначе порты конфликтуют с рабочим;
#   - --restore-config только для полного восстановления рабочего проекта;
#   - манифест и SHA-256 проверяются до удаления конфигурации и до старта PostgreSQL.

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKUP_DIR=""
PROJECT_NAME="corvex"
FORCE=0
RESTORE_CONFIG=0
DATA_ONLY=0
DATABASES=(auth_db admin_db mail_db chat_db)

usage() {
  sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() {
  printf 'ОШИБКА: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup-dir) BACKUP_DIR="${2:?--backup-dir требует значение}"; shift 2 ;;
    -p|--project) PROJECT_NAME="${2:?--project требует значение}"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --restore-config) RESTORE_CONFIG=1; shift ;;
    --data-only) DATA_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "неизвестный аргумент: $1 (см. --help)" ;;
  esac
done

[[ -n "$BACKUP_DIR" ]] || die "нужен --backup-dir (см. --help)"
[[ "$FORCE" -eq 1 ]] || die "отказываюсь восстанавливать без --force"
[[ "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "недопустимое имя compose-проекта: $PROJECT_NAME"
[[ -d "$BACKUP_DIR" ]] || die "каталог копии не найден: $BACKUP_DIR"

if [[ "$DATA_ONLY" -eq 1 && "$PROJECT_NAME" == "corvex" ]]; then
  die "--data-only требует отдельного имени проекта, а не рабочего corvex"
fi
if [[ "$DATA_ONLY" -eq 0 && "$PROJECT_NAME" != "corvex" ]]; then
  die "изолированный проект поддерживает только --data-only: иначе публикуются порты"
fi
if [[ "$RESTORE_CONFIG" -eq 1 && ( "$DATA_ONLY" -eq 1 || "$PROJECT_NAME" != "corvex" ) ]]; then
  die "--restore-config допустим только при полном восстановлении рабочего проекта"
fi

command -v docker >/dev/null 2>&1 || die "docker не найден в PATH"
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"
cd "$REPO_ROOT"

# --- проверка копии до первого изменения -----------------------------------

for db in "${DATABASES[@]}"; do
  [[ -f "$BACKUP_DIR/$db.dump" ]] || die "нет дампа базы: $db.dump"
done

VOLUME_ARCHIVES=(mail-data.tar.gz attachments.tar.gz chat-files.tar.gz)
VOLUME_NAMES=(mail-data attachment-data chat-file-data)
# Старые копии сделаны до появления настроек relay: архив справочный, база и ключ
# из .env восстановят конфигурацию заново. Его отсутствие — не ошибка.
if [[ -f "$BACKUP_DIR/alert-relay-data.tar.gz" ]]; then
  VOLUME_ARCHIVES+=(alert-relay-data.tar.gz)
  VOLUME_NAMES+=(alert-relay-data)
fi
for archive in "${VOLUME_ARCHIVES[@]}"; do
  [[ -f "$BACKUP_DIR/$archive" ]] || die "нет архива тома: $archive"
done

# Манифест проверяется ДО удаления конфигурации и до старта базы: иначе битая
# копия обнаруживается уже после того, как рабочие данные удалены.
if [[ -f "$BACKUP_DIR/manifest.sha256" ]]; then
  ( cd "$BACKUP_DIR" && sha256sum -c manifest.sha256 --quiet ) \
    || die "SHA-256 не совпал: копия повреждена или изменилась. Ничего не восстанавливаю."
  echo "Манифест и SHA-256 проверены"
elif [[ -f "$BACKUP_DIR/manifest.json" ]]; then
  python3 - "$BACKUP_DIR" <<'PY' || die "SHA-256 не совпал: копия повреждена. Ничего не восстанавливаю."
import hashlib, json, sys
from pathlib import Path
directory = Path(sys.argv[1])
manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
for entry in manifest.get("files", []):
    relative = entry["path"]
    if Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise SystemExit(f"небезопасный путь в манифесте: {relative}")
    target = directory / relative
    if not target.exists():
        raise SystemExit(f"файла из манифеста нет: {target}")
    if target.stat().st_size != entry["bytes"]:
        raise SystemExit(f"размер не совпал: {target}")
    actual = hashlib.sha256(target.read_bytes()).hexdigest()
    if actual != entry["sha256"]:
        raise SystemExit(f"SHA-256 не совпал: {target}")
print(f"Манифест и SHA-256 проверены: {len(manifest.get('files', []))} файлов")
PY
else
  echo "Манифеста нет: копия старая, проверены только обязательные файлы"
fi

# --- восстановление ---------------------------------------------------------

if [[ "$RESTORE_CONFIG" -eq 1 ]]; then
  STAMP="$(date +%Y-%m-%d_%H%M%S)"
  echo "Текущая конфигурация сохраняется в .pre-restore-$STAMP"
  mkdir -p ".pre-restore-$STAMP"
  cp .env docker-compose.yml ".pre-restore-$STAMP/" 2>/dev/null || true
  cp "$BACKUP_DIR/.env" "$BACKUP_DIR/docker-compose.yml" .
  cp -r "$BACKUP_DIR/certs" "$BACKUP_DIR/secrets" infra/
fi

if [[ "$DATA_ONLY" -eq 1 ]]; then
  echo "Запускаю изолированный PostgreSQL в проекте $PROJECT_NAME..."
  docker compose -p "$PROJECT_NAME" up -d postgres
  postgres_id="$(docker compose -p "$PROJECT_NAME" ps -q postgres)"
  [[ -n "$postgres_id" ]] || die "не удалось найти контейнер postgres изолированного проекта"
  # Метка проекта проверяется до удаления файлов: ошибка в имени проекта иначе
  # означала бы восстановление поверх рабочих томов.
  actual_project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$postgres_id")"
  [[ "$actual_project" == "$PROJECT_NAME" ]] \
    || die "проект не совпал: ожидался $PROJECT_NAME, получен ${actual_project:-<пусто>}"
else
  echo "Останавливаю только проект $PROJECT_NAME (тома сохраняются)..."
  docker compose -p "$PROJECT_NAME" --profile monitoring down
fi

for index in "${!VOLUME_ARCHIVES[@]}"; do
  archive="${VOLUME_ARCHIVES[$index]}"
  volume="${PROJECT_NAME}_${VOLUME_NAMES[$index]}"
  echo "Восстанавливаю том $volume из $archive..."
  docker run --rm -v "$volume:/data" -v "$BACKUP_DIR:/backup:ro" alpine \
    sh -c "find /data -mindepth 1 -delete && tar xzf /backup/$archive -C /data" \
    || die "том $volume не восстановлен"
done

if [[ "$DATA_ONLY" -eq 0 ]]; then
  echo "Запускаю только PostgreSQL в проекте $PROJECT_NAME..."
  docker compose -p "$PROJECT_NAME" up -d postgres
fi

PG_USER="$(docker compose -p "$PROJECT_NAME" exec -T postgres printenv POSTGRES_USER | tr -d '\r')" \
  || die "не удалось прочитать POSTGRES_USER из восстановленного postgres"

ready=0
for _ in $(seq 1 60); do
  if docker compose -p "$PROJECT_NAME" exec -T postgres pg_isready -U "$PG_USER" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done
[[ "$ready" -eq 1 ]] || die "PostgreSQL не стал готовым за 120 секунд"

for db in "${DATABASES[@]}"; do
  echo "Восстанавливаю базу $db..."
  docker compose -p "$PROJECT_NAME" cp "$BACKUP_DIR/$db.dump" "postgres:/tmp/$db.dump"
  docker compose -p "$PROJECT_NAME" exec -T postgres pg_restore \
    -U "$PG_USER" -d "$db" --clean --if-exists --no-owner "/tmp/$db.dump"
  docker compose -p "$PROJECT_NAME" exec -T postgres rm -f "/tmp/$db.dump"
done

if [[ "$DATA_ONLY" -eq 1 ]]; then
  docker compose -p "$PROJECT_NAME" ps
  echo "Изолированный прогон данных завершён. Приложения не запускались."
  echo "Рабочий corvex и его тома не изменялись. Проект: $PROJECT_NAME"
  exit 0
fi

echo "Запускаю весь стек..."
docker compose -p "$PROJECT_NAME" up -d
docker compose -p "$PROJECT_NAME" ps
echo "Восстановление завершено. Проверьте health и вход администратора до открытия доступа."
