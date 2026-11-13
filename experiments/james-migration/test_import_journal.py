import tempfile
import os
import subprocess
import sys
import unittest
from pathlib import Path
from import_journal import Journal, JournalError

ID = 'f61b2fbc-7419-4eb0-8992-35841d0c77af'
HASH = 'a' * 64

class JournalTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'journal.db'
        self.j = Journal(self.path)
        self.j.register(ID, HASH, 23)

    def tearDown(self):
        self.j.close()
        self.tmp.cleanup()

    def test_normal_and_repeated_registration(self):
        self.j.register(ID, HASH, 23)
        self.j.start(ID)
        self.j.imported(ID, 'native')
        with self.assertRaises(JournalError): self.j.verify(ID, 'native', 'b' * 64, 23)
        self.assertEqual(self.j.state(ID), ('imported', 'native'))
        self.j.verify(ID, 'native', HASH, 23)
        self.assertEqual(self.j.state(ID), ('verified', 'native'))

    def test_changed_source_cannot_reuse_id(self):
        with self.assertRaises(JournalError): self.j.register(ID, 'b' * 64, 23)
        self.assertEqual(self.j.state(ID), ('pending', None))

    def test_crash_uncertain_does_not_auto_retry(self):
        self.j.start(ID)
        self.j.close()
        self.j = Journal(self.path)
        self.assertEqual(self.j.recover_after_exclusive_restart(), 1)
        with self.assertRaises(JournalError): self.j.start(ID)
        with self.assertRaises(JournalError): self.j.imported(ID, 'native')
        self.assertEqual(self.j.state(ID), ('uncertain', None))

    def test_abrupt_process_exit_leaves_unknown_state(self):
        code = ("import os,sys; from import_journal import Journal; "
                "j=Journal(sys.argv[1]); j.start(sys.argv[2]); os._exit(42)")
        env = dict(os.environ, PYTHONPATH=str(Path(__file__).resolve().parent))
        proc = subprocess.run([sys.executable, '-c', code, str(self.path), ID], env=env, check=False)
        self.assertEqual(proc.returncode, 42)
        self.assertEqual(self.j.state(ID), ('submitting', None))
        self.assertEqual(self.j.recover_after_exclusive_restart(), 1)
        with self.assertRaises(JournalError): self.j.start(ID)

    def test_concurrent_start_is_claimed_once(self):
        other = Journal(self.path)
        try:
            self.j.start(ID)
            with self.assertRaises(JournalError): other.start(ID)
        finally:
            other.close()

    def test_uncertain_outcome_remains_blocked(self):
        self.j.start(ID)
        self.j.mark_uncertain(ID)
        with self.assertRaises(JournalError): self.j.start(ID)
        self.assertEqual(self.j.recover_after_exclusive_restart(), 0)

if __name__ == '__main__': unittest.main()
