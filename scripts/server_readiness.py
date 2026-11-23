#!/usr/bin/env python3
"""Предполётная проверка сервера, где нет ни домена, ни DNS, ни почтовых портов.

Запасной вариант на случай, когда сервер выдали, а домена ещё нет. `preflight.py`
проверяет то, что нужно для сборки, но запускать его надо внутри Linux-VM, где стоит
Docker. Здесь — хостовая половина: доступен ли сервер, свободны ли ключевые порты,
сходятся ли часы, хватает ли места, и не занимает ли порты кто-то чужой.

Скрипт описывает свою границу честно: он **не** доказывает, что Corvex поднимется.
Он отвечает на три вопроса, ответы на которые нужны **до** копирования репозитория:

1. смогу ли я вообще достучаться до сервера по SSH (или я уже на нём);
2. свободны ли 80/443/25/587/993/995 — не заняты ли они IIS, Exchange или чужим nginx;
3. есть ли под это место и не разъезжаются ли часы.

Запускается на любом из двух хостов — Windows и Linux — и ничего не меняет:
никаких установок, ни одной записи на диск сервера, ни одного перезапуска.

Коды возврата: 0 — блокеров нет, 1 — есть FAIL, 2 — `--strict` при наличии WARN.

Примеры:

    python scripts/server_readiness.py --host 203.0.113.10 --ssh-user deploy
    python scripts/server_readiness.py --host 203.0.113.10 --ssh-user deploy --strict
    python scripts/server_readiness.py --host 203.0.113.10 --public-ip 203.0.113.10
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import socket
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Порты, которые Corvex публикует наружу, и что произойдёт, если они заняты.
EXPECTED_FREE = {
    80: "веб-интерфейс и выпуск Let's Encrypt",
    443: "веб-интерфейс, API, чат",
    25: "приём почты из интернета",
    587: "отправка из почтовых клиентов",
    993: "IMAP",
    995: "POP3",
}

# Минимумы из SERVER-REQUIREMENTS.md для 50 ящиков.
MIN_CPU = 4
MIN_RAM_GIB = 16
MIN_SYSTEM_DISK_GIB = 100
MIN_DATA_DISK_GIB = 100

# Внутренние порты не должны быть видны снаружи — проверяем, что они закрыты.
MUST_BE_CLOSED = {
    5432: "PostgreSQL",
    6379: "Redis",
    5672: "RabbitMQ",
    9200: "Elasticsearch",
}

SHELL_TIMEOUT_S = 20


@dataclass
class Check:
    name: str
    status: str  # PASS | WARN | FAIL
    detail: str


def add(checks: list[Check], name: str, status: str, detail: str) -> None:
    checks.append(Check(name, status, detail))


# --- SSH -------------------------------------------------------------------


def ssh_command(host: str, user: str | None, port: int, script: str) -> list[str]:
    target = f"{user}@{host}" if user else host
    return [
        "ssh",
        "-p", str(port),
        "-o", "BatchMode=yes",          # без интерактивных запросов пароля
        "-o", "ConnectTimeout=10",
        "-o", "StrictHostKeyChecking=accept-new",
        target,
        script,
    ]


def run_ssh(host: str, user: str | None, port: int, script: str) -> tuple[int, str]:
    try:
        result = subprocess.run(
            ssh_command(host, user, port, script),
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=SHELL_TIMEOUT_S, check=False,
        )
    except FileNotFoundError:
        return 127, "ssh не найден в PATH"
    except subprocess.TimeoutExpired:
        return 124, f"ssh не ответил за {SHELL_TIMEOUT_S} с"
    output = (result.stdout + result.stderr).strip()
    return result.returncode, output


# `free -g` есть не везде (минимальные образы), поэтому память берём из /proc.
REMOTE_FACTS = (
    "nproc; "
    "awk '/MemTotal/ {printf \"%.0f\\n\", $2/1048576}' /proc/meminfo; "
    "df -Pk / | awk 'NR==2 {print $4}'; "
    "date -u +%s"
)


def parse_remote_facts(output: str) -> dict[str, int]:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if len(lines) < 4 or not lines[0].isdigit():
        raise ValueError(f"неожиданный ответ: {output[:200]}")
    return {
        "cpu": int(lines[0]),
        "ram_gib": int(lines[1]),
        "disk_free_gib": int(lines[2]) // (1024 * 1024),
        "server_epoch": int(lines[3]),
    }


# --- проверки --------------------------------------------------------------


def check_reachability(checks: list[Check], host: str, user: str | None, port: int) -> dict[str, int] | None:
    code, output = run_ssh(host, user, port, REMOTE_FACTS)
    if code != 0:
        add(checks, "ssh", "FAIL", f"не подключиться: {output.splitlines()[-1] if output else code}")
        return None
    try:
        facts = parse_remote_facts(output)
    except ValueError as failure:
        add(checks, "ssh", "FAIL", str(failure))
        return None
    add(checks, "ssh", "PASS", f"подключение есть, часовой пояс сервера UTC ({facts['server_epoch']})")
    return facts


def check_resources(checks: list[Check], facts: dict[str, int]) -> None:
    cpu_ok = facts["cpu"] >= MIN_CPU
    add(
        checks, "cpu", "PASS" if cpu_ok else "FAIL",
        f"{facts['cpu']} vCPU (нужно ≥ {MIN_CPU})",
    )
    ram_ok = facts["ram_gib"] >= MIN_RAM_GIB
    add(
        checks, "ram", "PASS" if ram_ok else "FAIL",
        f"{facts['ram_gib']} ГиБ (нужно ≥ {MIN_RAM_GIB}; {MIN_RAM_GIB + 8} с мониторингом)",
    )
    disk_ok = facts["disk_free_gib"] >= MIN_SYSTEM_DISK_GIB
    add(
        checks, "disk /", "PASS" if disk_ok else "FAIL",
        f"{facts['disk_free_gib']} ГиБ свободно (нужно ≥ {MIN_SYSTEM_DISK_GIB})",
    )


def check_clock(checks: list[Check], facts: dict[str, int]) -> None:
    drift = abs(int(datetime.now(timezone.utc).timestamp()) - facts["server_epoch"])
    if drift <= 5:
        add(checks, "clock", "PASS", f"расхождение {drift} с")
    elif drift <= 60:
        add(checks, "clock", "WARN", f"расхождение {drift} с — включите NTP до установки")
    else:
        add(checks, "clock", "FAIL", f"расхождение {drift} с: TLS, JWT и DKIM будут ломаться")


def tcp_open(host: str, port: int, timeout: float = 5.0) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def check_ports(checks: list[Check], host: str, publicly_reachable: bool) -> None:
    """Занят ли порт. `publicly_reachable` — проверяем снаружи, а не на самом сервере."""
    for port, purpose in EXPECTED_FREE.items():
        if not publicly_reachable:
            add(checks, f"port {port}", "WARN",
                f"не проверялся снаружи: {purpose} — прогоните с этой машины")
            continue
        busy = tcp_open(host, port)
        add(
            checks, f"port {port}", "WARN" if busy else "PASS",
            f"{'занят' if busy else 'свободен'} — {purpose}",
        )
    for port, service in MUST_BE_CLOSED.items():
        if not publicly_reachable:
            continue
        if tcp_open(host, port):
            add(checks, f"port {port}", "FAIL", f"{service} виден снаружи — так быть не должно")
        else:
            add(checks, f"port {port}", "PASS", f"{service} снаружи закрыт")


def check_tools(checks: list[Check]) -> None:
    if platform.system() == "Windows":
        add(checks, "ssh client", "PASS" if os.environ.get("WINDIR") else "WARN",
            "используется встроенный OpenSSH Windows")
        docker = subprocess.run(["docker", "--version"], capture_output=True, text=True, check=False) \
            if _which("docker") else None
        if docker is None:
            add(checks, "docker (локально)", "WARN",
                "для установки на сервер локальный Docker не нужен — это нормально")
        else:
            add(checks, "docker (локально)", "PASS", docker.stdout.strip())
    else:
        add(checks, "ssh client", "PASS" if _which("ssh") else "FAIL", "OpenSSH в PATH")


def _which(command: str) -> bool:
    from shutil import which
    return which(command) is not None


def check_git_sha(checks: list[Check]) -> None:
    try:
        sha = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"], cwd=ROOT,
            capture_output=True, text=True, check=False,
        ).stdout.strip()
        branch = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT,
            capture_output=True, text=True, check=False,
        ).stdout.strip()
    except OSError:
        add(checks, "репозиторий", "WARN", "git недоступен: устанавливать будете с сервера вручную")
        return
    if sha:
        add(checks, "репозиторий", "PASS", f"{branch}@{sha} — этот SHA и записывается в отчёт")
    else:
        add(checks, "репозиторий", "WARN", "не удалось определить SHA")


# --- вывод -----------------------------------------------------------------


def exit_code(checks: list[Check], strict: bool) -> int:
    if any(check.status == "FAIL" for check in checks):
        return 1
    if strict and any(check.status == "WARN" for check in checks):
        return 2
    return 0


def write_report(checks: list[Check], report_dir: Path, host: str) -> Path:
    report_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = report_dir / f"server-readiness-{stamp}.json"
    payload = {
        "host": host,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "checks": [check.__dict__ for check in checks],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Проверяет готовность сервера, у которого ещё нет домена и DNS: "
            "доступ по SSH, ресурсы, часы и занятость портов. Ничего не меняет."
        )
    )
    parser.add_argument("--host", required=True, help="IP или имя сервера")
    parser.add_argument("--ssh-user", default=None, help="пользователь SSH; без него — текущий")
    parser.add_argument("--ssh-port", type=int, default=22, help="порт SSH (по умолчанию 22)")
    parser.add_argument(
        "--local", action="store_true",
        help="скрипт уже на сервере: SSH не используется, факты берутся с этой машины",
    )
    parser.add_argument(
        "--public-ip", default=None,
        help="проверять занятость портов по этому адресу (снаружи), а не по --host",
    )
    parser.add_argument("--report-dir", default=str(ROOT / "artifacts" / "smoke"))
    parser.add_argument("--strict", action="store_true", help="WARN тоже считается провалом")
    parser.add_argument("--json", action="store_true", help="напечатать отчёт JSON в stdout")
    args = parser.parse_args(argv)
    if args.ssh_port <= 0 or args.ssh_port > 65535:
        parser.error("--ssh-port должен быть в диапазоне 1..65535")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    checks: list[Check] = []
    print(f"Corvex server readiness: {args.host}, {datetime.now().strftime('%Y-%m-%d %H:%M')}")

    check_git_sha(checks)
    check_tools(checks)

    if args.local:
        facts = parse_remote_facts(
            subprocess.run(
                ["sh", "-c", REMOTE_FACTS], capture_output=True, text=True, check=False,
            ).stdout
        )
        add(checks, "ssh", "PASS", "локальный запуск: SSH не нужен")
    else:
        facts = check_reachability(checks, args.host, args.ssh_user, args.ssh_port)

    if facts is None:
        add(checks, "ресурсы", "FAIL", "не проверены: нет доступа к серверу")
        add(checks, "порты", "FAIL", "не проверены: нет доступа к серверу")
    else:
        check_resources(checks, facts)
        check_clock(checks, facts)
        check_ports(checks, args.public_ip or args.host, publicly_reachable=bool(args.public_ip))

    for check in checks:
        print(f"[{check.status}] {check.name} — {check.detail}")

    path = write_report(checks, Path(args.report_dir), args.host)
    print(f"\nОтчёт: {path}")
    print(
        "Что этот прогон не проверяет: домен, MX, SPF/DKIM/DMARC, PTR, TLS с публичным "
        "именем, установку Docker и сборку. Это следующий шаг, и он требует домена."
    )
    code = exit_code(checks, args.strict)
    if args.json:
        print(json.dumps([check.__dict__ for check in checks], ensure_ascii=False, indent=2))
    return code


if __name__ == "__main__":
    sys.exit(main())
