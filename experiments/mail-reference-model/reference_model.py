"""Isolated reference-lifecycle prototype. NOT a production mail store.

Uses SQLite to exercise the state machine only; it does not manage files and is
not a substitute for the PostgreSQL, filesystem, or crash-recovery tests.
"""
import sqlite3

SCHEMA = """
PRAGMA foreign_keys = ON;
CREATE TABLE body (id TEXT PRIMARY KEY, state TEXT NOT NULL
                   CHECK (state IN ('ACTIVE', 'GC_PENDING')));
CREATE TABLE reference (id TEXT PRIMARY KEY, body_id TEXT NOT NULL REFERENCES body(id));
CREATE TRIGGER reference_only_active BEFORE INSERT ON reference
WHEN (SELECT state FROM body WHERE id = NEW.body_id) != 'ACTIVE'
BEGIN SELECT RAISE(ABORT, 'body is not active'); END;
CREATE TRIGGER reference_immutable BEFORE UPDATE OF body_id ON reference
BEGIN SELECT RAISE(ABORT, 'reference body is immutable'); END;
CREATE TRIGGER pending_only_unreferenced BEFORE UPDATE OF state ON body
WHEN NEW.state = 'GC_PENDING' AND EXISTS (SELECT 1 FROM reference WHERE body_id = OLD.id)
BEGIN SELECT RAISE(ABORT, 'body still referenced'); END;
CREATE TRIGGER no_reactivation BEFORE UPDATE OF state ON body
WHEN OLD.state = 'GC_PENDING' AND NEW.state != 'GC_PENDING'
BEGIN SELECT RAISE(ABORT, 'body cannot be reactivated'); END;
"""


def connect(path):
    db = sqlite3.connect(path, timeout=5, isolation_level=None)
    db.execute('PRAGMA foreign_keys = ON')
    return db


def initialize(path):
    with connect(path) as db:
        db.executescript(SCHEMA)


def attach(db, ref_id, body_id):
    """Atomic only in the caller's transaction; DB trigger rejects pending bodies."""
    db.execute('INSERT INTO reference(id, body_id) VALUES (?, ?)', (ref_id, body_id))


def detach_and_retire(db, ref_id):
    """Serialize writers with BEGIN IMMEDIATE; caller must commit or roll back."""
    if not db.in_transaction:
        raise ValueError('an existing write transaction is required')
    row = db.execute('SELECT body_id FROM reference WHERE id = ?', (ref_id,)).fetchone()
    if row is None:
        raise KeyError(ref_id)
    body_id = row[0]
    db.execute('DELETE FROM reference WHERE id = ?', (ref_id,))
    db.execute("UPDATE body SET state = 'GC_PENDING' WHERE id = ? AND state = 'ACTIVE' "
               "AND NOT EXISTS (SELECT 1 FROM reference WHERE body_id = ?)", (body_id, body_id))
