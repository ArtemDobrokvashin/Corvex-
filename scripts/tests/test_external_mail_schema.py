"""Guard: OAuth-ящик обязан вставляться в БД без пароля.

21 ноября 2026 подключение Gmail через OAuth падало на живом стенде с HTTP 500:

    ERROR: null value in column "secret_enc" of relation "external_mail_accounts"
    violates not-null constraint   (SQLState 23502)

Код был прав по смыслу — у OAuth-ящика пароля нет, токены лежат в отдельной таблице,
и юнит-тест `ExternalMailAccountServiceTest` прямо утверждал `secretEnc == null`. Но схема
из `V4` объявляла `secret_enc TEXT NOT NULL`, а тест работал с замоканным репозиторием
и до PostgreSQL не доходил. Ни один тест не сравнивал аннотацию JPA с фактической схемой.

Этот тест сравнивает. Он читает миграции в порядке версий и сущность, а не доверяет
комментариям: «в коде написано nullable» и «в базе разрешено NULL» — разные утверждения.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICE = ROOT / "services" / "mail-storage-service"
MIGRATIONS = SERVICE / "src" / "main" / "resources" / "db" / "migration"
ENTITY = (
    SERVICE
    / "src"
    / "main"
    / "java"
    / "ru"
    / "corvex"
    / "mailstorage"
    / "domain"
    / "ExternalMailAccount.java"
)
TABLE = "external_mail_accounts"
COLUMN = "secret_enc"


def ordered_migrations() -> list[tuple[int, Path]]:
    found = []
    for path in MIGRATIONS.glob("V*__*.sql"):
        match = re.match(r"V(\d+)__", path.name)
        if match:
            found.append((int(match.group(1)), path))
    return sorted(found)


def column_state(column: str = COLUMN) -> tuple[bool, list[str]]:
    """(разрешён ли NULL после всех миграций, файлы, которые это изменили)."""
    nullable = False
    mentioned = False
    history: list[str] = []
    for _, path in ordered_migrations():
        sql = " ".join(path.read_text(encoding="utf-8").split())
        if re.search(rf"CREATE TABLE {TABLE}\b", sql):
            created = re.search(rf"CREATE TABLE {TABLE}\b(.*?);", sql, re.S)
            if created and re.search(rf"\b{column}\s+\w+(\(\d+\))?\s+NOT NULL", created.group(1)):
                nullable = False
                mentioned = True
                history.append(f"{path.name}: NOT NULL при создании")
            elif created and re.search(rf"\b{column}\b", created.group(1)):
                nullable = True
                mentioned = True
                history.append(f"{path.name}: колонка без NOT NULL")
        for alter in re.finditer(
            rf"ALTER TABLE {TABLE}\s+ALTER COLUMN {column}\s+(DROP|SET) NOT NULL", sql, re.I
        ):
            nullable = alter.group(1).upper() == "DROP"
            mentioned = True
            history.append(f"{path.name}: {alter.group(1).upper()} NOT NULL")
    return nullable, history


def entity_nullable(column: str = COLUMN) -> bool | None:
    source = ENTITY.read_text(encoding="utf-8")
    match = re.search(
        rf'@Column\([^)]*name\s*=\s*"{column}"[^)]*\)', source
    )
    if not match:
        return None
    nullable = re.search(r"nullable\s*=\s*(true|false)", match.group(0))
    if not nullable:
        return None
    return nullable.group(1) == "true"


class OAuthSecretNullabilityTest(unittest.TestCase):
    def test_column_state_is_discovered(self):
        """Проверка самого разбора: без неё тест мог бы «проходить» на пустом месте."""
        nullable, history = column_state()
        self.assertTrue(history, "не найдено ни одного упоминания secret_enc в миграциях")
        self.assertIn("V4__external_mail_connector.sql: NOT NULL при создании", history)

    def test_schema_allows_null_after_all_migrations(self):
        nullable, history = column_state()
        self.assertTrue(
            nullable,
            "после всех миграций secret_enc всё ещё NOT NULL, а OAuth-ящик пишет туда NULL "
            "(HTTP 500 при подключении Gmail): " + "; ".join(history),
        )

    def test_entity_matches_schema(self):
        nullable, _ = column_state()
        declared = entity_nullable()
        self.assertIsNotNone(declared, "у secret_enc нет явного nullable в @Column")
        self.assertEqual(
            nullable,
            declared,
            "аннотация JPA и схема расходятся: сущность "
            f"nullable={declared}, база nullable={nullable}",
        )

    def test_password_accounts_still_require_secret(self):
        """Инвариант не потерян: NULL разрешён только для OAuth2."""
        sql = "\n".join(path.read_text(encoding="utf-8") for _, path in ordered_migrations())
        checks = re.findall(
            rf"ALTER TABLE {TABLE}\s+ADD CONSTRAINT \w+\s+CHECK \((.*?)\);", sql, re.S | re.I
        )
        oauth_check = [c for c in checks if "OAUTH2" in c.upper() and COLUMN in c]
        self.assertTrue(
            oauth_check,
            "нет CHECK-констрейнта, который требует secret_enc для парольных ящиков: "
            "после DROP NOT NULL пустой пароль стал бы допустим для всех типов входа",
        )


if __name__ == "__main__":
    unittest.main()
