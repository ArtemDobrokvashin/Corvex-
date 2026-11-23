"""Ubuntu как основная платформа: то, что ломает вход и старт, а не косметика.

Три вещи проверяются здесь, и каждая соответствует настоящему отказу:

1. **Вход по IP без домена.** nginx слушает 443 на любом имени, но web-gateway
   проверяет `Origin` по `CORVEX_WEB_ORIGINS`. Пока там `https://mail.company.local`,
   браузер по `https://<IP>` получает отказ на каждом запросе и интерфейс молчит —
   в логах сервисов при этом нет ни одной ошибки.
2. **`server_name` с одним именем.** Тогда по IP nginx отдаёт дефолтный сервер, а не
   Corvex: страница не открывается вовсе. Catch-all `_` чинит и это.
3. **`vm.max_map_count`.** На чистой Ubuntu 65530, Elasticsearch требует 262144;
   без этого он уходит в рестарт-петлю, и это выглядит как «мало памяти».
"""

from __future__ import annotations

import importlib.util
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / "scripts"

NGINX = (ROOT / "infra" / "nginx" / "conf.d" / "corvex.conf").read_text(encoding="utf-8")
PREPARE = (SCRIPTS / "prepare-ubuntu.sh").read_text(encoding="utf-8")

spec = importlib.util.spec_from_file_location("ubuntu_origin", SCRIPTS / "ubuntu_origin.py")
ubuntu_origin = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(ubuntu_origin)


class OriginTest(unittest.TestCase):
    def test_empty_origins_become_the_ip(self):
        text = "CORVEX_DOMAIN=mail.company.local\nCORVEX_WEB_ORIGINS=\n"
        updated = ubuntu_origin.rewrite(text, domain="", ip="203.0.113.10")
        self.assertIn("CORVEX_WEB_ORIGINS=https://203.0.113.10", updated)

    def test_default_domain_origin_is_replaced_not_kept(self):
        # Оставить https://mail.company.local рядом с IP значило бы «вход по IP
        # разрешён», но с лишним мусорным origin в списке.
        text = "CORVEX_WEB_ORIGINS=https://mail.company.local\n"
        updated = ubuntu_origin.rewrite(text, domain="", ip="203.0.113.10")
        self.assertNotIn("mail.company.local", updated.split("CORVEX_WEB_ORIGINS=")[1])
        self.assertIn("https://203.0.113.10", updated)

    def test_manual_list_is_extended_not_overwritten(self):
        text = "CORVEX_WEB_ORIGINS=https://mail.company.ru,http://localhost:8080\n"
        updated = ubuntu_origin.rewrite(text, domain="mail.company.ru", ip="203.0.113.10")
        value = re.search(r"^CORVEX_WEB_ORIGINS=(.*)$", updated, re.M).group(1)
        self.assertEqual("https://mail.company.ru,http://localhost:8080,https://203.0.113.10", value)

    def test_domain_updates_corvex_domain(self):
        text = "CORVEX_DOMAIN=mail.company.local\nCORVEX_WEB_ORIGINS=\n"
        updated = ubuntu_origin.rewrite(text, domain="mail.company.ru", ip="")
        self.assertIn("CORVEX_DOMAIN=mail.company.ru", updated)
        self.assertNotIn("CORVEX_DOMAIN=mail.company.local", updated)

    def test_repeated_run_is_idempotent(self):
        text = "CORVEX_WEB_ORIGINS=\n"
        once = ubuntu_origin.rewrite(text, domain="mail.company.ru", ip="203.0.113.10")
        twice = ubuntu_origin.rewrite(once, domain="mail.company.ru", ip="203.0.113.10")
        self.assertEqual(once, twice)

    def test_value_is_appended_when_the_variable_is_absent(self):
        updated = ubuntu_origin.rewrite("POSTGRES_USER=corvex\n", domain="", ip="10.0.0.5")
        self.assertIn("CORVEX_WEB_ORIGINS=https://10.0.0.5", updated)

    def test_comments_and_order_are_preserved(self):
        text = "# комментарий про origin\nPOSTGRES_USER=corvex\nCORVEX_WEB_ORIGINS=\n"
        updated = ubuntu_origin.rewrite(text, domain="", ip="10.0.0.5")
        self.assertTrue(updated.startswith("# комментарий про origin\n"))
        self.assertLess(updated.index("POSTGRES_USER"), updated.index("CORVEX_WEB_ORIGINS"))


class MainTest(unittest.TestCase):
    def run_main(self, env_path: Path, argv: list[str]) -> int:
        return ubuntu_origin.main(["--env", str(env_path), *argv])

    def test_bom_and_crlf_are_removed(self):
        # BOM — типичный FAIL после правки .env в Windows: docker compose падает
        # на "unexpected character \\ufeff in variable name".
        import tempfile

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_bytes(b"\xef\xbb\xbfCORVEX_WEB_ORIGINS=\r\n")
            self.assertEqual(0, self.run_main(path, ["--ip", "10.0.0.5"]))
            data = path.read_bytes()
        self.assertFalse(data.startswith(b"\xef\xbb\xbf"))
        self.assertNotIn(b"\r", data)

    def test_missing_env_is_an_error_not_a_silent_creation(self):
        import io
        import tempfile
        from contextlib import redirect_stderr

        with tempfile.TemporaryDirectory() as directory:
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                code = self.run_main(Path(directory) / ".env", ["--ip", "10.0.0.5"])
        self.assertEqual(1, code)
        self.assertIn("first-run.sh", stderr.getvalue())

    def test_requires_a_domain_or_an_ip(self):
        with self.assertRaises(SystemExit) as raised:
            ubuntu_origin.parse_args([])
        self.assertNotEqual(0, raised.exception.code)


class NginxTest(unittest.TestCase):
    def test_https_server_is_a_catch_all(self):
        # Иначе запрос по IP попадает в дефолтный сервер nginx, и Corvex «не
        # открывается» — при том что контейнеры healthy.
        https_block = NGINX.split("listen 443 ssl http2;", 1)[1].split("server {", 1)[0]
        self.assertIn("server_name _;", https_block)
        self.assertNotIn("server_name mail.company.local;", NGINX)

    def test_acme_challenge_still_served_on_80(self):
        self.assertIn("location /.well-known/acme-challenge/", NGINX)

    def test_grafana_allowlist_is_still_explicit(self):
        # Catch-all server_name не должен был задеть ограничение доступа к Grafana.
        self.assertIn("allow ", NGINX)
        self.assertIn("deny all", NGINX)


class PrepareUbuntuTest(unittest.TestCase):
    def test_script_is_strict_and_checks_root(self):
        self.assertIn("set -Eeuo pipefail", PREPARE)
        self.assertIn('"$(id -u)" -ne 0', PREPARE)

    def test_elasticsearch_sysctl_is_persistent(self):
        self.assertIn("vm.max_map_count=262144", PREPARE)
        self.assertIn("/etc/sysctl.conf", PREPARE)

    def test_docker_comes_from_the_official_repository_not_snap(self):
        self.assertIn("download.docker.com/linux/ubuntu", PREPARE)
        self.assertNotIn("snap install docker", PREPARE)
        self.assertIn("docker-compose-plugin", PREPARE)

    def test_non_root_usage_does_not_require_sudo_per_command(self):
        self.assertIn("usermod -aG docker", PREPARE)

    def test_docker_autostart_is_enabled(self):
        self.assertIn("systemctl enable --now docker", PREPARE)

    def test_check_mode_changes_nothing(self):
        self.assertIn("--check", PREPARE)
        # В режиме проверки не должно быть ни apt-get, ни sysctl -w: это режим
        # «покажи, что не так», его зовут до каких-либо изменений.
        check_block = PREPARE.split('if [[ "$CHECK_ONLY" -eq 1 ]]', 1)[1].split("fi", 1)[0]
        self.assertIn("exit", check_block)

    def test_enough_disk_is_required(self):
        self.assertIn("нужно ≥ 100", PREPARE)

    def test_origin_update_happens_before_the_first_up(self):
        # prepare-ubuntu зовёт ubuntu_origin.py и не поднимает стек сам: поднятие
        # остаётся за человеком, иначе подготовка хоста и запуск смешиваются.
        self.assertIn("scripts/ubuntu_origin.py", PREPARE)
        executing = [line for line in PREPARE.splitlines()
                     if not line.strip().startswith("#")]
        # Строка с подсказкой человеку — это `say`, а не запуск. Ищем именно вызов.
        self.assertFalse(any("docker compose up" in line and "say" not in line
                             for line in executing))


if __name__ == "__main__":
    unittest.main()
