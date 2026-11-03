"""Общие примитивы для non-destructive Corvex smoke-скриптов."""

from __future__ import annotations

import json
import ssl
import subprocess
import time
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class SmokeError(RuntimeError):
    """Проверка завершилась ожидаемым для отчёта образом."""


class SkipCheck(RuntimeError):
    """Проверка неполна: например, в ящике нет письма для FETCH/RETR."""


def git_sha(root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=root,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=10,
            check=False,
        )
        return result.stdout.strip() if result.returncode == 0 else "unknown"
    except (OSError, subprocess.TimeoutExpired):
        return "unknown"


def make_ssl_context(ca_file: str | None = None) -> ssl.SSLContext:
    """TLS 1.2+ с обязательной проверкой цепочки и hostname."""
    context = ssl.create_default_context(cafile=ca_file)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.check_hostname = True
    return context


def tls_details(sock: Any) -> dict[str, str]:
    version = sock.version()
    if version not in {"TLSv1.2", "TLSv1.3"}:
        raise SmokeError(f"Недопустимая версия TLS после handshake: {version!r}")
    cipher = sock.cipher()
    return {"tls": version, "cipher": cipher[0] if cipher else "unknown"}


class SmokeReport:
    def __init__(self, suite: str, root: Path) -> None:
        self.suite = suite
        self.root = root
        self.results: list[dict[str, Any]] = []

    def add(
        self,
        name: str,
        status: str,
        *,
        duration: float = 0.0,
        details: Any = None,
        error: str | None = None,
    ) -> None:
        item: dict[str, Any] = {
            "name": name,
            "status": status,
            "duration_seconds": round(duration, 3),
        }
        if details is not None:
            item["details"] = details
        if error:
            item["error"] = error[:2000]
        self.results.append(item)
        suffix = ""
        if details:
            suffix = (
                f" — {details}"
                if isinstance(details, str)
                else f" — {json.dumps(details, ensure_ascii=False)}"
            )
        if error:
            suffix = f" — {error[:500]}"
        print(f"[{status}] {name}{suffix}", flush=True)

    def run(self, name: str, check: Callable[[], Any]) -> bool:
        started = time.monotonic()
        try:
            details = check()
        except SkipCheck as exc:
            self.add(
                name,
                "SKIP",
                duration=time.monotonic() - started,
                error=str(exc),
            )
            return False
        except Exception as exc:  # noqa: BLE001 — отчёт сохраняет ошибку и продолжает независимые проверки.
            self.add(
                name,
                "FAIL",
                duration=time.monotonic() - started,
                error=str(exc),
            )
            return False
        self.add(name, "PASS", duration=time.monotonic() - started, details=details)
        return True

    @property
    def failures(self) -> int:
        return sum(item["status"] == "FAIL" for item in self.results)

    @property
    def skips(self) -> int:
        return sum(item["status"] == "SKIP" for item in self.results)

    @property
    def exit_code(self) -> int:
        if self.failures:
            return 1
        if self.skips:
            return 3
        return 0

    def write(self, path: Path, metadata: dict[str, Any] | None = None) -> None:
        payload = {
            "suite": self.suite,
            "created_at": datetime.now(timezone.utc).isoformat(),  # noqa: UP017 — Python 3.10 compatibility.
            "git_sha": git_sha(self.root),
            "summary": {
                "pass": sum(item["status"] == "PASS" for item in self.results),
                "fail": self.failures,
                "skip": self.skips,
            },
            "metadata": metadata or {},
            "results": self.results,
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"JSON report: {path}", flush=True)

    def finish(self) -> int:
        passed = sum(item["status"] == "PASS" for item in self.results)
        print(
            f"SUMMARY {self.suite}: PASS={passed} FAIL={self.failures} SKIP={self.skips}; "
            f"exit={self.exit_code}",
            flush=True,
        )
        return self.exit_code
