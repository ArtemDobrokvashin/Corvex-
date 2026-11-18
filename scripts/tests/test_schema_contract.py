"""Контракт «JPA-аннотация ↔ фактическая схема БД» для всех сервисов.

Зачем этот тест существует. 2026-10-19 живая проверка OAuth дала HTTP 500:

    null value in column "secret_enc" of relation "external_mail_accounts"
    violates not-null constraint   (SQLState 23502)

Код писал `null` осознанно (у OAuth-ящика нет пароля), а миграция `V4` объявляла
колонку `NOT NULL`. Ни один прогон этого не видел: тест сервиса работал с замоканным
репозиторием, H2 в тестах не применяет миграции, а JPA `ddl-auto` не сверяет схему.
Дефект жил в `main` с этапа 4 и нашёлся только человеком на стенде.

Частный страж `test_external_mail_schema.py` закрыл ту одну колонку. Этот тест
закрывает весь класс: он читает миграции в порядке версий, применяет `CREATE TABLE`,
`ALTER TABLE ... ADD COLUMN`, `ALTER COLUMN ... SET/DROP NOT NULL`, `RENAME`, `DROP COLUMN`
и сравнивает итоговую схему с аннотациями `@Column` и `@JoinColumn` во всех сущностях.

Опасное направление — «SQL запрещает NULL, а сущность допускает»: вставка упадёт в
рантайме. Обратное («SQL разрешает, сущность запрещает») не ломает запись, но означает
дыру в данных; оба направления считаются дефектом, различия — в тексте сообщения.
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


# --- разбор миграций ------------------------------------------------------------


def split_top_level(body: str) -> list[str]:
    """Делит тело CREATE TABLE по запятым верхнего уровня (скобки и кавычки учтены)."""
    parts, current, depth, quote = [], [], 0, ""
    for char in body:
        if quote:
            current.append(char)
            if char == quote:
                quote = ""
            continue
        if char in ("'", '"'):
            quote = char
            current.append(char)
            continue
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        if char == "," and depth == 0:
            parts.append("".join(current))
            current = []
            continue
        current.append(char)
    if current:
        parts.append("".join(current))
    return parts


def balanced_create_tables(sql: str) -> list[tuple[str, str]]:
    """Возвращает [(имя таблицы, тело в скобках)] для всех CREATE TABLE в файле."""
    found = []
    for match in re.finditer(r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?([A-Za-z_]\w*)\s*\(", sql, re.I):
        start = match.end()
        depth, index = 1, start
        while index < len(sql) and depth:
            if sql[index] == "(":
                depth += 1
            elif sql[index] == ")":
                depth -= 1
            index += 1
        found.append((match.group(1).lower(), sql[start:index - 1]))
    return found


def column_is_not_null(fragment: str) -> bool:
    upper = fragment.upper()
    return "NOT NULL" in upper or "PRIMARY KEY" in upper


SCHEMA = dict  # имя таблицы -> {колонка: not_null}


def strip_comments(sql: str) -> str:
    """Убирает `--` комментарии: они встречаются внутри CREATE TABLE и ломают разбор."""
    return "\n".join(re.sub(r"--.*$", "", line) for line in sql.splitlines())


def apply_migrations(migration_files: list[Path]) -> SCHEMA:
    schema: SCHEMA = {}
    for path in migration_files:
        sql = strip_comments(path.read_text(encoding="utf-8"))

        for table, body in balanced_create_tables(sql):
            columns: dict[str, bool] = {}
            for piece in split_top_level(body):
                piece = piece.strip()
                if not piece:
                    continue
                if re.match(r"(CONSTRAINT|PRIMARY\s+KEY|UNIQUE|FOREIGN\s+KEY|CHECK)\b", piece, re.I):
                    continue
                tokens = piece.split(None, 2)
                if len(tokens) < 2:
                    continue
                columns[tokens[0].lower()] = column_is_not_null(tokens[2] if len(tokens) > 2 else "")
            schema[table] = columns

        # ALTER TABLE может содержать несколько действий через запятую:
        #   ALTER TABLE contacts
        #       ADD COLUMN IF NOT EXISTS phone VARCHAR(64),
        #       ADD COLUMN IF NOT EXISTS note TEXT;
        for statement in sql.split(";"):
            match = re.match(r"\s*ALTER TABLE\s+(\w+)\s+(.*)$", statement, re.S | re.I)
            if not match:
                continue
            table = match.group(1).lower()
            for action in split_top_level(match.group(2)):
                action = action.strip()
                add = re.match(r"ADD COLUMN\s+(?:IF NOT EXISTS\s+)?(\w+)\s+(.*)$", action, re.S | re.I)
                if add:
                    schema.setdefault(table, {})[add.group(1).lower()] = column_is_not_null(add.group(2))
                    continue
                change = re.match(
                    r"ALTER COLUMN\s+(\w+)\s+(SET NOT NULL|DROP NOT NULL)", action, re.S | re.I
                )
                if change and table in schema and change.group(1).lower() in schema[table]:
                    schema[table][change.group(1).lower()] = change.group(2).upper() == "SET NOT NULL"
                    continue
                rename = re.match(r"RENAME COLUMN\s+(\w+)\s+TO\s+(\w+)", action, re.S | re.I)
                if rename and table in schema and rename.group(1).lower() in schema[table]:
                    schema[table][rename.group(2).lower()] = schema[table].pop(rename.group(1).lower())
                    continue
                drop = re.match(r"DROP COLUMN\s+(?:IF EXISTS\s+)?(\w+)", action, re.S | re.I)
                if drop:
                    schema.get(table, {}).pop(drop.group(1).lower(), None)
    return schema


# --- разбор сущностей -----------------------------------------------------------


ENTITY_TABLE = re.compile(r'@Table\(\s*name\s*=\s*"(\w+)"')
COLUMN_ANNOTATION = re.compile(
    r'@(Column|JoinColumn)\(\s*(?:[^)]*?\bname\s*=\s*"(\w+)")?[^)]*?\)\s*'
    r'(?:private|protected|public)?\s*[\w<>,\[\]\s\.]+?\s+(\w+)\s*;',
    re.S,
)


def entity_columns(source: str) -> list[tuple[str, str, bool]]:
    """[(вложенная сущность?, колонка, nullable по аннотации)] для одного файла."""
    result = []
    for match in COLUMN_ANNOTATION.finditer(source):
        name = match.group(2)
        if not name:
            continue  # @Column без name: имя колонки выводится из поля, проверить нельзя
        annotation = match.group(0)
        nullable_match = re.search(r"nullable\s*=\s*(true|false)", annotation)
        nullable = True if not nullable_match else nullable_match.group(1) != "false"
        result.append((match.group(1), name.lower(), nullable))
    return result


def entities(root: Path) -> dict[str, Path]:
    found = {}
    for path in root.glob("services/*/src/main/java/**/*.java"):
        text = path.read_text(encoding="utf-8")
        if "@Entity" not in text:
            continue
        match = ENTITY_TABLE.search(text)
        if match:
            found[match.group(1).lower()] = path
    return found


def migrations_by_service(root: Path) -> dict[str, list[Path]]:
    grouped: dict[str, list[Path]] = {}
    for directory in root.glob("services/*/src/main/resources/db/migration"):
        service = directory.parents[4].name
        files = []
        for path in directory.glob("V*__*.sql"):
            match = re.match(r"V(\d+)__", path.name)
            if match:
                files.append((int(match.group(1)), path))
        grouped[service] = [path for _, path in sorted(files)]
    return grouped


# --- сравнение ------------------------------------------------------------------

# Осознанные исключения. Пустой список — норма: каждое расхождение требует либо
# миграции, либо правки аннотации.
ALLOWED: set[tuple[str, str, str]] = set()


def mismatches(root: Path = ROOT) -> list[str]:
    found_entities = entities(root)
    problems: list[str] = []
    for service, files in migrations_by_service(root).items():
        if not files:
            continue
        schema = apply_migrations(files)
        for table, path in found_entities.items():
            if table not in schema:
                continue
            source = path.read_text(encoding="utf-8")
            for _, column, jpa_nullable in entity_columns(source):
                if column not in schema[table]:
                    problems.append(
                        f"{service}/{path.name}: @Column(\"{column}\") отсутствует в таблице {table}"
                    )
                    continue
                sql_nullable = not schema[table][column]
                if jpa_nullable == sql_nullable:
                    continue
                if (table, column, "jpa") in ALLOWED:
                    continue
                dangerous = jpa_nullable and not sql_nullable
                direction = (
                    "SQL запрещает NULL, а JPA допускает — вставка упадёт в рантайме"
                    if dangerous
                    else "JPA запрещает NULL, а SQL допускает — дыра в данных"
                )
                problems.append(
                    f"{service}/{path.name}: {table}.{column}: JPA nullable={str(jpa_nullable).lower()}, "
                    f"SQL nullable={str(sql_nullable).lower()} — {direction}"
                )
    return problems


class SchemaContractTest(unittest.TestCase):
    def test_every_entity_column_matches_migrations(self) -> None:
        problems = mismatches()
        self.assertEqual([], problems, "\n" + "\n".join(sorted(problems)))

    def test_guard_is_not_vacuous(self) -> None:
        # Если парсер перестанет находить сущности или таблицы, тест выше станет
        # зелёным ни о чём. Проверяем охват.
        found_entities = entities(ROOT)
        self.assertGreaterEqual(len(found_entities), 25, "сущности не найдены")

        schema_count = 0
        for service, files in migrations_by_service(ROOT).items():
            schema_count += len(apply_migrations(files)) if files else 0
        self.assertGreaterEqual(schema_count, 35, "таблицы из миграций не разобраны")

    def test_detects_known_bug_if_it_returns(self) -> None:
        """Мутация: колонка NOT NULL в SQL против nullable=true в сущности."""
        sql = "CREATE TABLE t (id UUID PRIMARY KEY, secret_enc TEXT NOT NULL);"
        schema = apply_migrations_from_sql(sql)
        self.assertTrue(schema["t"]["secret_enc"], "NOT NULL должен читаться как запрет NULL")

        source = '@Entity @Table(name = "t") class T {\n' \
                 '  @Column(name = "secret_enc", nullable = true) private String secretEnc;\n}'
        columns = entity_columns(source)
        self.assertEqual([("Column", "secret_enc", True)], columns)

        relaxed = apply_migrations_from_sql(
            "CREATE TABLE t (id UUID PRIMARY KEY, secret_enc TEXT NOT NULL);\n"
            "ALTER TABLE t ALTER COLUMN secret_enc DROP NOT NULL;"
        )
        self.assertFalse(relaxed["t"]["secret_enc"], "DROP NOT NULL должен применяться")

    def test_parses_multi_column_line(self) -> None:
        # Одна строка с несколькими колонками — реальный случай в V1 auth-service.
        schema = apply_migrations_from_sql(
            "CREATE TABLE login_attempts (id BIGSERIAL PRIMARY KEY, "
            "username VARCHAR(64) NOT NULL, ip_address INET, success BOOLEAN NOT NULL);"
        )
        self.assertTrue(schema["login_attempts"]["username"])
        self.assertFalse(schema["login_attempts"]["ip_address"], "ip_address допускает NULL")
        self.assertTrue(schema["login_attempts"]["success"])


def apply_migrations_from_sql(sql: str) -> SCHEMA:
    import tempfile

    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8") as handle:
        handle.write(sql)
        path = Path(handle.name)
    try:
        return apply_migrations([path])
    finally:
        path.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
