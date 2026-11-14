"""Structural guard only; no deployed ClamAV, AMQP container or recovery is tested."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LimitAlertCandidateTest(unittest.TestCase):
    def test_overlay_only_sets_the_alert_in_an_opt_in_file(self):
        candidate = (ROOT / 'infra/james/candidates/clamav-limit-alert.compose.yml').read_text(encoding='utf-8')
        active = (ROOT / 'docker-compose.yml').read_text(encoding='utf-8')
        self.assertIn('CLAMD_CONF_AlertExceedsMax: "yes"', candidate)
        self.assertNotIn('CLAMD_CONF_AlertExceedsMax', active)
        self.assertNotIn('ports:', candidate)
        self.assertNotIn('volumes:', candidate)
        self.assertNotIn('entrypoint:', candidate)

    def test_alert_not_published_and_is_not_unavailable_retry(self):
        base = ROOT / 'services/mail-antivirus-service/src/main/java/ru/corvex/mailav/pipeline'
        listener = (base / 'AntivirusCheckListener.java').read_text(encoding='utf-8')
        self.assertLess(listener.index('Heuristics.Limits.Exceeded'), listener.index('new MailPipelineEvents.AvCheckedEvent'))
        self.assertIn('throw new AvScanLimitExceededException()', listener)
        error = (base / 'AvScanLimitExceededException.java').read_text(encoding='utf-8')
        self.assertIn('extends IOException', error)
        self.assertNotIn('extends AvVerdictUnavailableException', error)
        recoverer = (base / 'AvRetryRecoverer.java').read_text(encoding='utf-8')
        self.assertIn('fallback.recover(message, cause)', recoverer)


if __name__ == '__main__':
    unittest.main()
