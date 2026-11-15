#!/usr/bin/env python3
"""Compare explicitly mapped migration manifests offline; never touches mail servers."""
from __future__ import annotations
import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from uuid import UUID

HEX = re.compile(r'^[0-9a-f]{64}$')
FLAGS = frozenset(('seen', 'answered', 'flagged', 'draft', 'deleted'))
MAX_FILE = 128 * 1024 * 1024

class InvalidManifest(ValueError):
    pass


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise InvalidManifest('duplicate JSON key')
        result[key] = value
    return result


def read_manifest(path: Path, kind: str) -> list[dict]:
    # Limit input before parsing, including files that grow while read.
    with path.open('rb') as f:
        raw = f.read(MAX_FILE + 1)
    if len(raw) > MAX_FILE:
        raise InvalidManifest('manifest exceeds size limit')
    try:
        root = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as e:
        raise InvalidManifest('invalid UTF-8 JSON') from e
    if not isinstance(root, dict) or set(root) != {'version', 'kind', 'snapshot_id', 'records'}:
        raise InvalidManifest('invalid manifest structure')
    if type(root['version']) is not int or root['version'] != 1 or root['kind'] != kind:
        raise InvalidManifest('incorrect manifest version or kind')
    if not isinstance(root['snapshot_id'], str) or not root['snapshot_id'] or len(root['snapshot_id']) > 200:
        raise InvalidManifest('invalid snapshot identifier')
    if not isinstance(root['records'], list) or not root['records']:
        raise InvalidManifest('records must be a nonempty array; empty scopes need separate verification')
    result = []
    for r in root['records']:
        expected = {'source_id', 'mailbox', 'folder', 'sha256', 'size', 'flags'} | ({'target_id'} if kind == 'target' else set())
        if not isinstance(r, dict) or set(r) != expected:
            raise InvalidManifest('invalid record fields')
        try:
            if str(UUID(r['source_id'])) != r['source_id']:
                raise ValueError()
        except (ValueError, TypeError, AttributeError):
            raise InvalidManifest('invalid canonical source UUID') from None
        for field in ('mailbox', 'folder') + (('target_id',) if kind == 'target' else ()):
            if not isinstance(r[field], str) or not r[field] or len(r[field]) > 1024 or '\x00' in r[field]:
                raise InvalidManifest('invalid record identifier')
        if not isinstance(r['sha256'], str) or not HEX.fullmatch(r['sha256']):
            raise InvalidManifest('invalid sha256')
        if type(r['size']) is not int or r['size'] < 0:
            raise InvalidManifest('invalid size')
        flags = r['flags']
        if not isinstance(flags, list) or any(not isinstance(x, str) or x not in FLAGS for x in flags) or len(set(flags)) != len(flags):
            raise InvalidManifest('invalid flags')
        result.append({**r, 'flags': sorted(flags)})
    return result


def compare(source: list[dict], target: list[dict]) -> dict:
    source_counts = Counter(r['source_id'] for r in source)
    target_counts = Counter(r['source_id'] for r in target)
    # Target identifiers are unique within a James account/mailbox namespace.
    native_counts = Counter((r['mailbox'], r['target_id']) for r in target)
    duplicate_source = sum(n - 1 for n in source_counts.values() if n > 1)
    duplicate_mapping = sum(n - 1 for n in target_counts.values() if n > 1)
    duplicate_target = sum(n - 1 for n in native_counts.values() if n > 1)
    src = {r['source_id']: r for r in source}
    dst = {r['source_id']: r for r in target}
    common = src.keys() & dst.keys()
    mismatches = Counter()
    for key in common:
        for field in ('mailbox', 'folder', 'sha256', 'size', 'flags'):
            if src[key][field] != dst[key][field]:
                mismatches[field] += 1
    return {'source_records': len(source), 'target_records': len(target),
            'missing': len(src.keys() - dst.keys()), 'unexpected': len(dst.keys() - src.keys()),
            'duplicate_source': duplicate_source, 'duplicate_mapping': duplicate_mapping,
            'duplicate_target_id': duplicate_target,
            'mismatches': {field: mismatches[field] for field in ('mailbox', 'folder', 'sha256', 'size', 'flags')}}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('target', type=Path)
    args = parser.parse_args(argv)
    try:
        source, target = read_manifest(args.source, 'source'), read_manifest(args.target, 'target')
        result = compare(source, target)
    except (OSError, InvalidManifest) as e:
        print(f'ERROR: {e}', file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    print('WARNING: manifest equality is not proof of snapshot consistency, completeness, delivery or cutover readiness.', file=sys.stderr)
    return int(any(result[k] for k in ('missing', 'unexpected', 'duplicate_source', 'duplicate_mapping', 'duplicate_target_id')) or any(result['mismatches'].values()))

if __name__ == '__main__':
    sys.exit(main())
