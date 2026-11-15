"""Experimental local SQLite import state model, NOT a migration runner.

No network operations. SUBMITTING on restart means the outcome is unknown.
A proof of absence from an external authority is needed before retrying it.
"""
from __future__ import annotations
import re
import sqlite3
from contextlib import contextmanager
from uuid import UUID

HASH = re.compile(r'^[0-9a-f]{64}$')
SCHEMA = '''
CREATE TABLE IF NOT EXISTS imports (
  source_id TEXT PRIMARY KEY,
  sha256 TEXT NOT NULL,
  size INTEGER NOT NULL CHECK(size >= 0),
  state TEXT NOT NULL CHECK(state IN ('pending','submitting','uncertain','imported','verified')),
  target_id TEXT,
  CHECK((state IN ('imported','verified') AND target_id IS NOT NULL)
        OR (state IN ('pending','submitting','uncertain') AND target_id IS NULL))
);
'''

class JournalError(Exception):
    pass

class Journal:
    def __init__(self, path):
        self.db = sqlite3.connect(path, timeout=5, isolation_level=None)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.executescript(SCHEMA)

    def close(self):
        self.db.close()

    @contextmanager
    def transaction(self):
        self.db.execute('BEGIN IMMEDIATE')
        try:
            yield
            self.db.execute('COMMIT')
        except BaseException:
            self.db.execute('ROLLBACK')
            raise

    def register(self, source_id, digest, size):
        try:
            if str(UUID(source_id)) != source_id:
                raise ValueError()
        except (TypeError, ValueError, AttributeError):
            raise JournalError('invalid source UUID') from None
        if not isinstance(digest, str) or not HASH.fullmatch(digest):
            raise JournalError('invalid hash')
        if type(size) is not int or size < 0 or size > 2**63-1:
            raise JournalError('invalid size')
        with self.transaction():
            row = self.db.execute('SELECT sha256,size FROM imports WHERE source_id=?', (source_id,)).fetchone()
            if row is None:
                self.db.execute("INSERT INTO imports(source_id,sha256,size,state) VALUES(?,?,?,'pending')", (source_id, digest, size))
            elif row != (digest, size):
                raise JournalError('source bytes changed; stop and investigate')

    def _transition(self, source_id, expected, state, target_id=None):
        with self.transaction():
            cur = self.db.execute('UPDATE imports SET state=?,target_id=? WHERE source_id=? AND state=?',
                                  (state, target_id, source_id, expected))
            if cur.rowcount != 1:
                raise JournalError('invalid or concurrent transition')

    def start(self, source_id):
        self._transition(source_id, 'pending', 'submitting')

    def mark_uncertain(self, source_id):
        self._transition(source_id, 'submitting', 'uncertain')

    def imported(self, source_id, target_id):
        if not isinstance(target_id, str) or not target_id or len(target_id) > 1024 or '\x00' in target_id:
            raise JournalError('invalid target id')
        self._transition(source_id, 'submitting', 'imported', target_id)

    def verify(self, source_id, target_id, digest, size):
        # Verification is against independently retrieved target bytes, not the import response.
        with self.transaction():
            cur = self.db.execute("UPDATE imports SET state='verified' WHERE source_id=? AND state='imported' AND target_id=? AND sha256=? AND size=?",
                                  (source_id, target_id, digest, size))
            if cur.rowcount != 1:
                raise JournalError('target does not match or state changed')

    def recover_after_exclusive_restart(self):
        # Caller must prove no previous worker is still running. SQLite cannot prove it.
        with self.transaction():
            return self.db.execute("UPDATE imports SET state='uncertain' WHERE state='submitting'").rowcount

    def state(self, source_id):
        return self.db.execute('SELECT state,target_id FROM imports WHERE source_id=?', (source_id,)).fetchone()
