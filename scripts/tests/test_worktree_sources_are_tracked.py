"""Guard against losing work: every project source file must be in git.

Earlier sessions left whole features (owner-scoped drafts) untracked, so a
snapshot could have discarded them silently. This test fails when a source file
exists on disk but no tracked index knows about it.
"""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
SUFFIXES = {'.java', '.py', '.ps1', '.ts', '.tsx', '.sql', '.yml', '.yaml'}
SKIP_DIRS = {'node_modules', 'target', 'build', 'dist', '.git', '.cache', 'coverage', 'out',
             '.venv', 'venv', '__pycache__', '.idea', '.vscode'}


def _git(*args: str) -> str:
    return subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True, check=True).stdout


def _inside_git() -> bool:
    try:
        _git('rev-parse', '--is-inside-work-tree')
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False


class WorktreeSourcesTrackedTest(unittest.TestCase):
    def test_no_source_file_is_left_untracked(self):
        if not _inside_git():
            self.skipTest('not a git worktree')
        tracked = {line for line in _git('ls-files').splitlines() if line}
        missing = []
        for path in ROOT.rglob('*'):
            if not path.is_file() or path.suffix not in SUFFIXES:
                continue
            relative = path.relative_to(ROOT)
            if any(part in SKIP_DIRS for part in relative.parts):
                continue
            name = relative.as_posix()
            if name not in tracked:
                missing.append(name)
        self.assertEqual([], sorted(missing),
                         'Source files exist on disk but are not tracked by git; commit them '
                         'or add an explicit ignore rule.')


if __name__ == '__main__':
    unittest.main()
