# =============================================================================
# Corvex — подготовка Ubuntu-сервера перед первым `docker compose up`.
#
# Запуск (на самом сервере, из корня репозитория):
#   sudo ./scripts/prepare-ubuntu.sh                 # подготовить
#   sudo ./scripts/prepare-ubuntu.sh --check         # только показать, что не так
#   sudo ./scripts/prepare-ubuntu.sh --ip 203.0.113.10
#
# Что делает и почему — только то, без чего стек на чистой Ubuntu не поднимется:
#
#   1. проверяет, что это Linux с systemd и что мы root (иначе нужен sudo);
#   2. ставит пакеты, которых нет в минимальном образе Ubuntu Server:
#      ca-certificates, curl, git, openssl, python3 (+ venv не нужен);
#   3. ставит Docker Engine и плагин compose из официального репозитория Docker,
#      а не из snap: snap-версия не видит тома иначе и хуже дружит с compose;
#   4. добавляет пользователя-вызывающего в группу docker — иначе каждая команда
#      требует sudo, а скрипты вызывают docker напрямую;
#   5. поднимает `vm.max_map_count=262144`: без этого Elasticsearch уходит в
#      рестарт-петлю на любом Linux, и в логах это выглядит как «мало памяти»;
#   6. если передано `--ip` или `--domain` — вписывает их в `.env`, иначе
#      интерфейс будет открываться только по имени из самоподписанного
#      сертификата, а браузер по IP получит отказ CORS;
#   7. включает автозапуск Docker после перезагрузки;
#   8. проверяет свободное место и предупреждает, если его меньше 100 ГиБ.
#
# Чего НЕ делает: не настраивает firewall за вас, не выпускает сертификат
# Let's Encrypt (для него нужен домен и открытый 80), не меняет SSH и не
# трогает уже существующий Docker иначе как через свой репозиторий.
# =============================================================================

set -Eeuo pipefail

CHECK_ONLY=0
PUBLIC_IP=""
DOMAIN=""
DOCKER_USER="${SUDO_USER:-}"
SKIP_DOCKER=0

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() { printf 'ОШИБКА: %s\n' "$*" >&2; exit 1; }
say() { printf '%s\n' "$*"; }

usage() {
  sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) CHECK_ONLY=1; shift ;;
    --ip) PUBLIC_IP="${2:?--ip требует значение}"; shift 2 ;;
    --domain) DOMAIN="${2:?--domain требует значение}"; shift 2 ;;
    --user) DOCKER_USER="${2:?--user требует значение}"; shift 2 ;;
    --skip-docker) SKIP_DOCKER=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "неизвестный аргумент: $1 (см. --help)" ;;
  esac
done

# --- 1. Окружение -----------------------------------------------------------

[[ "$(uname -s)" == "Linux" ]] || die "скрипт для Linux; на Windows используйте scripts/preflight-server.ps1"
command -v systemctl >/dev/null 2>&1 || die "нет systemd: это не Ubuntu Server, скрипт рассчитан на него"

if [[ "$CHECK_ONLY" -eq 0 && "$(id -u)" -ne 0 ]]; then
  die "нужен root: sudo ./scripts/prepare-ubuntu.sh $( [[ -n "$PUBLIC_IP" ]] && echo "--ip $PUBLIC_IP" )"
fi

if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  say "Система: ${PRETTY_NAME:-Linux}"
  case "${ID:-}" in
    ubuntu|debian) ;;
    *) say "ВНИМАНИЕ: скрипт проверялся на Ubuntu 22.04/24.04; на ${ID:-этой системе} возможны отличия в apt." ;;
  esac
fi

problems=0
note_problem() { say "  [!] $*"; problems=$((problems + 1)); }

say ""
say "Проверка окружения:"

missing_packages=()
for package in ca-certificates curl git openssl python3; do
  if ! dpkg -s "$package" >/dev/null 2>&1; then
    missing_packages+=("$package")
  fi
done
if [[ "${#missing_packages[@]}" -gt 0 ]]; then
  note_problem "нет пакетов: ${missing_packages[*]}"
else
  say "  [ok] базовые пакеты на месте"
fi

docker_ok=0
if command -v docker >/dev/null 2>&1; then
  if docker compose version >/dev/null 2>&1; then
    say "  [ok] Docker и плагин compose: $(docker --version | cut -d, -f1)"
    docker_ok=1
  else
    note_problem "Docker есть, а плагина compose нет (нужен docker-compose-plugin)"
  fi
  if [[ "$(docker info --format '{{.OSType}}' 2>/dev/null || true)" != "linux" ]]; then
    note_problem "демон Docker не отвечает или это не Linux-движок"
  fi
else
  note_problem "Docker не установлен"
fi

map_count="$(cat /proc/sys/vm/max_map_count 2>/dev/null || echo 0)"
if [[ "$map_count" -lt 262144 ]]; then
  note_problem "vm.max_map_count=$map_count (нужно ≥ 262144 для Elasticsearch)"
else
  say "  [ok] vm.max_map_count=$map_count"
fi

free_gib="$(df -Pk "$REPO_ROOT" | awk 'NR==2 {print int($4/1024/1024)}')"
if [[ "$free_gib" -lt 100 ]]; then
  note_problem "свободно ${free_gib} ГиБ под $REPO_ROOT (нужно ≥ 100: образы ~25 ГиБ плюс данные)"
else
  say "  [ok] свободно ${free_gib} ГиБ"
fi

if ! getent group docker >/dev/null 2>&1; then
  note_problem "нет группы docker (создастся при установке Docker)"
elif [[ -n "$DOCKER_USER" ]] && ! id -nG "$DOCKER_USER" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
  note_problem "пользователь $DOCKER_USER не в группе docker: команды потребуют sudo"
else
  say "  [ok] доступ к docker есть"
fi

if systemctl is-enabled --quiet docker 2>/dev/null; then
  say "  [ok] Docker включён в автозапуск"
else
  note_problem "Docker не включён в автозапуск: после перезагрузки стек не поднимется"
fi

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  say "  [i] ufw включён — порты открываются отдельно, см. docs/INSTALL.md §1"
fi

say ""
if [[ "$CHECK_ONLY" -eq 1 ]]; then
  say "Найдено замечаний: $problems"
  [[ "$problems" -eq 0 ]] && exit 0 || exit 1
fi

# --- 2. Пакеты ---------------------------------------------------------------

say "Устанавливаю базовые пакеты..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl git openssl python3 lsb-release gnupg >/dev/null
say "  [ok] пакеты установлены"

# --- 3. Docker Engine из официального репозитория ----------------------------

if [[ "$SKIP_DOCKER" -eq 0 ]]; then
  if [[ "$docker_ok" -eq 0 ]]; then
    say "Устанавливаю Docker Engine и плагин compose..."
    install -m 0755 -d /etc/apt/keyrings
    if [[ ! -f /etc/apt/keyrings/docker.asc ]]; then
      curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
      chmod a+r /etc/apt/keyrings/docker.asc
    fi
    arch="$(dpkg --print-architecture)"
    codename="$(. /etc/os-release && echo "${VERSION_CODENAME}")"
    # shellcheck disable=SC1091
    printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' \
      "$arch" "$codename" > /etc/apt/sources.list.d/docker.list
    apt-get update -qq
    apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null
    say "  [ok] Docker установлен"
  else
    say "Docker уже установлен — не трогаю (обновление отдельным решением)."
  fi

  systemctl enable --now docker >/dev/null 2>&1
  say "  [ok] Docker включён в автозапуск"

  if [[ -n "$DOCKER_USER" && "$DOCKER_USER" != "root" ]]; then
    if ! id -nG "$DOCKER_USER" | tr ' ' '\n' | grep -qx docker; then
      usermod -aG docker "$DOCKER_USER"
      say "  [ok] $DOCKER_USER добавлен в группу docker (перелогиньтесь, чтобы применилось)"
    else
      say "  [ok] $DOCKER_USER уже в группе docker"
    fi
  fi
fi

# --- 4. Elasticsearch: vm.max_map_count -------------------------------------

# Без этого Elasticsearch падает с "max virtual memory areas vm.max_map_count is
# too low" и перезапускается по кругу. На Ubuntu значение по умолчанию 65530,
# то есть меньше требуемых 262144, — то есть это не «если понадобится», а
# обязательный шаг на любом чистом сервере.
if [[ "$(cat /proc/sys/vm/max_map_count)" -lt 262144 ]]; then
  sysctl -w vm.max_map_count=262144 >/dev/null
  if ! grep -q '^vm.max_map_count' /etc/sysctl.conf 2>/dev/null; then
    printf '\n# Corvex: Elasticsearch требует не меньше 262144 (docs/INSTALL.md)\nvm.max_map_count=262144\n' >> /etc/sysctl.conf
  fi
  say "  [ok] vm.max_map_count=262144 (постоянно, через /etc/sysctl.conf)"
else
  say "  [ok] vm.max_map_count уже достаточен"
fi

# --- 5. Адрес в .env: вход по IP без домена ---------------------------------

# nginx слушает 443 на любом имени, но web-gateway проверяет Origin по
# CORVEX_WEB_ORIGINS. Пока там только https://mail.company.local, браузер по
# https://<IP> получает отказ на каждом запросе, и вход «не работает» без
# единой ошибки в логах. Поэтому адрес, по которому реально откроют сервер,
# обязан попасть в .env — до первого `docker compose up`.
if [[ -n "$PUBLIC_IP" || -n "$DOMAIN" ]]; then
  [[ -f "$REPO_ROOT/.env" ]] || die "нет $REPO_ROOT/.env: сначала запустите ./scripts/first-run.sh --domain ..."
  cd "$REPO_ROOT"
  origin_args=()
  [[ -n "$PUBLIC_IP" ]] && origin_args+=(--ip "$PUBLIC_IP")
  [[ -n "$DOMAIN" ]] && origin_args+=(--domain "$DOMAIN")
  # Отдельным скриптом, а не heredoc: разбор .env должен быть проверяемым тестом,
  # а не текстом внутри другого скрипта.
  python3 scripts/ubuntu_origin.py --env .env "${origin_args[@]}"
fi

# --- 6. Итог ----------------------------------------------------------------

say ""
say "Готово. Следующие шаги:"
say "  1) перелогиньтесь (или выполните 'newgrp docker'), чтобы работать без sudo;"
say "  2) ./scripts/first-run.sh ${DOMAIN:+--domain $DOMAIN }${PUBLIC_IP:+$([[ -z "$DOMAIN" ]] && echo \"--public-base-url https://$PUBLIC_IP\")}"
say "  3) python3 scripts/preflight.py"
say "  4) docker compose up -d --build"
say "  5) ./scripts/backup.sh --backup-root /srv/backup   # сразу, до появления данных"
say ""
say "Отдельно, если сервер смотрит в интернет: firewall и порты — docs/INSTALL.md §1,"
say "сертификат Let's Encrypt — §6 (нужны домен и открытый 80)."
