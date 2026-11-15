"""Disposable James demo protocol probe. Synthetic messages only; not production."""
import email.policy
from email.parser import BytesParser
from email.message import EmailMessage
import imaplib
import os
import re
import secrets
import smtplib
import sys
import ssl
import time

HOST = 'james'
USER = 'user01@james.local'
OTHER = 'user02@james.local'
PASSWORD = '1234'  # Public upstream demo account, safe only on the isolated network.


def ok(response, operation):
    status, data = response
    if status != 'OK':
        raise RuntimeError(f'{operation}: {status} {data!r}')
    return data


def connect(user, deadline):
    while True:
        client = None
        try:
            client = imaplib.IMAP4(HOST, 143, timeout=5)
            # Demo generates a self-signed certificate. No identity validation is
            # acceptable ONLY for this disposable, internal, synthetic-data lab.
            # Never reuse this context for production or actual credentials.
            ok(client.starttls(ssl_context=ssl._create_unverified_context()), 'STARTTLS')
            ok(client.login(user, PASSWORD), 'login')
            return client
        except (OSError, imaplib.IMAP4.error):
            if client is not None:
                try:
                    client.shutdown()
                except OSError:
                    pass
            if time.monotonic() >= deadline:
                raise RuntimeError('James IMAP/demo accounts did not become ready') from None
            time.sleep(1)


def uid_search(client, marker):
    data = ok(client.uid('SEARCH', None, 'HEADER', 'X-Corvex-Lab', marker), 'search')
    return data[0].split() if data and data[0] else []


def body(client, uid):
    data = ok(client.uid('FETCH', uid, '(BODY.PEEK[])'), 'fetch')
    literals = [part[1] for part in data if isinstance(part, tuple) and isinstance(part[1], bytes)]
    if len(literals) != 1:
        raise RuntimeError('Expected exactly one message body in FETCH')
    return literals[0]


def restart_message(marker):
    # Explicit fixed wire bytes permit comparison across separate probe containers.
    return (f'From: {USER}\r\nTo: {USER}\r\n'
            f'Subject: Corvex restart test\r\nX-Corvex-Lab: {marker}\r\n'
            'MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary="corvex-lab"\r\n'
            '\r\n--corvex-lab\r\nContent-Type: text/plain\r\n\r\n'
            'Synthetic restart test.\r\n--corvex-lab\r\n'
            'Content-Type: application/octet-stream\r\n'
            'Content-Disposition: attachment; filename="test.bin"\r\n'
            'Content-Transfer-Encoding: base64\r\n\r\n'
            'AAEC/w==\r\n--corvex-lab--\r\n').encode('ascii')


def restart_probe(mode, marker):
    if not re.fullmatch(r'[0-9a-f]{32}', marker or ''):
        raise ValueError('JAMES_LAB_MARKER must be exactly 32 lowercase hexadecimal characters')
    if mode not in ('seed', 'verify'):
        raise ValueError('Unknown restart probe mode')
    raw = restart_message(marker)
    client = connect(USER, time.monotonic() + 120)
    try:
        ok(client.select('INBOX', readonly=(mode == 'verify')), 'select inbox')
        matches = uid_search(client, marker)
        if mode == 'seed':
            if matches:
                raise RuntimeError('Marker already exists; refusing duplicate seed')
            ok(client.append('INBOX', None, None, raw), 'append restart message')
            matches = uid_search(client, marker)
        if len(matches) != 1 or body(client, matches[0]) != raw:
            raise RuntimeError('Restart message missing, duplicated or changed')
        print('PASS: restart ' + mode + ' marker=' + marker + ' bytes matched')
    finally:
        client.logout()


def diagnose_restart(marker):
    """Read-only diagnostic for a disposable synthetic lab, no message output."""
    if not re.fullmatch(r'[0-9a-f]{32}', marker or ''):
        raise ValueError('JAMES_LAB_MARKER must be exactly 32 lowercase hexadecimal characters')
    client = connect(USER, time.monotonic() + 120)
    try:
        selected = ok(client.select('INBOX', readonly=True), 'select inbox')
        if not selected or not selected[0] or not selected[0].isdigit():
            raise RuntimeError('Invalid INBOX message count')
        total = int(selected[0])
        if total > 100:
            raise RuntimeError('Refusing diagnostic for an INBOX with over 100 messages')
        matches = uid_search(client, marker)
        data = ok(client.uid('SEARCH', None, 'ALL'), 'search all')
        uids = data[0].split() if data and data[0] else []
        if len(uids) != total or len(set(uids)) != len(uids):
            raise RuntimeError('SEARCH ALL does not match selected INBOX count; diagnostic incomplete')
        exact = marked = 0
        for uid in uids:
            raw = body(client, uid)
            header = BytesParser(policy=email.policy.default).parsebytes(raw, headersonly=True)
            if header.get('X-Corvex-Lab') == marker:
                marked += 1
                if raw == restart_message(marker):
                    exact += 1
        print(f'DIAGNOSTIC: inbox_count={total} header_search_count={len(matches)} '
              f'scan_marker_count={marked} exact_bytes_count={exact}')
        print('No message contents displayed. This is a diagnostic, not a restart PASS.')
    finally:
        client.logout()


def smtp_probe():
    """Send synthetic MIME only to the fixed local demo account, then read via IMAP."""
    marker = secrets.token_hex(16)
    msg = EmailMessage(policy=email.policy.SMTP)
    msg['From'] = USER
    msg['To'] = OTHER
    msg['Subject'] = 'Corvex synthetic SMTP lab'
    msg['X-Corvex-Lab'] = marker
    msg.set_content('Synthetic SMTP test only.')
    msg.add_attachment(b'smtp-lab-attachment\x00\xff', maintype='application',
                       subtype='octet-stream', filename='synthetic.bin')
    mode = os.environ.get('JAMES_LAB_SMTP_MODE', 'inbound')
    if mode not in ('inbound', 'submission'):
        raise ValueError('Unknown laboratory SMTP mode')
    port = 587 if mode == 'submission' else 25
    with smtplib.SMTP(HOST, port, timeout=10) as smtp:
        if mode == 'submission':
            # Self-signed demo certificate: encryption without identity verification.
            # LAB ONLY; production needs verified certificates and hostnames.
            smtp.ehlo()
            smtp.starttls(context=ssl._create_unverified_context())
            smtp.ehlo()
            smtp.login(USER, PASSWORD)
        refused = smtp.send_message(msg, from_addr=USER, to_addrs=[OTHER])
        if refused:
            raise RuntimeError('SMTP refused the local demo recipient')
    client = connect(OTHER, time.monotonic() + 120)
    try:
        ok(client.select('INBOX', readonly=True), 'select SMTP recipient inbox')
        deadline = time.monotonic() + 45
        while True:
            ids = uid_search(client, marker)
            if ids:
                break
            if time.monotonic() >= deadline:
                raise RuntimeError('SMTP accepted but message not found by IMAP search within 45 seconds')
            time.sleep(1)
            ok(client.select('INBOX', readonly=True), 'refresh SMTP recipient inbox')
        if len(ids) != 1:
            raise RuntimeError('SMTP marker found multiple times')
        received = BytesParser(policy=email.policy.default).parsebytes(body(client, ids[0]))
        attachments = list(received.iter_attachments())
        if len(attachments) != 1 or attachments[0].get_payload(decode=True) != b'smtp-lab-attachment\x00\xff':
            raise RuntimeError('SMTP delivered attachment missing or changed')
    finally:
        client.logout()


def run_suite(marker):
    """Run independent lab scenarios; never stop or restart a container."""
    checks = [('IMAP basic (APPEND/COPY/flags/MOVE/isolation)', basic_probe),
              ('SMTP local delivery with attachment', smtp_probe)]
    if marker:
        checks.append(('Previously seeded message after restart', lambda: restart_probe('verify', marker)))
    results = []
    for name, check in checks:
        try:
            check()
        except Exception as exc:
            # Error messages may include server responses; synthetic lab only.
            results.append(('FAIL', name))
            print(f'FAIL: {name}: {type(exc).__name__}: {exc}', flush=True)
        else:
            results.append(('PASS', name))
            print(f'PASS: {name}', flush=True)
    if not marker:
        results.append(('SKIP', 'Previously seeded message after restart (marker missing)'))
    for name in ('POP3 (demo disabled)', 'concurrent operations', 'crash/power loss',
                 'backup/restore', 'Corvex integration', 'production TLS and AV'):
        results.append(('SKIP', name))
    print('\n=== LAB SUMMARY ===', flush=True)
    for status, name in results:
        print(f'{status}: {name}', flush=True)
    counts = {status: sum(r[0] == status for r in results) for status in ('PASS', 'FAIL', 'SKIP')}
    print(f"TOTAL: PASS={counts['PASS']} FAIL={counts['FAIL']} SKIP={counts['SKIP']}", flush=True)
    print('Synthetic isolated demo only; not production acceptance.', flush=True)
    return 1 if counts['FAIL'] else 0


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise RuntimeError('Set JAMES_LAB_CONFIRM=synthetic-only in the isolated lab')
    mode = os.environ.get('JAMES_LAB_MODE', 'basic')
    marker = os.environ.get('JAMES_LAB_MARKER', '')
    if mode == 'suite':
        return run_suite(marker)
    if mode == 'diagnose':
        diagnose_restart(marker)
        return 0
    if mode != 'basic':
        restart_probe(mode, marker)
        return 0
    basic_probe()
    return 0


def basic_probe():
    marker = secrets.token_hex(16)
    msg = EmailMessage(policy=email.policy.SMTP)
    msg['From'] = USER
    msg['To'] = USER
    msg['Subject'] = 'Corvex synthetic James lab'
    msg['X-Corvex-Lab'] = marker
    msg.set_content('Synthetic test only. No real email.')
    msg.add_attachment(b'corvex-lab-attachment\x00\xff', maintype='application',
                       subtype='octet-stream', filename='synthetic.bin')
    raw = msg.as_bytes()
    client = connect(USER, time.monotonic() + 120)
    other = None
    folder = 'corvex-lab-' + marker
    try:
        ok(client.select('INBOX'), 'select inbox')
        ok(client.append('INBOX', None, None, raw), 'append')
        ids = uid_search(client, marker)
        if len(ids) != 1 or body(client, ids[0]) != raw:
            raise RuntimeError('APPEND round-trip mismatch')
        ok(client.create(folder), 'create folder')
        ok(client.uid('COPY', ids[0], folder), 'copy')
        ok(client.select(folder), 'select copy')
        copies = uid_search(client, marker)
        if len(copies) != 1 or body(client, copies[0]) != raw:
            raise RuntimeError('COPY round-trip mismatch')
        ok(client.uid('STORE', copies[0], '+FLAGS.SILENT', r'(\Flagged)'), 'set flag')
        flag_data = ok(client.uid('FETCH', copies[0], '(FLAGS)'), 'fetch flags')
        flag_lines = [line for line in flag_data if isinstance(line, bytes)]
        if not any(b'\\Flagged' in imaplib.ParseFlags(line) for line in flag_lines):
            raise RuntimeError('Flag update not visible')
        if body(client, copies[0]) != raw:
            raise RuntimeError('Flag update changed MIME bytes')
        advertised = ok(client.capability(), 'capability')
        caps = b' '.join(line for line in advertised if isinstance(line, bytes)).upper().split()
        if b'MOVE' not in caps:
            raise RuntimeError('IMAP MOVE capability unavailable; MOVE not tested')
        destination = folder + '-moved'
        ok(client.create(destination), 'create MOVE destination')
        ok(client.uid('MOVE', copies[0], destination), 'move copy')
        if uid_search(client, marker):
            raise RuntimeError('MOVE left copy in source folder')
        ok(client.select(destination), 'select MOVE destination')
        moved = uid_search(client, marker)
        if len(moved) != 1 or body(client, moved[0]) != raw:
            raise RuntimeError('MOVE round-trip mismatch')
        moved_flags = ok(client.uid('FETCH', moved[0], '(FLAGS)'), 'fetch moved flags')
        if not any(b'\\Flagged' in imaplib.ParseFlags(line) for line in moved_flags if isinstance(line, bytes)):
            raise RuntimeError('MOVE lost flag')
        ok(client.select('INBOX', readonly=True), 'select original inbox')
        originals = uid_search(client, marker)
        if len(originals) != 1 or body(client, originals[0]) != raw:
            raise RuntimeError('MOVE of copy damaged original')
        other = connect(OTHER, time.monotonic() + 120)
        ok(other.select('INBOX', readonly=True), 'select other inbox')
        if uid_search(other, marker):
            raise RuntimeError('Synthetic message visible in other inbox')
        print('PASS: APPEND, MIME bytes, COPY, flags, MOVE and other-user inbox isolation')
        print('Basic IMAP scenario only; see suite summary for other checks.')
    finally:
        if other:
            other.logout()
        client.logout()
    # Leave synthetic data intact for diagnostics; the isolated lab is disposable.


if __name__ == '__main__':
    sys.exit(main())
