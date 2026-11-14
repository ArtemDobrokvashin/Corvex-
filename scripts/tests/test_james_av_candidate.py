"""Structural safeguards only, not a James/ClamAV integration test."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class JamesAvCandidateTest(unittest.TestCase):
    def test_candidate_is_not_mounted(self):
        compose = (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
        self.assertNotIn('james-av-gate', compose)
        self.assertNotIn('CorvexClamAVGate', compose)

    def test_dns_refresh_is_separate_from_deadline_and_has_expiry(self):
        gate = (ROOT / 'experiments/james-av-gate/src/main/java/ru/corvex/james/av/CorvexClamAVGate.java').read_text()
        cache = (ROOT / 'experiments/james-av-gate/src/main/java/ru/corvex/james/av/ClamdAddressCache.java').read_text()
        self.assertIn('cache.snapshot()', gate)
        self.assertIn('cache.close()', gate)
        self.assertIn('MAX_AGE_NANOS', cache)
        self.assertIn('scheduleWithFixedDelay', cache)
        self.assertIn('ScheduledThreadPoolExecutor(1', cache)
        self.assertIn('task.cancel(false)', cache)
        self.assertNotIn('getAllByName', gate.split('protected Socket getClamdSocket()', 1)[1])

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
