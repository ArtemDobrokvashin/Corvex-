"""Synthetic-only vertical slice: snapshot bytes -> journal -> IMAP -> verification.

NEVER use for real mail. APPENDUID is checked, but recovery remains incomplete.
"""
import hashlib
import imaplib
import os
import re
import time
from pathlib import Path
import probe
from import_journal import Journal, JournalError
from jmap_import_probe import RAW

SOURCE_ID = 'f61b2fbc-7419-4eb0-8992-35841d0c77af'


def number(value):
    if not isinstance(value, bytes) or not re.fullmatch(rb'[1-9][0-9]{0,9}', value):
        raise RuntimeError('Invalid synthetic IMAP identifier')
    if int(value) > 4294967295:
        raise RuntimeError('Invalid synthetic IMAP identifier')
    return value


def validity(client):
    _, data = client.response('UIDVALIDITY')
    if not isinstance(data, list) or len(data) != 1:
        raise RuntimeError('Missing synthetic UIDVALIDITY')
    return number(data[0])


def append_uid(client, expected_validity):
    _, data = client.response('APPENDUID')
    if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], bytes):
        raise RuntimeError('Missing synthetic APPENDUID')
    parts = data[0].split()
    if len(parts) != 2 or number(parts[0]) != expected_validity:
        raise RuntimeError('Invalid or changed synthetic APPENDUID')
    return number(parts[1])


def attempt(client, journal, source_id, content, after_append=None):
    digest = hashlib.sha256(content).hexdigest()
    journal.register(source_id, digest, len(content))
    probe.ok(client.select('INBOX'), 'select synthetic inbox')
    expected_validity = validity(client)
    # Clear stale response code before sending a new command.
    client.response('APPENDUID')
    journal.start(source_id)
    try:
        probe.ok(client.append('INBOX', r'(\Seen \Flagged)', None, content), 'append synthetic snapshot')
        if after_append is not None:
            after_append()
        uid = append_uid(client, expected_validity)
        probe.ok(client.select('INBOX', readonly=True), 'reselect synthetic inbox')
        if validity(client) != expected_validity:
            raise RuntimeError('Synthetic UIDVALIDITY changed after append')
        target = expected_validity.decode('ascii') + ':' + uid.decode('ascii')
        journal.imported(source_id, target)
    except BaseException:
        if journal.state(source_id) == ('submitting', None):
            journal.mark_uncertain(source_id)
        raise
    downloaded = probe.body(client, uid)
    data = probe.ok(client.uid('FETCH', uid, '(FLAGS)'), 'fetch test flags')
    lines = [x for x in data if isinstance(x, bytes) and b'FLAGS' in x]
    if len(lines) != 1 or not {b'\\Seen', b'\\Flagged'}.issubset(set(imaplib.ParseFlags(lines[0]))):
        raise RuntimeError('Synthetic imported flags mismatch')
    journal.verify(source_id, target, hashlib.sha256(downloaded).hexdigest(), len(downloaded))
    return target


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise SystemExit('Synthetic-only confirmation required')
    # Exact fixed test bytes, not user-supplied data. Exclusive CREATE forbids accidental resume.
    source = Path('/journal/synthetic.eml')
    with source.open('xb') as f:
        f.write(RAW)
    journal_file = Path('/journal/synthetic-import.sqlite')
    if journal_file.exists():
        raise RuntimeError('Synthetic journal already exists; refusing restart')
    journal = Journal(journal_file)
    client = None
    try:
        client = probe.connect('user01@james.local', time.monotonic() + 120)
        content = source.read_bytes()
        if content != RAW:
            raise RuntimeError('Synthetic source file changed')
        target = attempt(client, journal, SOURCE_ID, content)
        if journal.state(SOURCE_ID) != ('verified', target):
            raise RuntimeError('Synthetic journal not verified')
        print('PASS: synthetic file imported by IMAP APPENDUID with journal and verified bytes/flags')
    finally:
        if client is not None:
            client.logout()
        journal.close()

if __name__ == '__main__': main()
