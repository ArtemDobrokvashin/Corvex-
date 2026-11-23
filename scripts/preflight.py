#!/usr/bin/env python3
"""Предполётная проверка перед первым `docker compose up`.

Зачем отдельный скрипт, если есть `docs/INSTALL.md`. Установка падает не на коде
Corvex, а на окружении: Docker не запущен, compose не видит `.env`, в `.env`
остались заглушки, занят порт 80, в VM меньше памяти, чем нужно Elasticsearch.
Каждая такая ошибка стоит одного раунда «пришлите вывод», а ищется за секунду.

Скрипт только читает: он не пишет `.env`, не создаёт тома, не запускает и не
перезапускает контейнеры. Единственная внешняя команда, которая что-то делает, —
`docker compose config -q`: она разбирает `docker-compose.yml` и `.env` и
завершается, ничего не создавая.

Проверяется:

  1. Docker и compose: CLI, плагин compose, запущенный демон, Linux-движок;
  2. ресурсы: vCPU, RAM, свободное место под каталогом репозитория;
  3. порты 80, 443, 25, 587, 143, 993, 995, 8080 на 0.0.0.0;
  4. `.env`: наличие, BOM, CRLF, дубли ключей, заглушки, обязательные ключи
     из `${VAR:?…}` в `docker-compose.yml`, непустые значения;
  5. ключи и сертификаты в `infra/secrets` и `infra/certs`, права на приватные;
  6. `docker compose config -q`;
  7. `vm.max_map_count` для Elasticsearch;
  8. git: ветка и несохранённые изменения.

Коды возврата: 0 — провалов нет, 1 — есть FAIL, 2 — `--strict` и есть WARN.

Примеры:
    python3 scripts/preflight.py
    python3 scripts/preflight.py --strict
    python3 scripts/preflight.py --root /opt/corvex --json
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

PASS, WARN, FAIL = "PASS", "WARN", "FAIL"

# Порты из docs/INSTALL.md плюс 8080: контейнер frontend публикует его для
# отладки, и на ноутбуке он часто занят другим dev-сервером.
PORTS = (80, 443, 25, 587, 143, 993, 995, 8080)

# Значения-заглушки из .env.example. Их наличие означает, что first-run не
# запускали или запускали на файле, который потом заменили обратно.
PLACEHOLDERS = (
    "change-me-strong-password",
    "base64-32-byte-key-for-aes-gcm",
)

# Минимумы из docs/SERVER-REQUIREMENTS.md, но для ноутбука/VM они ниже:
# с меньшим Elasticsearch не стартует или падает по памяти.
DEFAULT_MIN_CPU = 4
DEFAULT_MIN_RAM_GIB = 8.0
DEFAULT_MIN_DISK_GIB = 40.0
MIN_MAX_MAP_COUNT = 262144
SYSCTL_MAX_MAP_COUNT = Path("/proc/sys/vm/max_map_count")


@dataclass
class Thresholds:
    cpu: int = DEFAULT_MIN_CPU
    ram_gib: float = DEFAULT_MIN_RAM_GIB
    disk_gib: float = DEFAULT_MIN_DISK_GIB
    sysctl_file: Path = SYSCTL_MAX_MAP_COUNT


@dataclass
class Result:
    check: str
    status: str
    detail: str

    def line(self, color: bool) -> str:
        head = f"[{self.status}] {self.check}"
        text = f"{head} - {self.detail}" if self.detail else head
        if not color:
            return text
        code = {PASS: "32", WARN: "33", FAIL: "31"}.get(self.status, "0")
        return f"\033[{code}m{text}\033[0m"


def run(command: list[str], cwd: Path | None = None, timeout: int = 30) -> tuple[int, str]:
    """Запускает команду и возвращает (код, объединённый вывод).

    Отсутствие команды — это не исключение, а код 127: проверка окружения
    обязана сообщить «Docker не установлен», а не упасть трассировкой.
    """
    try:
        completed = subprocess.run(
            command, cwd=cwd, capture_output=True, text=True, timeout=timeout
        )
    except FileNotFoundError:
        return 127, f"команда не найдена: {command[0]}"
    except subprocess.TimeoutExpired:
        return 124, f"таймаут {timeout}с: {' '.join(command)}"
    output = (completed.stdout + completed.stderr).strip()
    return completed.returncode, output


def first_line(text: str, limit: int = 160) -> str:
    line = text.strip().splitlines()[0] if text.strip() else ""
    return line[:limit]


def parse_env(path: Path) -> tuple[dict[str, str], list[str]]:
    """Разбирает .env так же строго, как это делает compose, и возвращает замечания.

    Своя логика нужна из-за BOM: `docker compose` читает `.env` парсером
    compose-go, где BOM не считается пробелом, а входит в имя первой
    переменной. Windows PowerShell 5.1 пишет `Set-Content -Encoding UTF8`
    именно с BOM, поэтому файл, созданный на Windows, ломает разбор.
    """
    problems: list[str] = []
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        problems.append(
            "BOM в начале .env: compose-go считает его частью имени переменной "
            "и падает на разборе; первая строки станет нечитаемой"
        )
        raw = raw[3:]
    text = raw.decode("utf-8", errors="replace")
    if "\r\n" in text:
        problems.append("CRLF в .env: compose это терпит, но .env.example хранится с LF")
    values: dict[str, str] = {}
    duplicates: list[str] = []
    for number, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in stripped:
            problems.append(f"строка {number}: нет '=' ({stripped[:40]})")
            continue
        key, value = stripped.split("=", 1)
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            problems.append(f"строка {number}: недопустимое имя переменной {key!r}")
        if key in values:
            duplicates.append(key)
        values[key] = value.strip()
    for key in duplicates:
        problems.append(f"ключ {key} встречается больше одного раза: победит последнее значение")
    return values, problems


def compose_required(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8") if path.exists() else ""
    return set(re.findall(r"\$\{([A-Z][A-Z0-9_]*):\?", text))


def listening_ports() -> set[int] | None:
    """Порты в состоянии LISTEN по /proc/net/tcp{,6}; None — если файлов нет.

    Через /proc, а не через bind-пробу: bind на порт < 1024 без root даёт
    EACCES, и проверка «свободен ли 80» отвечала бы «занят» на любой
    непривилегированной установке. Это была бы ложная тревога в самом
    важном месте — как раз перед первым запуском.
    """
    ports: set[int] = set()
    found = False
    for name in ("/proc/net/tcp", "/proc/net/tcp6"):
        path = Path(name)
        if not path.exists():
            continue
        found = True
        for line in path.read_text().splitlines()[1:]:
            fields = line.split()
            if len(fields) < 4 or fields[3] != "0A":  # 0A = TCP_LISTEN
                continue
            ports.add(int(fields[1].split(":")[1], 16))
    return ports if found else None


def port_is_free(port: int) -> bool:
    listening = listening_ports()
    if listening is not None:
        return port not in listening
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("0.0.0.0", port))
        except OSError:
            return False
    return True


def total_ram_gib() -> float | None:
    try:
        pages = os.sysconf("SC_PHYS_PAGES")
        page_size = os.sysconf("SC_PAGE_SIZE")
        return pages * page_size / 1024 ** 3
    except (ValueError, OSError, AttributeError):
        return None


def check_docker(root: Path, results: list[Result]) -> None:
    code, output = run(["docker", "--version"])
    if code != 0:
        results.append(Result("docker", FAIL, "Docker CLI не найден или не отвечает: " + first_line(output)))
        return
    results.append(Result("docker", PASS, first_line(output)))

    code, output = run(["docker", "compose", "version"])
    if code != 0:
        results.append(Result("docker compose", FAIL, "плагин compose не установлен: " + first_line(output)))
        return
    results.append(Result("docker compose", PASS, first_line(output)))

    code, output = run(["docker", "info", "--format", "{{.OSType}}"])
    if code != 0:
        results.append(
            Result("docker daemon", FAIL, "демон не отвечает (Docker Desktop не запущен?): " + first_line(output))
        )
        return
    engine = output.strip()
    if engine != "linux":
        results.append(
            Result("docker daemon", FAIL, f"движок {engine or '?'}: нужны Linux-контейнеры (WSL2/Hyper-V)")
        )
    else:
        results.append(Result("docker daemon", PASS, "движок linux"))


def check_host_readiness(results: list[Result]) -> None:
    """То, без чего Ubuntu-сервер «работает», но не переживает перезагрузку.

    Две проверки, обе из настоящих отказов: пользователь не в группе docker —
    каждый скрипт требует sudo и падает на первой команде; Docker не включён в
    автозапуск — после перезагрузки сервера стек не поднимается, а причина видна
    только тому, кто помнит про systemctl.
    """
    if platform.system() != "Linux":
        return

    user = os.environ.get("SUDO_USER") or os.environ.get("USER") or ""
    if user:
        try:
            in_group = "docker" in subprocess.run(
                ["id", "-nG", user], capture_output=True, text=True, check=False
            ).stdout.split()
        except OSError:
            in_group = False
        if in_group:
            results.append(Result("docker group", PASS, f"{user} может вызывать docker без sudo"))
        else:
            results.append(
                Result("docker group", WARN,
                       f"{user} не в группе docker: скрипты вызовут docker и получат отказ. "
                       "sudo usermod -aG docker " + user + " и перелогиниться")
            )

    code, output = run(["systemctl", "is-enabled", "docker"])
    if code == 0:
        results.append(Result("docker autostart", PASS, "включён"))
    else:
        results.append(
            Result("docker autostart", WARN,
                   "Docker не включён в автозапуск: после перезагрузки стек не поднимется. "
                   "sudo systemctl enable --now docker")
        )


def check_resources(root: Path, results: list[Result], limits: Thresholds) -> None:
    cpu = os.cpu_count() or 0
    if cpu < limits.cpu:
        results.append(Result("vCPU", FAIL, f"{cpu} < {limits.cpu}: Java-сервисы не уложатся в память и таймауты"))
    else:
        results.append(Result("vCPU", PASS, f"{cpu}"))

    ram = total_ram_gib()
    if ram is None:
        results.append(Result("RAM", WARN, "не удалось прочитать: проверьте вручную (нужно ≥ 8 ГиБ)"))
    elif ram < limits.ram_gib / 2:
        results.append(Result("RAM", FAIL, f"{ram:.1f} ГиБ: Elasticsearch требует ≥ 2,5 ГиБ только себе"))
    elif ram < limits.ram_gib:
        results.append(Result("RAM", WARN, f"{ram:.1f} ГиБ < {limits.ram_gib:.0f} ГиБ: возможны падения Elasticsearch"))
    else:
        results.append(Result("RAM", PASS, f"{ram:.1f} ГиБ"))

    free_gib = shutil.disk_usage(root).free / 1024 ** 3
    if free_gib < limits.disk_gib / 2:
        results.append(Result("disk", FAIL, f"{free_gib:.1f} ГиБ свободно под {root}: мало для сборки и томов"))
    elif free_gib < limits.disk_gib:
        results.append(Result("disk", WARN, f"{free_gib:.1f} ГиБ < {limits.disk_gib:.0f} ГиБ"))
    else:
        results.append(Result("disk", PASS, f"{free_gib:.1f} ГиБ свободно"))


def check_ports(results: list[Result]) -> None:
    busy = [port for port in PORTS if not port_is_free(port)]
    if not busy:
        results.append(Result("ports", PASS, ", ".join(str(p) for p in PORTS) + " свободны"))
        return
    # `docker compose ps --quiet` печатает ID запущенных контейнеров и
    # завершается успешно в любом случае, поэтому смотрим на вывод, а не на код.
    running = run(["docker", "compose", "ps", "--quiet"])[1].strip()
    detail = "заняты: " + ", ".join(str(p) for p in busy)
    if running:
        results.append(Result("ports", WARN, detail + " (возможно, это уже запущенный стек Corvex)"))
    else:
        results.append(Result("ports", FAIL, detail + " — освободите порты или остановите чужой сервис"))


def check_env(root: Path, results: list[Result]) -> None:
    env_path = root / ".env"
    if not env_path.exists():
        results.append(
            Result(".env", FAIL, "файла нет: выполните scripts/first-run.sh (Linux-VM) или scripts/first-run.ps1")
        )
        return
    values, problems = parse_env(env_path)
    if problems:
        results.append(Result(".env", FAIL, "; ".join(problems)))
    elif values:
        results.append(Result(".env", PASS, f"{len(values)} переменных, разбор без замечаний"))
    else:
        results.append(Result(".env", FAIL, "файл пуст"))

    placeholders = sorted(
        key for key, value in values.items() if any(p in value for p in PLACEHOLDERS)
    )
    if placeholders:
        results.append(
            Result(
                ".env заглушки",
                FAIL,
                "остались значения из .env.example: " + ", ".join(placeholders)
                + " (TOTP, внешние ящики и секреты алертов ответят 503)",
            )
        )
    else:
        results.append(Result(".env заглушки", PASS, "ни одного change-me / base64-заглушки"))

    required = compose_required(root / "docker-compose.yml")
    missing = sorted(key for key in required if not values.get(key))
    if missing:
        results.append(
            Result(".env обязательные", FAIL, "compose требует, но в .env нет: " + ", ".join(missing))
        )
    else:
        results.append(Result(".env обязательные", PASS, f"{len(required)} ключей с :? заполнены"))


def check_secrets(root: Path, results: list[Result]) -> None:
    expected = {
        "infra/secrets/jwt_private.pem": (True, 0o600),
        "infra/secrets/jwt_public.pem": (False, 0o644),
        "infra/secrets/dkim_private.pem": (True, 0o600),
        "infra/secrets/dkim_public.pem": (False, 0o644),
        "infra/certs/fullchain.pem": (False, 0o644),
        "infra/certs/privkey.pem": (True, 0o600),
    }
    missing = [name for name in expected if not (root / name).exists()]
    if missing:
        results.append(
            Result("keys/certs", FAIL, "нет файлов: " + ", ".join(missing) + " — выполните first-run заново")
        )
        return
    results.append(Result("keys/certs", PASS, f"{len(expected)} файлов на месте"))

    loose = []
    for name, (private, mode) in expected.items():
        if not private:
            continue
        actual = (root / name).stat().st_mode & 0o777
        if actual & 0o077:
            loose.append(f"{name} {oct(actual)}")
    if loose:
        results.append(
            Result("keys права", WARN, "приватные ключи читают другие: " + ", ".join(loose))
        )
    else:
        results.append(Result("keys права", PASS, "приватные ключи 0600"))


def check_compose_config(root: Path, results: list[Result]) -> None:
    code, output = run(["docker", "compose", "config", "-q"], cwd=root, timeout=60)
    if code == 0:
        results.append(Result("compose config", PASS, "docker-compose.yml и .env разбираются"))
    elif code == 127:
        results.append(Result("compose config", WARN, "пропущено: docker не найден"))
    else:
        results.append(Result("compose config", FAIL, first_line(output, 300)))


def check_elasticsearch_sysctl(results: list[Result], limits: Thresholds) -> None:
    path = limits.sysctl_file
    if not path.exists():
        return
    try:
        value = int(path.read_text().strip())
    except (OSError, ValueError):
        results.append(Result("vm.max_map_count", WARN, "не удалось прочитать"))
        return
    if value < MIN_MAX_MAP_COUNT:
        results.append(
            Result(
                "vm.max_map_count",
                FAIL,
                f"{value} < {MIN_MAX_MAP_COUNT}: Elasticsearch упадёт; "
                "sudo sysctl -w vm.max_map_count=262144",
            )
        )
    else:
        results.append(Result("vm.max_map_count", PASS, str(value)))


def compose_project_name(root: Path) -> str | None:
    """Имя проекта, с которым соберётся стек.

    Приоритет как у compose: флаг -p, затем COMPOSE_PROJECT_NAME, затем
    поле `name:` в docker-compose.yml. Ошибка здесь стоит дорого: при
    неверном имени установка молча подхватит тома другого стенда.
    """
    env_name = os.environ.get("COMPOSE_PROJECT_NAME", "").strip()
    if env_name:
        return env_name
    compose = root / "docker-compose.yml"
    if not compose.exists():
        return None
    for line in compose.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if stripped.startswith("name:"):
            return stripped.split(":", 1)[1].strip().strip("'\"") or None
    return None


def check_project_volumes(root: Path, results: list[Result]) -> None:
    """Предупреждает, что имя проекта уже занято томами другого стенда.

    `docker-compose.yml` фиксирует `name: corvex`, поэтому клон в новом
    каталоге — это НЕ чистая установка: compose переиспользует старые тома.
    На стенде это дало `password authentication failed for user "corvex"`:
    сервисы читали новый пароль из .env, а PostgreSQL жил на томе, где пароль
    был задан при первой инициализации.
    """
    project = compose_project_name(root)
    if not project:
        results.append(Result("project volumes", WARN, "имя проекта не определено: пропущено"))
        return
    code, output = run(["docker", "volume", "ls", "--filter", f"label=com.docker.compose.project={project}",
                        "--format", "{{.Name}}"])
    if code != 0:
        results.append(Result("project volumes", WARN, f"не удалось прочитать тома проекта {project}"))
        return
    volumes = [line.strip() for line in output.splitlines() if line.strip()]
    if not volumes:
        results.append(Result("project volumes", PASS, f"проект {project}: томов нет, установка чистая"))
        return
    results.append(
        Result(
            "project volumes",
            WARN,
            f"проект {project} уже имеет {len(volumes)} том(ов): это не чистая установка. "
            f"Для приёмки P4 запустите с другим именем: docker compose -p {project}-fresh up -d --build",
        )
    )


def check_git(root: Path, results: list[Result]) -> None:
    code, output = run(["git", "status", "--porcelain"], cwd=root)
    if code != 0:
        results.append(Result("git", WARN, "не репозиторий или git недоступен"))
        return
    dirty = [line for line in output.splitlines() if line.strip()]
    _, branch = run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=root)
    if dirty:
        results.append(
            Result("git", WARN, f"ветка {branch.strip()}, незакоммиченных файлов: {len(dirty)}")
        )
    else:
        results.append(Result("git", PASS, f"ветка {branch.strip()}, дерево чистое"))


def collect(root: Path, limits: Thresholds | None = None) -> list[Result]:
    limits = limits or Thresholds()
    results: list[Result] = []
    check_docker(root, results)
    check_resources(root, results, limits)
    check_host_readiness(results)
    check_ports(results)
    check_env(root, results)
    check_secrets(root, results)
    check_compose_config(root, results)
    check_elasticsearch_sysctl(results, limits)
    check_project_volumes(root, results)
    check_git(root, results)
    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Предполётная проверка Corvex перед docker compose up")
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]), help="корень репозитория")
    parser.add_argument("--strict", action="store_true", help="WARN тоже считается провалом")
    parser.add_argument("--json", action="store_true", help="печать отчёта в JSON")
    parser.add_argument("--no-color", action="store_true", help="без ANSI-цветов")
    parser.add_argument("--min-cpu", type=int, default=DEFAULT_MIN_CPU, help="порог vCPU")
    parser.add_argument("--min-ram-gib", type=float, default=DEFAULT_MIN_RAM_GIB, help="порог RAM, ГиБ")
    parser.add_argument("--min-disk-gib", type=float, default=DEFAULT_MIN_DISK_GIB, help="порог диска, ГиБ")
    parser.add_argument(
        "--sysctl-file",
        default=str(SYSCTL_MAX_MAP_COUNT),
        help="файл vm.max_map_count (по умолчанию /proc/sys/vm/max_map_count)",
    )
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    limits = Thresholds(
        cpu=args.min_cpu,
        ram_gib=args.min_ram_gib,
        disk_gib=args.min_disk_gib,
        sysctl_file=Path(args.sysctl_file),
    )
    results = collect(root, limits)

    failures = [r for r in results if r.status == FAIL]
    warnings = [r for r in results if r.status == WARN]

    if args.json:
        print(json.dumps(
            {
                "root": str(root),
                "pass": sum(1 for r in results if r.status == PASS),
                "warn": len(warnings),
                "fail": len(failures),
                "results": [asdict(r) for r in results],
            },
            ensure_ascii=False,
            indent=2,
        ))
    else:
        color = sys.stdout.isatty() and not args.no_color and os.environ.get("NO_COLOR") is None
        print(f"Corvex preflight: {root}")
        for result in results:
            print(result.line(color))
        print()
        print(f"Итого: {len(results) - len(warnings) - len(failures)} PASS, {len(warnings)} WARN, {len(failures)} FAIL")

    if failures:
        return 1
    if warnings and args.strict:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
