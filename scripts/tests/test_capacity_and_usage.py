"""Structural and arithmetic checks for the sizing helpers."""
import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    # dataclasses look the module up in sys.modules, so register it before exec.
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


class CapacityTest(unittest.TestCase):
    def setUp(self):
        self.capacity = load("capacity")

    def test_fifty_mailboxes_fit_a_small_server(self):
        result = self.capacity.estimate(50)
        # 50 x 5 GiB at 20% fill with 25% dedupe: ~37.5 GiB of mail.
        self.assertAlmostEqual(result.mailbox_data_gib, 37.5, delta=0.2)
        self.assertLess(result.data_disk_gib, 80)
        self.assertGreaterEqual(result.recommended_data_disk_gib, result.data_disk_gib)
        self.assertLess(result.recommended_data_disk_gib, 500)

    def test_scaling_is_linear_for_mail_and_search(self):
        small = self.capacity.estimate(50)
        large = self.capacity.estimate(1000)
        # Fixed costs (chat storage, monitoring) do not scale, so compare the
        # components that must: mail volume and the search index.
        self.assertAlmostEqual(large.mailbox_data_gib / small.mailbox_data_gib, 20, delta=0.1)
        self.assertAlmostEqual(large.search_gib / small.search_gib, 20, delta=0.2)
        self.assertGreater(large.recommended_data_disk_gib, small.recommended_data_disk_gib)

    def test_backup_on_same_disk_is_more_expensive_and_flagged(self):
        separate = self.capacity.estimate(50)
        together = self.capacity.estimate(50, backup_on_same_disk=True)
        self.assertGreater(together.total_gib, separate.total_gib)
        self.assertEqual(separate.backup_disk_gib > 0, True)
        self.assertTrue(any("том же диске" in note for note in together.notes))

    def test_quota_raise_increases_data(self):
        base = self.capacity.estimate(50, quota_gib=5)
        doubled = self.capacity.estimate(50, quota_gib=10)
        self.assertGreater(doubled.mailbox_data_gib, base.mailbox_data_gib * 1.9)

    def test_rejects_invalid_input(self):
        for kwargs in ({"mailboxes": 0}, {"quota_gib": 0}, {"fill_percent": 101},
                       {"dedupe": 1.0}, {"years": 0}):
            with self.assertRaises(ValueError):
                self.capacity.estimate(**{"mailboxes": 50, **kwargs})


class RuntimeUsageTest(unittest.TestCase):
    def setUp(self):
        self.usage = load("runtime_usage")

    def test_parses_docker_stats_memory_line(self):
        match = self.usage.MEMORY.search("1.5GiB / 2GiB")
        self.assertIsNotNone(match)
        self.assertEqual(self.usage.to_bytes(match.group(1), match.group(2)), 1.5 * 1024 ** 3)

    def test_handles_mebibytes_and_plain_bytes(self):
        self.assertEqual(self.usage.to_bytes("512", "MiB"), 512 * 1024 ** 2)
        self.assertEqual(self.usage.to_bytes("900", "B"), 900)

    def test_script_is_read_only(self):
        source = (ROOT / "scripts" / "runtime_usage.py").read_text(encoding="utf-8")
        for forbidden in ("compose down", "compose up", "compose restart", "prune", "rm -"):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()


class PreflightScriptTest(unittest.TestCase):
    def setUp(self):
        self.raw = (ROOT / "scripts" / "preflight-server.ps1").read_bytes()

    def test_has_utf8_bom_for_windows_powershell_51(self):
        # PowerShell 5.1 reads UTF-8 without BOM as the ANSI code page, which
        # turns Russian messages into mojibake. The BOM keeps them readable.
        self.assertTrue(self.raw.startswith(b"\xef\xbb\xbf"))

    def test_is_read_only(self):
        text = self.raw.decode("utf-8-sig")
        for forbidden in ("compose down", "compose up", "compose restart",
                          "Remove-Item", "docker volume rm", "docker system prune"):
            self.assertNotIn(forbidden, text)

    def test_covers_domain_free_checks(self):
        text = self.raw.decode("utf-8-sig")
        for check in ("docker compose config -q", "capacity.py",
                      "CORVEX_INTERNAL_API_TOKEN", "Get-NetTCPConnection", "w32tm"):
            self.assertIn(check, text)
