"""Static safety checks only; Windows PowerShell and git integration require a PC run."""
from pathlib import Path
import unittest


class SourceHistoryTest(unittest.TestCase):
    def test_read_only_inventory(self):
        script = (Path(__file__).resolve().parents[1] / 'inspect-av-source-history.ps1').read_bytes().decode('ascii')
        for item in ('git ls-tree -r eda8c7d', "git log '--format=%H' eda8c7d -- $path",
                     'git hash-object -- $item.FullName', 'Reparse point detected',
                     'MISSING $path', 'UNKNOWN $path', 'HISTORY $path', 'EXTRA $path',
                     'no files, tags, containers or queues changed'):
            self.assertIn(item, script)
        for item in ('git reset', 'git clean', 'git checkout', 'docker ', 'Remove-Item',
                     'Copy-Item', 'Set-Content', 'Get-Content'):
            self.assertNotIn(item, script)


if __name__ == '__main__':
    unittest.main()
