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


def frontend_calls(text: str) -> set[tuple[str, str]]:
    """(method, path) for every apiFetch/apiBlob call in the drive client.

    Аргументы вырезаются по балансу скобок, а не регуляркой: в options лежат
    вложенные объекты вроде JSON.stringify({parentId, name}), и простой шаблон
    обрывает их на первой закрывающей скобке.
    """
    calls = set()
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
        path_match = re.match(r"\s*(`[^`]+`|\"[^\"]+\"|nodePath\(id\))", args)
        if not path_match:
            continue
        verb = re.search(r"method:\s*'(\w+)'", args)
        calls.add((verb.group(1) if verb else "GET", normalize(path_match.group(1))))
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
        # /api/v1/drive/usage. Если маршрут объявлен только как /api/v1/drive/**,
        # шлюз легко отдаёт 404 на корневой путь — ровно это и случилось живьём.
        import yaml

        config = yaml.safe_load(
            (
                ROOT
                / "services"
                / "web-gateway"
                / "src"
                / "main"
                / "resources"
                / "application.yml"
            ).read_text(encoding="utf-8")
        )
        routes = config["spring"]["cloud"]["gateway"]["server"]["webflux"]["routes"]
        drive = next((route for route in routes if route["id"] == "drive"), None)
        self.assertIsNotNone(drive, "gateway has no drive route")
        # Predicate в списке задан как "Path=<шаблон>" — сравниваем шаблоны,
        # а не строки целиком, иначе проверка цепляется к синтаксису YAML.
        patterns = {
            value
            for predicate in drive["predicates"]
            for value in str(predicate).split("=", 1)[-1].split(",")
        }
        self.assertIn("/api/v1/drive", patterns)
        self.assertIn("/api/v1/drive/**", patterns)

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
