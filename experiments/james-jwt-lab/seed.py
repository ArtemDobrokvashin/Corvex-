"""Create exactly one synthetic test mail in the disposable James lab."""
import email.policy
from email.message import EmailMessage
import os
import time
import probe

if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
    raise SystemExit('Synthetic-only confirmation required')
message = EmailMessage(policy=email.policy.SMTP)
message['From'] = 'user02@james.local'
message['To'] = 'user01@james.local'
message['Subject'] = 'Corvex James live synthetic message'
message.set_content('Synthetic data for the isolated JMAP integration test.')
message.add_attachment(b'corvex-live\x00\xff', maintype='application',
                       subtype='octet-stream', filename='synthetic.bin')
client = probe.connect('user01@james.local', time.monotonic() + 120)
try:
    probe.ok(client.select('INBOX'), 'select synthetic inbox')
    probe.ok(client.append('INBOX', None, None, message.as_bytes()), 'append synthetic message')
finally:
    client.logout()
print('PASS: one synthetic IMAP message added')
