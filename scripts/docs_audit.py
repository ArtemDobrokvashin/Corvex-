#!/usr/bin/env python3
"""Аудит «документация против кода» перед пилотом.

Зачем скрипт, а не вычитка глазами. В `docs/` около четырёх тысяч строк, и они
расходятся с кодом в двух направлениях, причём оба опасны по-своему:

* документ обещает маршрут, которого нет — человек интегрируется по `api/*.yaml`
  и получает 404/405 уже на живом стенде;
* маршрут есть, но его нигде нет — функция существует и работает, а клиент о ней
  не знает, то есть работа сделана в стол.

Ручная сверка ловит это один раз. Скрипт ловит каждый раз, поэтому он же
становится тестом (`scripts/tests/test_docs_audit.py`), а тест гоняется в CI.

Что проверяется:

1. **OpenAPI → код.** Каждая операция из `docs/api/*.yaml` должна существовать
   в `@*Mapping` контроллеров. Пометки `x-corvex-status: planned|internal`
   означают «в коде этого нет намеренно» и не проверяются.
2. **Код → документация.** Каждый маршрут `/api/v1/**` в контроллерах должен
   упоминаться либо в OpenAPI, либо в `docs/API.md` (там таблицы для того, что
   в OpenAPI не вынесено: календарь, диск, STOMP). Префиксы вида `/mail/**`
   доказательством не считаются — они описывают маршрут gateway, а не эндпоинт.
3. **Ссылки на файлы.** `scripts/*.ps1|py|java` и `docs/*.md`, упомянутые в
   документации, должны существовать.
4. **Миграции.** Заявления вида `V1…V13` сверяются с фактическим набором
   файлов Flyway; устаревшее число — предупреждение, а не ошибка, потому что
   в историческом контексте оно может быть верным.
5. **Переменные окружения.** Ключи из `.env.example` должны кем-то читаться
   (compose, Java, `application.yml`): «мёртвая» настройка хуже отсутствующей —
   оператор меняет значение и не понимает, почему ничего не меняется. Обратное
   направление проверяется только для обязательных: `${VAR}` без значения по
   умолчанию в compose обязан быть в примере, иначе стенд не поднимется.
6. **`planned`, который уже реализован.** Устаревшая пометка скрывает рабочую
   операцию от клиентов так же надёжно, как отсутствующее описание.
7. **Число прав.** «Всего N прав» в документации сверяется с enum
   `Permission.java`: счётчик уже расходился (25 против 27).

Ограничение, о котором нужно помнить: разбор Java и YAML здесь регулярный, а
не настоящий парсер. Он рассчитан на стиль этого репозитория (одна строка на
аннотацию, литеральные пути). Если в контроллере появится путь из константы
или `@RequestMapping` на нескольких строках, скрипт его не увидит — и это
честнее, чем тихо пропустить: такое место надо добавлять в разбор явно.

Запуск:
    python3 scripts/docs_audit.py
    python3 scripts/docs_audit.py --json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SPRING_METHODS = ("Get", "Post", "Put", "Patch", "Delete")


# --------------------------------------------------------------------------- #
# Разбор Java-контроллеров
# --------------------------------------------------------------------------- #

def _balanced_args(source: str, start: int) -> str:
    """Аргументы аннотации от `source[start] == '('` до парной скобки."""
    depth = 0
    index = start
    while index < len(source):
        char = source[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[start + 1:index]
        elif char == '"':
            index += 1
            while index < len(source) and source[index] != '"':
                if source[index] == "\\":
                    index += 1
                index += 1
        index += 1
    return ""


def _first_string(args: str) -> str:
    match = re.search(r'"([^"]*)"', args)
    return match.group(1) if match else ""


def normalize_path(path: str) -> str:
    """`{messageId}` и `{id}` — один и тот же маршрут, имя переменной не важно."""
    collapsed = re.sub(r"\{[^}]*\}", "{}", path)
    collapsed = re.sub(r"/{2,}", "/", collapsed)
    if len(collapsed) > 1 and collapsed.endswith("/"):
        collapsed = collapsed[:-1]
    return collapsed or "/"


def spring_routes(root: Path) -> dict[tuple[str, str], str]:
    """(метод, шаблон пути) → файл контроллера."""
    routes: dict[tuple[str, str], str] = {}
    for path in sorted((root / "services").rglob("*Controller*.java")):
        if "/src/main/" not in path.as_posix():
            continue
        source = path.read_text(encoding="utf-8", errors="replace")
        if "@RestController" not in source:
            continue
        base = ""
        class_mapping = re.search(r"@RequestMapping\s*\(", source)
        if class_mapping:
            base = _first_string(_balanced_args(source, class_mapping.end() - 1))
        for annotation in re.finditer(
            r"@(" + "|".join(SPRING_METHODS) + r")Mapping\b", source
        ):
            cursor = annotation.end()
            while cursor < len(source) and source[cursor] in " \t":
                cursor += 1
            suffix = ""
            if cursor < len(source) and source[cursor] == "(":
                suffix = _first_string(_balanced_args(source, cursor))
            key = (annotation.group(1).upper(), normalize_path(base + suffix))
            routes.setdefault(key, path.name)
    return routes


# --------------------------------------------------------------------------- #
# Разбор OpenAPI (без PyYAML: в CI нет pip install)
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class Operation:
    method: str
    path: str        # уже с префиксом servers.url
    status: str      # "" | "planned" | "internal"
    source: str


def openapi_operations(text: str, source: str) -> list[Operation]:
    server_prefix = ""
    # Только блок servers: выше в файле бывает `license.url`, и он тоже «url».
    block = re.search(r"^servers:\s*$([\s\S]*?)(?=^\S)", text, re.M)
    if block:
        server = re.search(r"url:\s*https?://[^/\s]+(/[^\s]*)?", block.group(1))
        if server and server.group(1):
            server_prefix = server.group(1).rstrip("/")

    operations: list[Operation] = []
    current: str | None = None
    path_status = ""
    path_prefix = ""
    path_has_servers = False
    in_path_servers = False
    for line in text.splitlines():
        target = re.match(r"^  (/\S*):\s*$", line)
        if target:
            current = target.group(1)
            path_status = ""
            path_prefix = ""
            path_has_servers = False
            in_path_servers = False
            continue
        if current is not None and re.match(r"^    servers:\s*$", line):
            # Path Item может переопределять servers (внутренний JWKS): адрес
            # в этом случае берётся из переопределения, а не из корневого блока.
            in_path_servers = True
            continue
        if in_path_servers:
            override = re.match(r"^      - url:\s*https?://[^/\s]+(/[^\s]*)?", line)
            if override:
                path_prefix = (override.group(1) or "").rstrip("/")
                path_has_servers = True
                in_path_servers = False
                continue
            if not line.startswith("      "):
                in_path_servers = False
        if current is None:
            continue
        marker = re.match(r"^    x-corvex-status:\s*(planned|internal)\s*$", line)
        if marker:
            path_status = marker.group(1)
            continue
        operation = re.match(
            r"^    (get|post|put|patch|delete|head|options):", line
        )
        if operation:
            operations.append(
                Operation(
                    method=operation.group(1).upper(),
                    path=normalize_path(
                        (path_prefix if path_has_servers else server_prefix) + current
                    ),
                    status=path_status,
                    source=source,
                )
            )
            continue
        # `x-corvex-status` может стоять и внутри операции (6 пробелов)
        inner = re.match(r"^      x-corvex-status:\s*(planned|internal)\s*$", line)
        if inner and operations and not path_status:
            last = operations[-1]
            operations[-1] = Operation(last.method, last.path, inner.group(1), last.source)
    return operations


def documented_in_markdown(text: str) -> set[tuple[str, str]]:
    """Пары (метод|ANY, путь) из обратных кавычек `docs/API.md`."""
    found: set[tuple[str, str]] = set()
    for token in re.findall(r"`([^`]+)`", text):
        # В таблицах путь и тело идут одной строкой: `PATCH /drive/{id}/name {name}`.
        # Тело отбрасываем, иначе регулярка не совпадёт и живой эндпоинт
        # будет выглядеть недокументированным.
        token = token.split("?")[0].strip()
        method_match = re.match(
            r"^((?:GET|POST|PUT|PATCH|DELETE)(?:/(?:GET|POST|PUT|PATCH|DELETE))*)\s+",
            token,
        )
        methods = method_match.group(1).split("/") if method_match else ["ANY"]
        if method_match:
            token = token[method_match.end():]
        path = token.split(" ")[0]
        if not re.match(r"^/[A-Za-z0-9/_{}.*-]*$", path):
            continue
        path = normalize_path(path)
        # Таблица gateway-маршрутов (`/mail/**`) описывает префиксы, а не эндпоинты:
        # считать её доказательством документированности нельзя, иначе проверка
        # «код → документация» проходит всегда и ничего не значит.
        if path == "/" or path.endswith("/**"):
            continue
        if not path.startswith("/api/"):
            path = "/api/v1" + path
        for method in methods:
            found.add((method, path))
    return found


# --------------------------------------------------------------------------- #
# Проверки
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class Finding:
    level: str      # error | warning
    check: str
    message: str


def _doc_files(root: Path) -> list[Path]:
    files = sorted((root / "docs").glob("*.md"))
    readme = root / "README.md"
    if readme.exists():
        files.append(readme)
    return files


def check_openapi_against_code(root: Path) -> list[Finding]:
    routes = spring_routes(root)
    findings: list[Finding] = []
    for spec in sorted((root / "docs" / "api").glob("*.yaml")):
        text = spec.read_text(encoding="utf-8")
        for operation in openapi_operations(text, spec.name):
            if operation.status in ("planned", "internal"):
                continue
            if (operation.method, operation.path) not in routes:
                findings.append(
                    Finding(
                        "error",
                        "openapi",
                        f"{spec.name}: {operation.method} {operation.path} описан, "
                        f"но в контроллерах такого маршрута нет",
                    )
                )
    return findings


def _covered_by_docs(method: str, path: str,
                     known: set[tuple[str, str]]) -> bool:
    for doc_method, doc_path in known:
        if doc_method not in ("ANY", method):
            continue
        if doc_path.endswith("/**"):
            prefix = doc_path[:-3]
            if path == prefix or path.startswith(prefix + "/"):
                return True
        elif doc_path == path:
            return True
    return False


def check_code_against_docs(root: Path) -> list[Finding]:
    routes = spring_routes(root)
    known: set[tuple[str, str]] = set()
    for spec in sorted((root / "docs" / "api").glob("*.yaml")):
        for operation in openapi_operations(
            spec.read_text(encoding="utf-8"), spec.name
        ):
            known.add((operation.method, operation.path))
    api_md = root / "docs" / "API.md"
    if api_md.exists():
        known |= documented_in_markdown(api_md.read_text(encoding="utf-8"))

    findings: list[Finding] = []
    for (method, path), controller in sorted(routes.items()):
        if not path.startswith("/api/v1"):
            continue
        if not _covered_by_docs(method, path, known):
            findings.append(
                Finding(
                    "warning",
                    "docs",
                    f"{method} {path} ({controller}) работает, но не упомянут "
                    f"ни в docs/api/*.yaml, ни в docs/API.md",
                )
            )
    return findings


FILE_REFERENCE = re.compile(
    r"(?<![\w/])(scripts/[A-Za-z0-9_.-]+\.(?:ps1|py|java)|docs/[A-Za-z0-9_.-]+\.md)"
)


def check_file_references(root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for doc in _doc_files(root):
        text = doc.read_text(encoding="utf-8")
        for reference in sorted(set(FILE_REFERENCE.findall(text))):
            if not (root / reference).exists():
                findings.append(
                    Finding(
                        "error",
                        "references",
                        f"{doc.name}: ссылка на {reference}, а такого файла нет",
                    )
                )
    return findings


def check_migrations(root: Path) -> list[Finding]:
    numbers = []
    for path in root.rglob("*.sql"):
        if "/db/migration/" not in path.as_posix():
            continue
        match = re.match(r"V(\d+)__", path.name)
        if match:
            numbers.append(int(match.group(1)))
    if not numbers:
        return [Finding("error", "migrations", "файлы миграций Flyway не найдены")]
    latest = max(numbers)
    findings: list[Finding] = []
    for doc in _doc_files(root):
        text = doc.read_text(encoding="utf-8")
        for claim in re.finditer(r"V1\s*[….]{1,3}\s*V(\d+)", text):
            claimed = int(claim.group(1))
            if claimed > latest:
                findings.append(
                    Finding(
                        "error",
                        "migrations",
                        f"{doc.name}: заявлено {claim.group(0)}, а самая новая "
                        f"миграция — V{latest}",
                    )
                )
            elif claimed < latest:
                findings.append(
                    Finding(
                        "warning",
                        "migrations",
                        f"{doc.name}: заявлено {claim.group(0)}, сегодня V1…V{latest}",
                    )
                )
    return findings


def check_env_example(root: Path) -> list[Finding]:
    env_file = root / ".env.example"
    if not env_file.exists():
        return [Finding("error", "env", ".env.example отсутствует")]

    documented = {
        match.group(1)
        for match in re.finditer(
            r"^#?\s*([A-Z][A-Z0-9_]*)=", env_file.read_text(encoding="utf-8"), re.M
        )
    }
    sources = [root / "docker-compose.yml"]
    sources += list((root / "services").rglob("*.java"))
    sources += list((root / "services").rglob("*.yml"))
    sources += list((root / "services").rglob("*.yaml"))
    blob = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in sources
        if path.exists()
    )

    findings: list[Finding] = []
    for name in sorted(documented):
        if not re.search(r"\b" + re.escape(name) + r"\b", blob):
            findings.append(
                Finding(
                    "error",
                    "env",
                    f"{name} есть в .env.example, но нигде не читается: "
                    f"настройка мёртвая",
                )
            )
    return findings


def check_compose_requires_documented(root: Path) -> list[Finding]:
    """`${VAR}` без значения по умолчанию обязан быть в .env.example.

    Переменная с `:-default` переживёт отсутствие в `.env`, а без него compose
    подставит пустую строку — и сервис упадёт уже на старте, с сообщением, которое
    не указывает на забытую строку в примере.
    """
    compose = root / "docker-compose.yml"
    if not compose.exists():
        return []
    text = compose.read_text(encoding="utf-8")
    required = {
        match.group(1)
        for match in re.finditer(r"\$\{(CORVEX_[A-Z0-9_]+)\}", text)
    }
    env_file = root / ".env.example"
    documented = set()
    if env_file.exists():
        documented = {
            match.group(1)
            for match in re.finditer(
                r"^#?\s*([A-Z][A-Z0-9_]*)=",
                env_file.read_text(encoding="utf-8"),
                re.M,
            )
        }
    return [
        Finding(
            "error",
            "env",
            f"docker-compose.yml требует {name} без значения по умолчанию, "
            f"но в .env.example её нет",
        )
        for name in sorted(required - documented)
    ]


def check_planned_but_implemented(root: Path) -> list[Finding]:
    """`planned` на работающей операции — тоже ложь, только в другую сторону."""
    routes = spring_routes(root)
    findings: list[Finding] = []
    for spec in sorted((root / "docs" / "api").glob("*.yaml")):
        for operation in openapi_operations(
            spec.read_text(encoding="utf-8"), spec.name
        ):
            if operation.status != "planned":
                continue
            if (operation.method, operation.path) in routes:
                findings.append(
                    Finding(
                        "warning",
                        "openapi",
                        f"{spec.name}: {operation.method} {operation.path} помечен "
                        f"planned, но маршрут уже есть в "
                        f"{routes[(operation.method, operation.path)]} — снимите пометку "
                        f"и допишите описание",
                    )
                )
    return findings


def check_permission_count(root: Path) -> list[Finding]:
    """Число прав в документации должно совпадать с `Permission.java`.

    Счётчик в двух документах уже расходился с кодом (25 против 27), а заметить
    это глазами можно только пересчитав таблицу вручную.
    """
    enum_file = next(
        root.glob("services/admin-service/src/main/java/**/rbac/Permission.java"),
        None,
    )
    if enum_file is None:
        return []
    names = {
        match.group(1)
        for match in re.finditer(
            r"^\s{4}([A-Z][A-Z0-9_]+)\s*(?:,|//|$)",
            enum_file.read_text(encoding="utf-8"),
            re.M,
        )
    }
    if not names:
        return []
    findings: list[Finding] = []
    for doc in _doc_files(root):
        text = doc.read_text(encoding="utf-8")
        for claim in re.finditer(r"(\d+)\s+прав\b", text):
            claimed = int(claim.group(1))
            if claimed != len(names):
                findings.append(
                    Finding(
                        "error",
                        "docs",
                        f"{doc.name}: заявлено {claimed} прав, в Permission.java их "
                        f"{len(names)}",
                    )
                )
    return findings


CHECKS = (
    ("openapi", check_openapi_against_code),
    ("docs", check_code_against_docs),
    ("references", check_file_references),
    ("migrations", check_migrations),
    ("env", check_env_example),
    ("env", check_compose_requires_documented),
    ("openapi", check_planned_but_implemented),
    ("docs", check_permission_count),
)


def run(root: Path = ROOT) -> list[Finding]:
    findings: list[Finding] = []
    for _, check in CHECKS:
        findings.extend(check(root))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--json", action="store_true", help="машинный вывод")
    parser.add_argument(
        "--errors-only", action="store_true", help="не печатать предупреждения"
    )
    args = parser.parse_args(argv)

    findings = run(args.root)
    shown = [f for f in findings if not args.errors_only or f.level == "error"]

    if args.json:
        print(json.dumps(
            [f.__dict__ for f in shown], ensure_ascii=False, indent=2
        ))
    else:
        for finding in shown:
            print(f"[{finding.level:7}] {finding.check:10} {finding.message}")
        errors = sum(1 for f in findings if f.level == "error")
        warnings = sum(1 for f in findings if f.level == "warning")
        print(f"\nИтого: {errors} ошибок, {warnings} предупреждений")

    return 1 if any(f.level == "error" for f in findings) else 0


if __name__ == "__main__":
    sys.exit(main())
