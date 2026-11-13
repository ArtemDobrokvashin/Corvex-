import contextlib
import importlib.util
import io
import unittest
from pathlib import Path
from unittest.mock import patch, Mock

spec = importlib.util.spec_from_file_location('jmap_probe', Path(__file__).with_name('jmap_probe.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class JmapProbeTests(unittest.TestCase):
    def test_mailbox_request_only_uses_fixed_endpoint_and_account(self):
        with patch.object(probe, 'request', return_value=(200, {'methodResponses': [['Mailbox/get', {'list': []}, 'c1']]})) as request:
            self.assertEqual('Mailbox/get', probe.mailbox_get(probe.USERS[0], 'account-a')[0])
        args = request.call_args.args
        self.assertEqual('/jmap', args[0])
        self.assertEqual('account-a', args[2]['methodCalls'][0][1]['accountId'])

    def test_run_reports_cross_user_access_as_failure(self):
        def fake_request(path, user=None, payload=None):
            if not user: return 401, None
            if path == '/jmap/session':
                return 200, {'primaryAccounts': {probe.MAIL: 'one' if user == probe.USERS[0] else 'two'}}
            return 200, {'methodResponses': [['Mailbox/get', {'list': []}, 'c1']]}
        output = io.StringIO()
        with patch.dict(probe.os.environ, {'JAMES_LAB_CONFIRM': 'synthetic-only'}), \
                patch.object(probe, 'request', side_effect=fake_request), \
                patch.object(probe, 'imap_to_jmap'), contextlib.redirect_stdout(output):
            self.assertEqual(1, probe.main())
        self.assertIn('FAIL: own mailbox and cross-user denial', output.getvalue())
        self.assertIn('FAIL=1', output.getvalue())

    def test_denied_cross_user_passes_and_other_checks_stay_skipped(self):
        def fake_request(path, user=None, payload=None):
            if not user: return 401, None
            if path == '/jmap/session':
                return 200, {'primaryAccounts': {probe.MAIL: 'one' if user == probe.USERS[0] else 'two'}}
            if user == probe.USERS[1]:
                return 200, {'methodResponses': [['error', {'type': 'accountNotFound'}, 'c1']]}
            return 200, {'methodResponses': [['Mailbox/get', {'list': []}, 'c1']]}
        output = io.StringIO()
        with patch.dict(probe.os.environ, {'JAMES_LAB_CONFIRM': 'synthetic-only'}), \
                patch.object(probe, 'request', side_effect=fake_request), \
                patch.object(probe, 'imap_to_jmap'), contextlib.redirect_stdout(output):
            self.assertEqual(0, probe.main())
        self.assertIn('TOTAL: PASS=5 FAIL=0 SKIP=6', output.getvalue())

    def test_fail_closed_without_confirmation(self):
        with patch.dict(probe.os.environ, {}, clear=True), patch.object(probe, 'request') as request:
            with self.assertRaises(RuntimeError): probe.main()
            request.assert_not_called()

    def test_session_failure_marks_cross_user_skip(self):
        def fake_request(path, user=None, payload=None):
            if not user: return 401, None
            return 503, None
        output = io.StringIO()
        with patch.dict(probe.os.environ, {'JAMES_LAB_CONFIRM': 'synthetic-only'}), \
                patch.object(probe, 'request', side_effect=fake_request), \
                patch.object(probe, 'imap_to_jmap'), contextlib.redirect_stdout(output):
            self.assertEqual(1, probe.main())
        self.assertIn('SKIP: own mailbox and cross-user denial', output.getvalue())

    def test_imap_to_jmap_checks_message_and_attachment_metadata(self):
        imap = Mock()
        client = imap.connect.return_value
        imap.ok.side_effect = lambda response, operation: response
        imap.secrets = None
        responses = [ {'ids': ['id-1']}, {'list': [{'id': 'id-1',
                       'subject': 'corvex-jmap-lab-' + 'a' * 32, 'hasAttachment': True}]} ]
        with patch.object(probe.importlib, 'import_module', return_value=imap), \
                patch.object(probe.secrets, 'token_hex', return_value='a' * 32), \
                patch.object(probe, 'jmap_method', side_effect=responses) as method:
            probe.imap_to_jmap('account-1')
        client.append.assert_called_once()
        client.logout.assert_called_once()
        self.assertEqual(['Email/query', 'Email/get'], [c.args[1] for c in method.call_args_list])

    def test_imap_to_jmap_rejects_missing_attachment(self):
        imap = Mock()
        imap.ok.side_effect = lambda response, operation: response
        responses = [{'ids': ['id-1']}, {'list': [{'id': 'id-1',
                      'subject': 'corvex-jmap-lab-' + 'a' * 32, 'hasAttachment': False}]}]
        with patch.object(probe.importlib, 'import_module', return_value=imap), \
                patch.object(probe.secrets, 'token_hex', return_value='a' * 32), \
                patch.object(probe, 'jmap_method', side_effect=responses):
            with self.assertRaisesRegex(RuntimeError, 'properties do not match'):
                probe.imap_to_jmap('account-1')

    def test_never_follows_redirect_with_credentials(self):
        self.assertIsNone(probe.NoRedirect().redirect_request(None, None, 302, 'redirect', {}, 'http://foreign/'))


if __name__ == '__main__':
    unittest.main()
