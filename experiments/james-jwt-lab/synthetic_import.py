"""Synthetic-only vertical slice: snapshot bytes -> journal -> IMAP -> verification.

NEVER use for real mail. UID discovery assumes an exclusively used lab inbox.
"""
import hashlib
import imaplib
import os
import time
from pathlib import Path
import probe
from import_journal import Journal
from jmap_import_probe import RAW

SOURCE_ID = 'f61b2fbc-7419-4eb0-8992-35841d0c77af'


def uid_set(client):
    data = probe.ok(client.uid('SEARCH', None, 'ALL'), 'search test inbox')
    return set(data[0].split()) if data and data[0] else set()


def attempt(client, journal, source_id, content):
    digest = hashlib.sha256(content).hexdigest()
    journal.register(source_id, digest, len(content))
    before = uid_set(client)
    # Durable intent BEFORE append. No retries on any ambiguous result.
    journal.start(source_id)
    try:
        probe.ok(client.append('INBOX', r'(\Seen \Flagged)', None, content), 'append synthetic snapshot')
        new_ids = uid_set(client) - before
        if len(new_ids) != 1:
            raise RuntimeError('Synthetic UID ambiguous')
        uid = next(iter(new_ids))
        if not uid.isdigit():
            raise RuntimeError('Invalid synthetic UID')
        journal.imported(source_id, uid.decode('ascii'))
    except BaseException:
        # If the imported transition already committed, leave that state intact.
        if journal.state(source_id) == ('submitting', None):
            journal.mark_uncertain(source_id)
        raise
    downloaded = probe.body(client, uid)
    data = probe.ok(client.uid('FETCH', uid, '(FLAGS)'), 'fetch test flags')
    lines = [x for x in data if isinstance(x, bytes) and b'FLAGS' in x]
    if len(lines) != 1 or not {b'\\Seen', b'\\Flagged'}.issubset(set(imaplib.ParseFlags(lines[0]))):
        raise RuntimeError('Synthetic imported flags mismatch')
    journal.verify(source_id, uid.decode('ascii'), hashlib.sha256(downloaded).hexdigest(), len(downloaded))
    return uid


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
        probe.ok(client.select('INBOX'), 'select synthetic inbox')
        content = source.read_bytes()
        if content != RAW:
            raise RuntimeError('Synthetic source file changed')
        uid = attempt(client, journal, SOURCE_ID, content)
        if journal.state(SOURCE_ID) != ('verified', uid.decode('ascii')):
            raise RuntimeError('Synthetic journal not verified')
        print('PASS: synthetic file imported by IMAP with journal and verified bytes/flags')
    finally:
        if client is not None:
            client.logout()
        journal.close()

if __name__ == '__main__': main()
