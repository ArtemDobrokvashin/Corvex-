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
    def __init__(self, other=False, corrupt=False, leak=False):
        self.other, self.corrupt, self.leak = other, corrupt, leak
        self.raw = None
        self.closed = False

    def select(self, *args, **kwargs): return 'OK', [b'1']
    def create(self, *args): return 'OK', [b'created']
    def append(self, folder, flags, date, raw):
        self.raw = raw
        return 'OK', [b'appended']
    def uid(self, command, *args):
        if command == 'SEARCH':
            return 'OK', [b'1' if not self.other or self.leak else b'']
        if command == 'FETCH':
            return 'OK', [(b'1 (BODY[]', b'bad' if self.corrupt else self.raw), b')']
        if command == 'COPY': return 'OK', [b'copied']
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

    def test_other_inbox_leak_fails(self):
        first, second = FakeImap(), FakeImap(other=True, leak=True)
        with self.assertRaisesRegex(RuntimeError, 'other inbox'):
            self.run_probe(first, second)
        self.assertTrue(first.closed and second.closed)


if __name__ == '__main__':
    unittest.main()
