import importlib.util
import io
import email
import email.policy
import os
import unittest
from unittest.mock import patch, Mock, call
from contextlib import redirect_stdout
from pathlib import Path

spec = importlib.util.spec_from_file_location('james_probe', Path(__file__).with_name('probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class FakeImap:
    capabilities = ('IMAP4REV1', 'MOVE')

    def __init__(self, other=False, corrupt=False, leak=False, lose_flag=False, retain_source=False):
        self.other, self.corrupt, self.leak = other, corrupt, leak
        self.lose_flag, self.retain_source = lose_flag, retain_source
        self.raw = None
        self.closed = False
        self.folder = None
        self.mailboxes = {'INBOX': []}
        self.flagged = False

    def select(self, folder, **kwargs):
        self.folder = folder
        return 'OK', [b'1']
    def capability(self): return 'OK', [' '.join(self.capabilities).encode()]
    def create(self, folder):
        self.mailboxes[folder] = []
        return 'OK', [b'created']
    def append(self, folder, flags, date, raw):
        self.raw = raw
        self.mailboxes[folder].append(b'1')
        return 'OK', [b'appended']
    def uid(self, command, *args):
        if command == 'SEARCH':
            return 'OK', [b'1' if (self.other and self.leak) or self.mailboxes[self.folder] else b'']
        if command == 'FETCH':
            if args[1] == '(FLAGS)':
                flagged = self.flagged and not (self.lose_flag and self.folder.endswith('-moved'))
                return 'OK', [b'1 (FLAGS (\\Flagged))' if flagged else b'1 (FLAGS ())']
            return 'OK', [(b'1 (BODY[]', b'bad' if self.corrupt else self.raw), b')']
        if command == 'COPY':
            self.mailboxes[args[1]].append(b'1')
            return 'OK', [b'copied']
        if command == 'STORE':
            self.flagged = True
            return 'OK', [b'stored']
        if command == 'MOVE':
            self.mailboxes[args[1]].append(b'1')
            if not self.retain_source:
                self.mailboxes[self.folder].clear()
            return 'OK', [b'moved']
        raise AssertionError(command)
    def logout(self):
        self.closed = True
        return 'BYE', [b'bye']


class ProbeTests(unittest.TestCase):
    def test_suite_collects_independent_failure_and_skip(self):
        output = io.StringIO()
        with patch.object(probe, 'basic_probe', side_effect=RuntimeError('synthetic failure')), \
                patch.object(probe, 'smtp_probe') as smtp, \
                patch.object(probe, 'restart_probe') as restart, redirect_stdout(output):
            result = probe.run_suite('a' * 32)
        self.assertEqual(1, result)
        smtp.assert_called_once()
        restart.assert_called_once_with('verify', 'a' * 32)
        self.assertIn('TOTAL: PASS=2 FAIL=1 SKIP=6', output.getvalue())
        self.assertIn('synthetic failure', output.getvalue())

    def test_suite_missing_marker_is_skipped_not_passed(self):
        output = io.StringIO()
        with patch.object(probe, 'basic_probe'), patch.object(probe, 'smtp_probe'), \
                patch.object(probe, 'restart_probe') as restart, redirect_stdout(output):
            result = probe.run_suite('')
        self.assertEqual(0, result)
        restart.assert_not_called()
        self.assertIn('TOTAL: PASS=2 FAIL=0 SKIP=7', output.getvalue())

    def test_smtp_sends_only_to_demo_recipient_and_checks_attachment(self):
        smtp = Mock()
        smtp.send_message.return_value = {}
        context = Mock()
        context.__enter__ = Mock(return_value=smtp)
        context.__exit__ = Mock(return_value=False)
        client = Mock()
        client.select.return_value = ('OK', [b'1'])
        received = email.message.EmailMessage(policy=email.policy.SMTP)
        received.set_content('Synthetic')
        received.add_attachment(b'smtp-lab-attachment\x00\xff', maintype='application',
                                subtype='octet-stream', filename='synthetic.bin')
        with patch.dict(probe.os.environ, {}, clear=True), \
                patch.object(probe.smtplib, 'SMTP', return_value=context) as transport, \
                patch.object(probe, 'connect', return_value=client), \
                patch.object(probe, 'uid_search', return_value=[b'1']), \
                patch.object(probe, 'body', return_value=received.as_bytes()):
            probe.smtp_probe()
        transport.assert_called_once_with('james', 25, timeout=10)
        self.assertEqual([probe.OTHER], smtp.send_message.call_args.kwargs['to_addrs'])
        client.select.assert_called_once_with('INBOX', readonly=True)
        client.logout.assert_called_once()

    def test_submission_uses_tls_before_auth_and_send(self):
        smtp = Mock()
        smtp.send_message.return_value = {}
        ctx = Mock()
        ctx.__enter__ = Mock(return_value=smtp)
        ctx.__exit__ = Mock(return_value=False)
        client = Mock()
        client.select.return_value = ('OK', [b'1'])
        received = email.message.EmailMessage(policy=email.policy.SMTP)
        received.set_content('Synthetic')
        received.add_attachment(b'smtp-lab-attachment\x00\xff', maintype='application',
                                subtype='octet-stream', filename='synthetic.bin')
        with patch.dict(probe.os.environ, {'JAMES_LAB_SMTP_MODE': 'submission'}, clear=True), \
                patch.object(probe.smtplib, 'SMTP', return_value=ctx) as transport, \
                patch.object(probe, 'connect', return_value=client), \
                patch.object(probe, 'uid_search', return_value=[b'1']), \
                patch.object(probe, 'body', return_value=received.as_bytes()):
            probe.smtp_probe()
        transport.assert_called_once_with('james', 587, timeout=10)
        names = [entry[0] for entry in smtp.method_calls]
        self.assertLess(names.index('starttls'), names.index('login'))
        self.assertLess(names.index('login'), names.index('send_message'))
        smtp.login.assert_called_once_with(probe.USER, probe.PASSWORD)

    def test_submission_tls_failure_never_sends_or_logs_in(self):
        smtp = Mock()
        smtp.starttls.side_effect = OSError('TLS failure')
        ctx = Mock()
        ctx.__enter__ = Mock(return_value=smtp)
        ctx.__exit__ = Mock(return_value=False)
        with patch.dict(probe.os.environ, {'JAMES_LAB_SMTP_MODE': 'submission'}, clear=True), \
                patch.object(probe.smtplib, 'SMTP', return_value=ctx), \
                patch.object(probe, 'connect') as connect:
            with self.assertRaises(OSError): probe.smtp_probe()
        smtp.login.assert_not_called()
        smtp.send_message.assert_not_called()
        connect.assert_not_called()

    def test_starttls_precedes_login(self):
        client = Mock()
        client.starttls.return_value = ('OK', [b'tls'])
        client.login.return_value = ('OK', [b'authenticated'])
        with patch.object(probe.imaplib, 'IMAP4', return_value=client):
            self.assertIs(client, probe.connect(probe.USER, probe.time.monotonic() + 1))
        self.assertEqual('starttls', client.method_calls[0][0])
        self.assertEqual(call.login(probe.USER, probe.PASSWORD), client.method_calls[1])
        context = client.starttls.call_args.kwargs['ssl_context']
        self.assertFalse(context.check_hostname)  # DEMO ONLY; not production policy
        self.assertEqual(probe.ssl.CERT_NONE, context.verify_mode)

    def test_failed_starttls_never_falls_back_to_cleartext(self):
        client = Mock()
        client.starttls.side_effect = probe.imaplib.IMAP4.error('TLS unavailable')
        error_class = probe.imaplib.IMAP4.error
        with patch.object(probe.imaplib, 'IMAP4', return_value=client) as constructor:
            constructor.error = error_class
            with self.assertRaisesRegex(RuntimeError, 'did not become ready'):
                probe.connect(probe.USER, 0)
        client.login.assert_not_called()
        client.shutdown.assert_called_once()

    def test_requires_explicit_confirmation_before_network(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(probe, 'connect') as connect:
            with self.assertRaises(RuntimeError): probe.main()
            connect.assert_not_called()

    def run_probe(self, primary, other):
        with patch.dict(os.environ, {'JAMES_LAB_CONFIRM': 'synthetic-only'}, clear=True), \
                patch.object(probe, 'connect', side_effect=[primary, other]):
            probe.main()

    def test_diagnostic_distinguishes_search_miss_from_preserved_bytes(self):
        marker = 'a' * 32
        client = Mock()
        client.select.return_value = ('OK', [b'1'])
        client.uid.return_value = ('OK', [b'7'])
        output = io.StringIO()
        with patch.object(probe, 'connect', return_value=client), \
                patch.object(probe, 'uid_search', return_value=[]), \
                patch.object(probe, 'body', return_value=probe.restart_message(marker)), \
                redirect_stdout(output):
            probe.diagnose_restart(marker)
        self.assertIn('header_search_count=0 scan_marker_count=1 exact_bytes_count=1', output.getvalue())
        client.select.assert_called_once_with('INBOX', readonly=True)
        client.logout.assert_called_once()

    def test_diagnostic_rejects_large_or_inconsistent_inbox(self):
        client = Mock()
        client.select.return_value = ('OK', [b'101'])
        with patch.object(probe, 'connect', return_value=client):
            with self.assertRaisesRegex(RuntimeError, 'over 100'):
                probe.diagnose_restart('a' * 32)
        client.uid.assert_not_called()
        client.logout.assert_called_once()
        client.reset_mock()
        client.select.return_value = ('OK', [b'1'])
        client.uid.return_value = ('OK', [b''])
        with patch.object(probe, 'connect', return_value=client), patch.object(probe, 'uid_search', return_value=[]):
            with self.assertRaisesRegex(RuntimeError, 'diagnostic incomplete'):
                probe.diagnose_restart('a' * 32)
        client.logout.assert_called_once()

    def test_restart_seed_and_verify_same_bytes(self):
        client = FakeImap()
        marker = 'a' * 32
        with patch.object(probe, 'connect', return_value=client):
            probe.restart_probe('seed', marker)
            probe.restart_probe('verify', marker)
        self.assertEqual(probe.restart_message(marker), client.raw)
        parsed = email.message_from_bytes(client.raw, policy=email.policy.default)
        self.assertEqual(b'\x00\x01\x02\xff', next(parsed.iter_attachments()).get_payload(decode=True))

    def test_restart_detects_missing_corruption_and_duplicate(self):
        marker = 'a' * 32
        client = FakeImap()
        with patch.object(probe, 'connect', return_value=client):
            with self.assertRaisesRegex(RuntimeError, 'missing, duplicated or changed'):
                probe.restart_probe('verify', marker)
            probe.restart_probe('seed', marker)
            with self.assertRaisesRegex(RuntimeError, 'refusing duplicate'):
                probe.restart_probe('seed', marker)
            client.corrupt = True
            with self.assertRaisesRegex(RuntimeError, 'missing, duplicated or changed'):
                probe.restart_probe('verify', marker)

    def test_restart_rejects_invalid_mode_and_marker_before_connect(self):
        with patch.object(probe, 'connect') as connect:
            with self.assertRaises(ValueError): probe.restart_probe('seed', 'invalid')
            with self.assertRaises(ValueError): probe.restart_probe('bad', 'a' * 32)
            connect.assert_not_called()

    def test_success(self):
        first, second = FakeImap(), FakeImap(other=True)
        self.run_probe(first, second)
        self.assertTrue(first.closed and second.closed)
        message = email.message_from_bytes(first.raw, policy=email.policy.default)
        self.assertEqual(b'corvex-lab-attachment\x00\xff', next(message.iter_attachments()).get_payload(decode=True))

    def test_corrupt_body_fails(self):
        first = FakeImap(corrupt=True)
        with self.assertRaisesRegex(RuntimeError, 'round-trip mismatch'):
            self.run_probe(first, FakeImap(other=True))
        self.assertTrue(first.closed)

    def test_move_losing_flag_fails(self):
        first = FakeImap(lose_flag=True)
        with self.assertRaisesRegex(RuntimeError, 'MOVE lost flag'):
            self.run_probe(first, FakeImap(other=True))
        self.assertTrue(first.closed)

    def test_move_leaving_source_fails(self):
        first = FakeImap(retain_source=True)
        with self.assertRaisesRegex(RuntimeError, 'MOVE left copy'):
            self.run_probe(first, FakeImap(other=True))
        self.assertTrue(first.closed)

    def test_missing_move_capability_fails(self):
        first = FakeImap()
        first.capabilities = ('IMAP4REV1',)
        with self.assertRaisesRegex(RuntimeError, 'capability unavailable'):
            self.run_probe(first, FakeImap(other=True))
        self.assertTrue(first.closed)

    def test_other_inbox_leak_fails(self):
        first, second = FakeImap(), FakeImap(other=True, leak=True)
        with self.assertRaisesRegex(RuntimeError, 'other inbox'):
            self.run_probe(first, second)
        self.assertTrue(first.closed and second.closed)


if __name__ == '__main__':
    unittest.main()
