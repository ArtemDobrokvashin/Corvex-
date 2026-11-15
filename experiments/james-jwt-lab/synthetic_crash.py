"""Only for the disposable lab: stop process after accepted APPEND, check recovery."""
import json
import os
import time
from pathlib import Path
import probe
import synthetic_import as imp
from import_journal import Journal

SOURCE_ID = '706aef07-3301-44df-9d83-0700c22dbdb8'
ROOT = Path('/journal')


def uids(client):
    result = probe.ok(client.uid('SEARCH', None, 'ALL'), 'search synthetic inbox')
    return set(result[0].split()) if result and result[0] else set()


def seed(client, journal):
    probe.ok(client.select('INBOX'), 'select test inbox')
    before = sorted(v.decode('ascii') for v in uids(client))
    with (ROOT / 'crash-before.json').open('x', encoding='utf-8') as f:
        json.dump(before, f)
    # Simulates abrupt process exit AFTER server acknowledged APPEND but BEFORE
    # APPENDUID is recorded; no finally blocks and no automatic repeat.
    imp.attempt(client, journal, SOURCE_ID, imp.RAW, after_append=lambda: os._exit(73))
    raise RuntimeError('Expected injected process exit did not happen')


def recover(client, journal):
    if journal.recover_after_exclusive_restart() != 1 or journal.state(SOURCE_ID) != ('uncertain', None):
        raise RuntimeError('Unknown import was not blocked')
    before = set(json.loads((ROOT / 'crash-before.json').read_text(encoding='utf-8')))
    probe.ok(client.select('INBOX', readonly=True), 'select test inbox')
    after = {v.decode('ascii') for v in uids(client)}
    new = after - before
    if len(new) != 1 or before - after:
        raise RuntimeError('Synthetic crash result not uniquely observable')
    if probe.body(client, next(iter(new)).encode('ascii')) != imp.RAW:
        raise RuntimeError('Synthetic crash message bytes changed')
    try:
        imp.attempt(client, journal, SOURCE_ID, imp.RAW)
    except imp.JournalError:
        pass
    else:
        raise RuntimeError('Uncertain synthetic import allowed a retry')
    # Re-select to ensure search still targets the same inbox after denied attempt.
    probe.ok(client.select('INBOX', readonly=True), 'reselect test inbox')
    if {v.decode('ascii') for v in uids(client)} != after:
        raise RuntimeError('Unexpected synthetic duplicate after blocked retry')
    print('PASS: synthetic crash after APPEND leaves uncertain state and no automatic duplicate')


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise SystemExit('Synthetic-only confirmation required')
    mode = os.environ.get('JAMES_LAB_CRASH_MODE')
    if mode not in ('seed', 'recover'):
        raise SystemExit('Synthetic crash mode required')
    path = ROOT / 'crash-import.sqlite'
    if (mode == 'seed' and path.exists()) or (mode == 'recover' and not path.is_file()):
        raise RuntimeError('Unexpected synthetic crash journal state')
    journal = Journal(path)
    client = None
    try:
        client = probe.connect('user01@james.local', time.monotonic() + 120)
        if mode == 'seed': seed(client, journal)
        else: recover(client, journal)
    finally:
        if client is not None: client.logout()
        journal.close()

if __name__ == '__main__': main()
