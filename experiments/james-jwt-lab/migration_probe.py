"""Synthetic-only IMAP byte/flags/duplicate round trip; not a migration tool."""
import hashlib
import imaplib
import os
import secrets
import time
import probe


def check(client, marker, raw):
    ids = probe.uid_search(client, marker)
    if len(ids) != 2 or len(set(ids)) != 2:
        raise RuntimeError('Synthetic duplicate copies missing or unexpectedly duplicated')
    flags = []
    for uid in ids:
        if probe.body(client, uid) != raw:
            raise RuntimeError('Synthetic bytes changed')
        data = probe.ok(client.uid('FETCH', uid, '(FLAGS)'), 'fetch flags')
        responses = [item for item in data if isinstance(item, bytes) and b'FLAGS' in item]
        if len(responses) != 1:
            raise RuntimeError('Invalid flag response')
        flags.append(set(imaplib.ParseFlags(responses[0])))
    if sorted(b'\\Seen' in item for item in flags) != [False, True]:
        raise RuntimeError('Synthetic per-copy Seen flags differ from expected')
    if not all(b'\\Flagged' in item for item in flags):
        raise RuntimeError('Synthetic Flagged flag missing')


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise SystemExit('Synthetic-only confirmation required')
    marker = secrets.token_hex(16)
    raw = probe.restart_message(marker)
    client = probe.connect('user01@james.local', time.monotonic() + 120)
    try:
        probe.ok(client.select('INBOX'), 'select synthetic inbox')
        if probe.uid_search(client, marker):
            raise RuntimeError('Unexpected marker collision')
        probe.ok(client.append('INBOX', r'(\Flagged)', None, raw), 'append first synthetic copy')
        probe.ok(client.append('INBOX', r'(\Seen \Flagged)', None, raw), 'append second synthetic copy')
        check(client, marker, raw)
    finally:
        client.logout()
    print('PASS: synthetic two-copy IMAP byte and flags round-trip; sha256=' + hashlib.sha256(raw).hexdigest())

if __name__ == '__main__':
    main()
