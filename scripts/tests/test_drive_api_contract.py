"""Guard: the drive UI and the drive backend must speak the same API.

Two agents worked on the personal drive in parallel: the backend in
`chat-file-service` and the page in `frontend/src/features/files`. The frontend
was written against a contract document while the real controller lived on a
different branch, so nothing but a test would notice a path or verb drift.
A mismatch here is a 404/405 that only appears live, after a rebuild.

The test parses the actual call sites in `driveApi.ts` and the actual mapping
annotations in `DriveController.java` instead of comparing two hand-kept lists.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT / "frontend" / "src" / "api" / "driveApi.ts"
CONTROLLER = (
    ROOT
    / "services"
    / "chat-file-service"
    / "src"
    / "main"
    / "java"
    / "ru"
    / "corvex"
    / "chatfile"
    / "drive"
    / "DriveController.java"
)
SERVICE = (
    ROOT
    / "services"
    / "chat-file-service"
    / "src"
    / "main"
    / "java"
    / "ru"
    / "corvex"
    / "chatfile"
    / "drive"
    / "DriveService.java"
)


def local_paths(text: str) -> dict[str, str]:
    """Простые `const name = \`...\`` в клиенте — чтобы понять apiBlob(path)."""
    return {
        match.group(1): match.group(2)
        for match in re.finditer(
            r"const\s+(\w+)\s*=\s*(`[^`]*`|'[^']*'|\"[^\"]*\")", text
        )
    }


def frontend_calls(text: str) -> set[tuple[str, str]]:
    """(method, path) for every apiFetch/apiBlob call in the drive client.

    Аргументы вырезаются по балансу скобок, а не регуляркой: в options лежат
    вложенные объекты вроде JSON.stringify({parentId, name}), и простой шаблон
    обрывает их на первой закрывающей скобке.
    """
    calls = set()
    paths = local_paths(text)
    for head in re.finditer(r"(?:apiFetch|apiBlob)(?:<[^>]*>)?\(", text):
        start = head.end()
        depth = 1
        index = start
        while index < len(text) and depth:
            if text[index] == "(":
                depth += 1
            elif text[index] == ")":
                depth -= 1
            index += 1
        args = text[start : index - 1]
        path_match = re.match(r"\s*(`[^`]+`|\"[^\"]+\"|nodePath\(id\)|\w+)", args)
        if not path_match:
            continue
        raw_path = path_match.group(1)
        if raw_path in paths:
            # const path = `${nodePath(id)}/content`; apiBlob(path, ...)
            raw_path = paths[raw_path]
        verb = re.search(r"method:\s*'(\w+)'", args)
        calls.add((verb.group(1) if verb else "GET", normalize(raw_path)))
    return calls


def normalize(raw: str) -> str:
    if raw == "nodePath(id)":
        return "/{id}"
    value = raw.strip("`").strip('"')
    value = value.replace("${BASE}", "")
    value = value.replace("${nodePath(id)}", "/{id}")
    value = re.sub(r"\$\{encodeURIComponent\((\w+)\)\}", r"{\1}", value)
    value = re.sub(r"\$\{[^}]*\}", "", value)
    return value.split("?")[0] or "/"


def controller_mappings(text: str) -> set[tuple[str, str]]:
    """(method, path) from Spring annotations, relative to the class mapping."""
    class_paths = re.findall(r"@RequestMapping\(\{?\"([^\"]+)\"", text)
    if not class_paths:
        raise AssertionError("DriveController has no @RequestMapping")
    calls = set()
    for match in re.finditer(
        r"@(Get|Post|Patch|Delete)Mapping\((?:path\s*=\s*)?(?:value\s*=\s*)?\"([^\"]+)\"",
        text,
    ):
        calls.add((match.group(1).upper(), match.group(2)))
    # @GetMapping без пути на методе — это корень класса (/api/v1/drive).
    for match in re.finditer(r"@(Get|Post|Patch|Delete)Mapping\s*$", text, re.M):
        calls.add((match.group(1).upper(), "/"))
    return calls


class DriveApiContractTest(unittest.TestCase):
    def setUp(self):
        self.frontend_text = FRONTEND.read_text(encoding="utf-8")
        self.controller_text = CONTROLLER.read_text(encoding="utf-8")

    def test_frontend_calls_match_controller_mappings(self):
        frontend = frontend_calls(self.frontend_text)
        backend = controller_mappings(self.controller_text)

        # Публичная ссылка открывается браузером напрямую и в клиенте не вызывается.
        backend_without_public_share = {call for call in backend if call[1] != "/shared/{token}"}

        self.assertEqual(
            backend_without_public_share,
            frontend,
            "driveApi.ts and DriveController.java drifted apart:\n"
            f"  only in frontend: {sorted(frontend - backend_without_public_share)}\n"
            f"  only in backend:  {sorted(backend_without_public_share - frontend)}",
        )

    def test_public_share_endpoint_stays_anonymous(self):
        # Файл по ссылке скачивает браузер без JWT: если контроллер уедет за авторизацию,
        # ссылка перестанет работать у внешнего получателя.
        self.assertIn(("GET", "/shared/{token}"), controller_mappings(self.controller_text))
        self.assertIn('"/api/v1/drive/shared/**"', self._security_rule())
        self.assertIn('"/api/v1/drive/shared/**"', self._gateway_rule())

    def test_gateway_routes_the_drive_root(self):
        # Список файлов и квота запрашиваются как /api/v1/drive?view=files и
        # /api/v1/drive/usage. Маршрут только с /api/v1/drive/** отдаёт на них 404.
        #
        # Проверка обязана работать без PyYAML: тест, который молча пропускается,
        # уже пропустил реальную ошибку — неэкранированную запятую в списке предикатов.
        patterns = self._drive_predicate_patterns()
        self.assertIn("/api/v1/drive", patterns)
        self.assertIn("/api/v1/drive/**", patterns)

    def test_no_route_splits_a_predicate_on_a_comma(self):
        # Общий сторож по всем маршрутам: "predicates: [Name=a,b]" без кавычек —
        # это два элемента списка, второй из которых читается как имя предиката.
        # Конфиг при этом валиден как YAML и роняет gateway только на старте.
        for number, line in enumerate(self._gateway_text().splitlines(), start=1):
            stripped = line.strip()
            if not stripped.startswith("predicates: ["):
                continue
            inner = stripped[len("predicates: ["):].rstrip("]")
            quoted = ('"' in inner) or ("'" in inner)
            if "," in inner and not quoted:
                self.fail(f"line {number}: quote the predicate list — "
                          f"an unquoted comma splits it into two predicates: {stripped}")

    def _drive_predicate_patterns(self) -> list[str]:
        """Шаблоны Path для маршрута drive, полученные из разобранного YAML или построчно."""
        text = self._gateway_text()
        try:
            import yaml
        except ImportError:
            yaml = None

        if yaml is not None:
            config = yaml.safe_load(text)
            routes = config["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"]
            drive = next((route for route in routes if route["id"] == "drive"), None)
            self.assertIsNotNone(drive, "gateway has no drive route")
            predicates = drive["predicates"]
            self.assertEqual(1, len(predicates),
                             "drive route must have exactly one Path predicate, "
                             f"got {predicates}")
            return [value.strip() for value in predicates[0].split("=", 1)[1].split(",")]

        lines = text.splitlines()
        start = next(
            (index for index, line in enumerate(lines) if line.strip() == "- id: drive"),
            None,
        )
        self.assertIsNotNone(start, "gateway has no drive route")
        predicate_line = next(
            (line.strip() for line in lines[start + 1:start + 12]
             if line.strip().startswith("predicates:")),
            None,
        )
        self.assertIsNotNone(predicate_line, "drive route has no predicates")
        raw = predicate_line.split("predicates:", 1)[1].strip()
        self.assertTrue(raw.startswith("[") and raw.endswith("]"),
                        f"unexpected predicate format: {predicate_line}")
        inner = raw[1:-1]
        quoted = (inner.startswith('"') and inner.endswith('"')) or (
            inner.startswith("'") and inner.endswith("'"))
        if quoted:
            inner = inner[1:-1]
        else:
            # Без кавычек запятая — разделитель элементов YAML-списка, и шлюз
            # получает два предиката вместо одного. Это и роняло gateway на старте.
            self.assertNotIn(",", inner,
                             "quote the predicate list: an unquoted comma splits it "
                             f"into two predicates ({predicate_line})")
        return [value.strip() for value in inner.split("=", 1)[1].split(",")]

    def _gateway_text(self) -> str:
        return (
            ROOT
            / "services"
            / "web-gateway"
            / "src"
            / "main"
            / "resources"
            / "application.yml"
        ).read_text(encoding="utf-8")

    def _gateway_config(self) -> dict:
        try:
            import yaml
        except ImportError:  # зависимость есть в CI-образе, но не в голом Python
            self.skipTest("PyYAML is not installed")
        return yaml.safe_load(self._gateway_text())

    def test_upload_part_name_matches_backend(self):
        # Spring ждёт @RequestPart("file"); другое имя в FormData — 400 на каждой загрузке.
        self.assertIn("body.append('file'", self.frontend_text)
        self.assertIn('@RequestPart("file")', self.controller_text)

    def test_shared_link_is_built_from_server_path(self):
        # Токен нельзя конструировать на клиенте из UUID: сервер хранит только его хеш.
        page = (ROOT / "frontend" / "src" / "features" / "files" / "FilesPage.tsx").read_text(
            encoding="utf-8"
        )
        self.assertIn(".path", page)
        self.assertNotIn("/api/v1/drive/shared/", page)

    def _security_rule(self) -> str:
        return (
            ROOT
            / "services"
            / "chat-file-service"
            / "src"
            / "main"
            / "java"
            / "ru"
            / "corvex"
            / "chatfile"
            / "security"
            / "ChatFileSecurityConfig.java"
        ).read_text(encoding="utf-8")

    def _gateway_rule(self) -> str:
        return (
            ROOT
            / "services"
            / "web-gateway"
            / "src"
            / "main"
            / "java"
            / "ru"
            / "corvex"
            / "gateway"
            / "security"
            / "GatewaySecurityConfig.java"
        ).read_text(encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
