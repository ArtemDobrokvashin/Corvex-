#!/usr/bin/env bash
#
# Corvex backup для Linux-сервера: дампы четырёх баз PostgreSQL, архивы файловых
# томов, конфигурация и манифест с SHA-256. Аналог scripts/backup.ps1 один в один
# по составу и по правилам; различия только там, где их требует платформа.
#
# Что делает так же, как PowerShell-версия:
#   - дамп `pg_dump -Fc -f` внутри контейнера, затем `docker compose cp` (без
#     текстового перенаправления: на Windows оно портило бинарный формат);
#   - тома монтируются в `:ro`, архивы читаются `alpine tar`;
#   - `.env`, `docker-compose.yml`, `infra/certs`, `infra/secrets` копируются
#     целиком — в них секреты, поэтому копия должна лежать на шифрованном носителе;
#   - Elasticsearch не входит: индекс перестраивается из баз.
#
# Дополнительно пишется `manifest.sha256` — обычный `sha256sum`-формат, чтобы
# сверку мог сделать любой POSIX-инструмент, без Python и без PowerShell.
#
# Пример:
#   ./scripts/backup.sh --backup-root /srv/backup
#   ./scripts/backup.sh -p corvex --backup-root /mnt/nas/corvex

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKUP_ROOT="/srv/backup"
PROJECT_NAME="corvex"
DATABASES=(auth_db admin_db mail_db chat_db)

usage() {
  sed -n '2,30p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() {
  printf 'ОШИБКА: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup-root) BACKUP_ROOT="${2:?--backup-root требует значение}"; shift 2 ;;
    -p|--project) PROJECT_NAME="${2:?--project требует значение}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "неизвестный аргумент: $1 (см. --help)" ;;
  esac
done

[[ "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "недопустимое имя compose-проекта: $PROJECT_NAME"
command -v docker >/dev/null 2>&1 || die "docker не найден в PATH"

cd "$REPO_ROOT"

mkdir -p "$BACKUP_ROOT"
# Абсолютный путь обязателен: его монтирует контейнер alpine.
BACKUP_ROOT="$(cd "$BACKUP_ROOT" && pwd)"
STAMP="$(date +%Y-%m-%d_%H%M%S)"
DIR="$BACKUP_ROOT/$STAMP"
mkdir -p "$DIR"

# Пользователя берём у работающего контейнера: хостовый bash не читает .env, а
# пароль базы задаётся при первом создании тома и в .env может уже не совпадать.
PG_USER="$(docker compose -p "$PROJECT_NAME" exec -T postgres printenv POSTGRES_USER | tr -d '\r')" \
  || die "не удалось прочитать POSTGRES_USER из контейнера postgres"
[[ -n "$PG_USER" ]] || die "POSTGRES_USER пуст — стек не запущен?"

for db in "${DATABASES[@]}"; do
  container_dump="/tmp/corvex-backup-$db.dump"
  host_dump="$DIR/$db.dump"
  docker compose -p "$PROJECT_NAME" exec -T postgres \
    pg_dump -U "$PG_USER" -Fc -f "$container_dump" "$db" \
    || die "pg_dump $db не прошёл"
  docker compose -p "$PROJECT_NAME" cp "postgres:$container_dump" "$host_dump" \
    || die "docker compose cp для $db не прошёл"
  docker compose -p "$PROJECT_NAME" exec -T postgres rm -f "$container_dump" >/dev/null 2>&1 || true
  [[ -s "$host_dump" ]] || die "дамп базы пустой или отсутствует: $host_dump"
  printf '  дамп %-10s %s байт\n' "$db" "$(stat -c %s "$host_dump")"
done

# Сначала базы, потом файлы: это тот же порядок, что в PowerShell-версии, и он
# даёт восстановимую точку — письма, на которые ссылается база, уже на месте.
declare -A VOLUMES=(
  ["mail-data"]="mail-data.tar.gz"
  ["attachment-data"]="attachments.tar.gz"
  ["chat-file-data"]="chat-files.tar.gz"
  ["alert-relay-data"]="alert-relay-data.tar.gz"
)
for volume in mail-data attachment-data chat-file-data alert-relay-data; do
  archive="${VOLUMES[$volume]}"
  docker run --rm \
    -v "${PROJECT_NAME}_${volume}:/data:ro" \
    -v "$DIR:/backup" \
    alpine tar czf "/backup/$archive" -C /data . \
    || die "архив тома ${PROJECT_NAME}_${volume} не создан"
  [[ -s "$DIR/$archive" ]] || die "архив тома пустой: $archive"
  printf '  том  %-20s %s\n' "${PROJECT_NAME}_${volume}" "$(stat -c %s "$DIR/$archive") байт"
done

cp .env docker-compose.yml "$DIR/"
cp -r infra/certs infra/secrets "$DIR/"
chmod 600 "$DIR/.env" || true

GIT_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"

# Манифест собирается из уже посчитанных файлов: порядок стабильный (sort), чтобы
# две копии одного состояния давали одинаковый manifest.sha256.
(
  cd "$DIR"
  find . -type f ! -name manifest.sha256 ! -name manifest.json -printf '%P\n' \
    | LC_ALL=C sort \
    | while IFS= read -r relative; do
        case "$relative" in
          *'"'*|*'\\'*) die "в манифест нельзя положить путь с кавычкой или обратной косой: $relative" ;;
        esac
        bytes="$(stat -c %s "$relative")"
        sha="$(sha256sum "$relative" | cut -d' ' -f1)"
        printf '%s\t%s\t%s\n' "$relative" "$bytes" "$sha"
      done > .manifest.tsv
)

python3 - "$DIR" "$GIT_SHA" "$PROJECT_NAME" <<'PY' || die "не удалось собрать manifest.json (нужен python3)"
import json, sys
from datetime import datetime, timezone
from pathlib import Path

directory, git_sha, project = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
files = []
for line in (directory / ".manifest.tsv").read_text(encoding="utf-8").splitlines():
    path, size, sha = line.split("\t")
    files.append({"path": path, "bytes": int(size), "sha256": sha})
payload = {
    "version": 1,
    "created_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "git_sha": git_sha,
    "source_project": project,
    "files": files,
}
(directory / "manifest.json").write_text(
    json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
)
with (directory / "manifest.sha256").open("w", encoding="utf-8") as handle:
    for entry in files:
        handle.write(f"{entry['sha256']}  {entry['path']}\n")
PY
rm -f "$DIR/.manifest.tsv"

printf 'Backup OK: %s\n' "$DIR"
printf 'Проверка целостности: (cd %s && sha256sum -c manifest.sha256 --quiet)\n' "$DIR"
