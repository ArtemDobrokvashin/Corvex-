"""Static safety checks; this is not a James or PowerShell execution test."""
from pathlib import Path
import unittest


class JamesAvPreparationTest(unittest.TestCase):
    def test_prepares_without_runtime_mutation(self):
        text = (Path(__file__).resolve().parents[1] / 'prepare-james-av-runtime.ps1').read_bytes().decode('ascii')
        for item in ('9e33cfa^{commit}', 'apache/james:postgres-3.9.1', 'Custom mailet config or extension mount already exists',
                     'original-mailetcontainer.xml', 'test-real-dependencies.ps1',
                     'Running mailet config changed during preparation.', 'preparation-manifest.json',
                     'networks, queues and mail were not changed'):
            self.assertIn(item, text)
        for forbidden in ('docker compose up', 'docker compose down', 'docker restart', 'docker network',
                          'docker exec', 'docker image tag', 'Remove-Item', 'git reset', 'git clean'):
            self.assertNotIn(forbidden, text)


if __name__ == '__main__':
    unittest.main()
