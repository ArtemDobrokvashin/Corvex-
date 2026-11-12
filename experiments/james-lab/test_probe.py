import importlib.util
import email
import email.policy
import os
import unittest
from unittest.mock import patch
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
    def test_requires_explicit_confirmation_before_network(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(probe, 'connect') as connect:
            with self.assertRaises(RuntimeError): probe.main()
            connect.assert_not_called()

    def run_probe(self, primary, other):
        with patch.dict(os.environ, {'JAMES_LAB_CONFIRM': 'synthetic-only'}, clear=True), \
                patch.object(probe, 'connect', side_effect=[primary, other]):
            probe.main()

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
