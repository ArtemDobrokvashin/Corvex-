"""Static checks, not a Docker or Windows PowerShell test."""
from pathlib import Path
import unittest


class AvImagePlanTest(unittest.TestCase):
    def test_inspection_is_read_only_and_compares_commit_and_labels(self):
        text = (Path(__file__).resolve().parents[1] / 'inspect-av-image-plan.ps1').read_bytes().decode('ascii')
        for required in ('eda8c7d^{commit}', 'candidateImageId', 'previousImageId', 'previousContainerId',
                         'org.corvex.av-candidate.commit', 'com.docker.compose.config-hash',
                         'docker compose config --hash mail-antivirus-service',
                         'docker compose config --images mail-antivirus-service',
                         'foreach ($configured in $configuredImages)', 'no changes made'):
            self.assertIn(required, text)
        for forbidden in ('docker compose up', 'docker compose down', 'docker image tag',
                          'docker tag ', 'docker run', 'docker restart', 'docker image rm',
                          'Remove-Item', 'Set-Content', '.Config.Env'):
            self.assertNotIn(forbidden, text)


if __name__ == '__main__':
    unittest.main()
