"""Synthetic-only IMAP byte/flags/duplicate round trip; not a migration tool."""
import hashlib
import imaplib
import json
from pathlib import Path
import tempfile
import compare_manifests
import source_manifest
import os
import re
import time
import probe


def check(client, marker, raw):
    ids = probe.uid_search(client, marker)
    if len(ids) != 2 or len(set(ids)) != 2:
        raise RuntimeError('Synthetic duplicate copies missing or unexpectedly duplicated')
    flags = []
    copies = []
    for uid in ids:
        body = probe.body(client, uid)
        if body != raw:
            raise RuntimeError('Synthetic bytes changed')
        data = probe.ok(client.uid('FETCH', uid, '(FLAGS)'), 'fetch flags')
        responses = [item for item in data if isinstance(item, bytes) and b'FLAGS' in item]
        if len(responses) != 1:
            raise RuntimeError('Invalid flag response')
        copy_flags = set(imaplib.ParseFlags(responses[0]))
        flags.append(copy_flags)
        copies.append((uid, body, copy_flags))
    if sorted(b'\\Seen' in item for item in flags) != [False, True]:
        raise RuntimeError('Synthetic per-copy Seen flags differ from expected')
    if not all(b'\\Flagged' in item for item in flags):
        raise RuntimeError('Synthetic Flagged flag missing')
    return copies



def reconcile_fixture(raw, copies):
    # The source->target mapping is known by construction for this synthetic
    # fixture only. It is NOT a discovery or safe production mapping algorithm.
    ids = {False: 'f61b2fbc-7419-4eb0-8992-35841d0c77af',
           True: 'e9769711-24f7-4d49-a938-134fe1f5f562'}
    rows = []
    target = []
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp) / 'snapshot'
        root.mkdir()
        for seen, source_id in ids.items():
            (root / (source_id + '.eml')).write_bytes(raw)
            flags = ['flagged'] + (['seen'] if seen else [])
            rows.append({'source_id': source_id, 'mailbox': 'user01@james.local',
                         'folder': 'INBOX', 'path': source_id + '.eml', 'flags': flags})
        metadata = Path(temp) / 'metadata.json'
        metadata.write_text(json.dumps({'version': 1, 'snapshot_id': 'synthetic', 'records': rows}), encoding='utf-8')
        source = source_manifest.build(metadata, root)['records']
        for uid, body, flags in copies:
            seen = b'\\Seen' in flags
            known = {b'\\Seen': 'seen', b'\\Flagged': 'flagged', b'\\Answered': 'answered',
                     b'\\Draft': 'draft', b'\\Deleted': 'deleted'}
            if flags - set(known) - {b'\\Recent'}:
                raise RuntimeError('Unexpected synthetic keyword')
            target.append({'source_id': ids[seen], 'target_id': uid.decode('ascii'),
                           'mailbox': 'user01@james.local', 'folder': 'INBOX',
                           'sha256': hashlib.sha256(body).hexdigest(), 'size': len(body),
                           'flags': sorted(known[f] for f in flags if f in known)})
        source = [{**item, 'flags': sorted(item['flags'])} for item in source]
        result = compare_manifests.compare(source, target)
        if any(result[k] for k in ('missing', 'unexpected', 'duplicate_source', 'duplicate_mapping', 'duplicate_target_id')) or any(result['mismatches'].values()):
            raise RuntimeError('Synthetic snapshot reconciliation failed')
    print('PASS: synthetic source snapshot and IMAP copies reconciled')


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise SystemExit('Synthetic-only confirmation required')
    marker = os.environ.get('JAMES_LAB_MIGRATION_MARKER', '')
    mode = os.environ.get('JAMES_LAB_MIGRATION_MODE', '')
    if not re.fullmatch(r'[0-9a-f]{32}', marker) or mode not in ('seed', 'verify'):
        raise SystemExit('Synthetic marker and seed/verify mode required')
    raw = probe.restart_message(marker)
    client = probe.connect('user01@james.local', time.monotonic() + 120)
    try:
        probe.ok(client.select('INBOX', readonly=(mode == 'verify')), 'select synthetic inbox')
        if mode == 'seed':
            if probe.uid_search(client, marker):
                raise RuntimeError('Unexpected marker collision')
            probe.ok(client.append('INBOX', r'(\Flagged)', None, raw), 'append first synthetic copy')
            probe.ok(client.append('INBOX', r'(\Seen \Flagged)', None, raw), 'append second synthetic copy')
        copies = check(client, marker, raw)
        if mode == 'verify':
            reconcile_fixture(raw, copies)
    finally:
        client.logout()
    print('PASS: synthetic two-copy IMAP ' + mode + ' bytes and flags; sha256=' + hashlib.sha256(raw).hexdigest())

if __name__ == '__main__':
    main()
