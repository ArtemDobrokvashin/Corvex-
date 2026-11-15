"""Structural checks; Docker build and the Windows script must be tested separately."""
from pathlib import Path
import unittest


class GuardImageBuildTest(unittest.TestCase):
    def test_source_is_immutable_and_image_has_unique_tag(self):
        text = (Path(__file__).resolve().parents[1] / 'build-av-guard-candidate.ps1').read_bytes().decode('ascii')
        for required in ("$commit = 'eda8c7d'", '[guid]::NewGuid()', "git archive '--format=tar'",
                         'services/mail-antivirus-service', 'docker image ls -q --filter',
                         'docker build --label', 'candidate-manifest.json', 'previousImageId',
                         'candidateImageId', 'no service was deployed or restarted'):
            self.assertIn(required, text)
        for forbidden in ('docker compose up', 'docker compose down', 'docker compose stop',
                          'docker compose restart', 'docker volume', 'docker system prune',
                          'docker image rm', 'docker run', 'Remove-Item', 'git reset'):
            self.assertNotIn(forbidden, text)
        self.assertIn('AV container changed during build', text)
        self.assertIn('original AV image no longer visible', text)


if __name__ == '__main__':
    unittest.main()
