#!/usr/bin/env python3
"""Non-destructive runtime-smoke: Compose containers и HTTPS без up/down/restart."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from smoke_common import SmokeError, SmokeReport, make_ssl_context

ROOT = Path(__file__).resolve().parent.parent


class ServiceStarting(RuntimeError):
    pass


def run_command(arguments: list[str], timeout: int) -> str:
    try:
        result = subprocess.run(
            arguments,
            cwd=ROOT,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as exc:
        raise SmokeError(f"Команда не найдена: {arguments[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise SmokeError(f"Таймаут команды {arguments[0]} ({timeout} с)") from exc
    if result.returncode != 0:
        # stdout не попадает в ошибку: `compose config --format json` содержит environment.
        error = result.stderr.strip() or f"exit code {result.returncode}"
        raise SmokeError(f"{' '.join(arguments[:3])}: {error[-2000:]}")
    return result.stdout


def select_services(config: dict[str, Any], monitoring: bool) -> list[str]:
    selected = []
    for name, service in config.get("services", {}).items():
        profiles = set(service.get("profiles") or [])
        if not profiles or (monitoring and "monitoring" in profiles):
            selected.append(name)
    if not selected:
        raise SmokeError("Compose config не содержит сервисов для проверки")
    return selected


def load_services(monitoring: bool, timeout: int) -> list[str]:
    command = ["docker", "compose"]
    if monitoring:
        command += ["--profile", "monitoring"]
    command += ["config", "--format", "json"]
    try:
        config = json.loads(run_command(command, timeout))
    except json.JSONDecodeError as exc:
        raise SmokeError("Docker Compose вернул невалидный JSON config") from exc
    return select_services(config, monitoring)


def evaluate_state(state: dict[str, Any]) -> str:
    if state.get("Restarting"):
        raise SmokeError("контейнер перезапускается")
    if not state.get("Running"):
        raise SmokeError(
            f"контейнер не запущен: Status={state.get('Status', 'unknown')}"
        )
    health = state.get("Health")
    if health:
        status = health.get("Status", "unknown")
        if status == "starting":
            raise ServiceStarting("healthcheck ещё выполняется")
        if status != "healthy":
            raise SmokeError(f"healthcheck={status}")
        return "healthy"
    return "running (без healthcheck)"


def container_ids(service: str, timeout: int) -> list[str]:
    output = run_command(
        ["docker", "compose", "ps", "--all", "--quiet", service], timeout
    )
    identifiers = [line.strip() for line in output.splitlines() if line.strip()]
    if not identifiers:
        raise SmokeError("контейнер не создан или не найден в текущем Compose project")
    return identifiers


def check_service(service: str, wait_seconds: int, timeout: int) -> dict[str, Any]:
    deadline = time.monotonic() + wait_seconds
    while True:
        identifiers = container_ids(service, timeout)
        states = []
        starting = False
        for identifier in identifiers:
            raw = run_command(
                ["docker", "inspect", "--format", "{{json .State}}", identifier],
                timeout,
            )
            try:
                state = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SmokeError("docker inspect вернул невалидный JSON") from exc
            try:
                status = evaluate_state(state)
            except ServiceStarting:
                if time.monotonic() >= deadline:
                    raise SmokeError(
                        f"healthcheck=starting после ожидания {wait_seconds} с"
                    )
                starting = True
                break
            states.append({"container": identifier[:12], "status": status})
        if not starting:
            return {"instances": states}
        time.sleep(min(5, max(1, int(deadline - time.monotonic()))))


def validate_web_url(url: str) -> None:
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise SmokeError("Web URL обязан использовать HTTPS")
    if parsed.username or parsed.password:
        raise SmokeError("Не передавайте credentials в Web URL")
    if parsed.query or parsed.fragment:
        raise SmokeError("Не передавайте query/fragment в Web URL и JSON-отчёт")


def check_web(url: str, ca_file: str | None, timeout: int) -> dict[str, Any]:
    validate_web_url(url)
    request = urllib.request.Request(
        url, headers={"User-Agent": "Corvex-Runtime-Smoke/1"}
    )
    context = make_ssl_context(ca_file)
    try:
        with urllib.request.urlopen(
            request, context=context, timeout=timeout
        ) as response:
            validate_web_url(response.geturl())
            body = response.read(262144)
            if response.status != 200:
                raise SmokeError(f"HTTPS status={response.status}")
            if b'id="root"' not in body and b"id='root'" not in body:
                raise SmokeError(
                    "Ответ HTTPS не похож на Corvex SPA: отсутствует #root"
                )
            return {"status": response.status, "bytes_read": len(body)}
    except Exception as exc:
        if isinstance(exc, SmokeError):
            raise
        raise SmokeError(f"HTTPS smoke: {exc}") from exc


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Проверка уже запущенного Corvex без up/down/restart и без изменения данных."
    )
    parser.add_argument(
        "--monitoring", action="store_true", help="Также проверить monitoring-профиль"
    )
    parser.add_argument(
        "--url", default="https://mail.company.local/", help="HTTPS URL frontend"
    )
    parser.add_argument(
        "--ca-file", help="PEM тестового CA; системное доверие используется без флага"
    )
    parser.add_argument(
        "--skip-web",
        action="store_true",
        help="Проверить только контейнеры (явный пропуск web-gate)",
    )
    parser.add_argument(
        "--wait-seconds",
        type=int,
        default=120,
        help="Ожидание health=starting на сервис",
    )
    parser.add_argument(
        "--timeout", type=int, default=30, help="Таймаут Docker/HTTPS операции, секунды"
    )
    parser.add_argument(
        "--report",
        type=Path,
        help="Путь JSON-отчёта, например artifacts/smoke/runtime.json",
    )
    args = parser.parse_args()
    if args.wait_seconds < 0 or args.timeout <= 0:
        parser.error("--wait-seconds должен быть >= 0, --timeout > 0")
    return args


def main() -> int:
    args = parse_args()
    report = SmokeReport("runtime", ROOT)
    try:
        services = load_services(args.monitoring, args.timeout)
    except SmokeError as exc:
        report.add("compose-config", "FAIL", error=str(exc))
        services = []
    else:
        report.add(
            "compose-config",
            "PASS",
            details={"services": len(services), "monitoring": args.monitoring},
        )
        for service in services:
            report.run(
                f"container:{service}",
                lambda service=service: check_service(
                    service, args.wait_seconds, args.timeout
                ),
            )
    if args.skip_web:
        report.add("https:frontend", "SKIP", error="явно задан --skip-web")
    else:
        report.run(
            "https:frontend", lambda: check_web(args.url, args.ca_file, args.timeout)
        )
    if args.report:
        report.write(
            args.report,
            {
                "monitoring": args.monitoring,
                "web_url": args.url if not args.skip_web else None,
            },
        )
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())
