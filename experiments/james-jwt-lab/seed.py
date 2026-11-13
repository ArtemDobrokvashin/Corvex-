"""Append or flag a synthetic message in the disposable James lab only."""
import email.policy
from email.message import EmailMessage
import os
import time
import probe

if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
    raise SystemExit('Synthetic-only confirmation required')
mode = os.environ.get('JAMES_LAB_SEED_MODE', 'append')
if mode not in ('append', 'seen'):
    raise SystemExit('Unsupported synthetic seed mode')
client = probe.connect('user01@james.local', time.monotonic() + 120)
try:
    probe.ok(client.select('INBOX'), 'select synthetic inbox')
    if mode == 'append':
        message = EmailMessage(policy=email.policy.SMTP)
        message['From'] = 'user02@james.local'
        message['To'] = 'user01@james.local'
        message['Subject'] = 'Corvex James live synthetic message'
        message.set_content('Synthetic data for the isolated JMAP integration test.')
        message.add_attachment(b'corvex-live\x00\xff', maintype='application',
                               subtype='octet-stream', filename='synthetic.bin')
        probe.ok(client.append('INBOX', None, None, message.as_bytes()), 'append synthetic message')
    else:
        data = probe.ok(client.uid('SEARCH', None, 'SUBJECT', '"Corvex James live synthetic message"'),
                        'find synthetic message')
        ids = data[0].split() if data and data[0] else []
        if not ids:
            raise RuntimeError('Synthetic message not found')
        probe.ok(client.uid('STORE', ids[0], '+FLAGS.SILENT', r'(\Seen)'), 'flag synthetic message')
finally:
    client.logout()
print('PASS: synthetic IMAP ' + mode)
