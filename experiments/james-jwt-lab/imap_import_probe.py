"""Append the exact JMAP fixture via IMAP in the disposable synthetic lab only."""
import hashlib
import imaplib
import os
import time
import probe
from jmap_import_probe import RAW


def uid_all(client):
    data = probe.ok(client.uid('SEARCH', None, 'ALL'), 'search synthetic inbox')
    return set(data[0].split()) if data and data[0] else set()


def verify(client, before):
    new_ids = uid_all(client) - before
    if len(new_ids) != 1:
        raise RuntimeError('Cannot uniquely identify synthetic IMAP append')
    uid = next(iter(new_ids))
    body = probe.body(client, uid)
    if body != RAW:
        raise RuntimeError('Synthetic IMAP fixture bytes differ: source_sha256=' + hashlib.sha256(RAW).hexdigest()
                           + ' target_sha256=' + hashlib.sha256(body).hexdigest())
    data = probe.ok(client.uid('FETCH', uid, '(FLAGS)'), 'fetch synthetic flags')
    responses = [item for item in data if isinstance(item, bytes) and b'FLAGS' in item]
    if len(responses) != 1:
        raise RuntimeError('Invalid synthetic IMAP flag response')
    flags = set(imaplib.ParseFlags(responses[0]))
    if not {b'\\Seen', b'\\Flagged'}.issubset(flags):
        raise RuntimeError('Synthetic IMAP flags differ')
    print('PASS: same synthetic MIME fixture via IMAP APPEND exact bytes and flags; sha256=' + hashlib.sha256(RAW).hexdigest())


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise SystemExit('Synthetic-only confirmation required')
    client = probe.connect('user01@james.local', time.monotonic() + 120)
    try:
        probe.ok(client.select('INBOX'), 'select synthetic inbox')
        before = uid_all(client)
        probe.ok(client.append('INBOX', r'(\Seen \Flagged)', None, RAW), 'append synthetic MIME fixture')
        verify(client, before)
    finally:
        client.logout()

if __name__ == '__main__': main()
