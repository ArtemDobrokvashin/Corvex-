"""Structural safeguards only, not a James/ClamAV integration test."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class JamesAvCandidateTest(unittest.TestCase):
    def test_candidate_is_not_mounted(self):
        compose = (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
        self.assertNotIn('james-av-gate', compose)
        self.assertNotIn('CorvexClamAVGate', compose)

    def test_has_explicit_three_outcomes_and_no_headers(self):
        p = ROOT / 'experiments/james-av-gate/src/main/java/ru/corvex/james/av/CorvexClamAVGate.java'
        s = p.read_text(encoding='utf-8')
        self.assertIn('mail.setState(UNKNOWN)', s)
        self.assertIn('mail.setState(INFECTED)', s)
        self.assertIn('stream: OK', s)
        self.assertIn('Scanner reply too long', s)
        self.assertNotIn('getAttribute(', s)
        self.assertNotIn('getHeader(', s)


if __name__ == '__main__':
    unittest.main()
