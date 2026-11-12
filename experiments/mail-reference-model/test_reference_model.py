import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path

from reference_model import attach, connect, detach_and_retire, initialize


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = str(Path(self.tmp.name) / 'model.db')
        initialize(self.path)
        self.db = connect(self.path)
        self.addCleanup(self.db.close)
        self.db.execute("INSERT INTO body VALUES ('b', 'ACTIVE')")
        attach(self.db, 'r1', 'b')

    def retire(self, ref='r1'):
        self.db.execute('BEGIN IMMEDIATE')
        detach_and_retire(self.db, ref)
        self.db.commit()

    def test_last_reference_retired_and_cannot_be_reattached(self):
        self.retire()
        self.assertEqual('GC_PENDING', self.db.execute('SELECT state FROM body').fetchone()[0])
        with self.assertRaises(sqlite3.IntegrityError):
            attach(self.db, 'r2', 'b')
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE body SET state='ACTIVE' WHERE id='b'")

    def test_shared_body_not_retired(self):
        attach(self.db, 'r2', 'b')
        self.retire()
        self.assertEqual('ACTIVE', self.db.execute('SELECT state FROM body').fetchone()[0])
        self.retire('r2')
        self.assertEqual('GC_PENDING', self.db.execute('SELECT state FROM body').fetchone()[0])

    def test_rollback_restores_reference_and_state(self):
        self.db.execute('BEGIN IMMEDIATE')
        detach_and_retire(self.db, 'r1')
        self.db.rollback()
        self.assertEqual([('r1', 'b')], self.db.execute('SELECT * FROM reference').fetchall())
        self.assertEqual('ACTIVE', self.db.execute('SELECT state FROM body').fetchone()[0])

    def test_guard_blocks_direct_invalid_mutations(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("UPDATE body SET state='GC_PENDING' WHERE id='b'")
        with self.assertRaises(sqlite3.IntegrityError):
            attach(self.db, 'absent', 'missing')
        with self.assertRaises(ValueError):
            detach_and_retire(self.db, 'r1')

    def test_retirement_wins_against_competing_attach(self):
        # SQLite has a global writer lock; this is not a PostgreSQL locking test.
        self.db.execute('BEGIN IMMEDIATE')
        detach_and_retire(self.db, 'r1')
        started = threading.Event()
        outcome = []
        def worker():
            db = connect(self.path)
            try:
                started.set()
                attach(db, 'r2', 'b')
                outcome.append('attached')
            except sqlite3.IntegrityError:
                outcome.append('rejected')
            finally:
                db.close()
        thread = threading.Thread(target=worker)
        thread.start()
        try:
            self.assertTrue(started.wait(2))
            self.db.commit()
        finally:
            thread.join(6)
        self.assertFalse(thread.is_alive())
        self.assertEqual(['rejected'], outcome)
        self.assertEqual([], self.db.execute('SELECT * FROM reference').fetchall())

    def test_attach_before_retirement_preserves_body(self):
        attach(self.db, 'r2', 'b')
        self.retire()
        self.assertEqual(('ACTIVE',), self.db.execute('SELECT state FROM body').fetchone())
        self.assertEqual([('r2',)], self.db.execute('SELECT id FROM reference').fetchall())


if __name__ == '__main__':
    unittest.main()
