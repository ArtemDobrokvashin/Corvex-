"""Structural checks for the calendar smoke; live HTTP remains a PC acceptance test."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class CalendarSmokeStructureTest(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / 'scripts/calendar_smoke.py').read_text(encoding='utf-8')

    def test_uses_gateway_paths_from_the_controller(self):
        for path in ('/api/v1/auth/login', '/api/v1/auth/me', '/api/v1/mail/folders',
                     '/api/v1/calendar/events'):
            self.assertIn(path, self.source)

    def test_checks_conflict_and_removal(self):
        self.assertIn('expect_status(\n            "PUT"', self.source)
        self.assertIn('409', self.source)
        self.assertIn('404', self.source)
        self.assertIn('revision', self.source)

    def test_never_prints_or_reports_the_password(self):
        # Пароль уходит только в тело запроса на вход: ни print, ни отчёт его не содержат.
        self.assertNotIn('print(password', self.source)
        self.assertIn('getpass', self.source)
        self.assertNotIn('"password"', self.source.split('metadata = {', 1)[1])

    def test_cleans_up_its_own_event_only(self):
        self.assertIn('def delete_quietly', self.source)
        self.assertIn('run_id', self.source)
        self.assertIn('--keep', self.source)


if __name__ == '__main__':
    unittest.main()
