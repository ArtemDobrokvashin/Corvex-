import os
import unittest
from unittest.mock import patch
import jmap_import_probe as p

class ImportProbeTests(unittest.TestCase):
    def test_requires_synthetic_confirmation(self):
        with patch.dict(os.environ, {}, clear=True), self.assertRaises(SystemExit):
            p.main()

    def test_import_success_and_exact_bytes(self):
        responses = [
            {'primaryAccounts': {p.MAIL: 'test-account'}},
            {'methodResponses':[['Mailbox/get', {'accountId':'test-account','list':[{'id':'inbox','role':'inbox'}]}, 'probe']]},
            {'accountId':'test-account','blobId':'upload-id','size':len(p.RAW)},
            {'methodResponses':[['Email/import', {'accountId':'test-account','created':{'fixture':{'id':'email-id','blobId':'message-blob'}}}, 'probe']]},
            {'methodResponses':[['Email/get', {'accountId':'test-account','notFound':[],'list':[{'id':'email-id','keywords':{'$seen':True,'$flagged':True},'mailboxIds':{'inbox':True}}]}, 'probe']]},
        ]
        with patch.object(p, 'json_request', side_effect=responses), patch.object(p, 'request', return_value=p.RAW):
            p.run('test-token')

    def test_changed_bytes_is_not_success(self):
        responses = [{'primaryAccounts':{p.MAIL:'test-account'}},
                     {'methodResponses':[['Mailbox/get', {'accountId':'test-account','list':[{'id':'inbox','role':'inbox'}]}, 'probe']]},
                     {'accountId':'test-account','blobId':'upload-id','size':len(p.RAW)},
                     {'methodResponses':[['Email/import', {'accountId':'test-account','created':{'fixture':{'id':'email-id','blobId':'message-blob'}}}, 'probe']]}]
        with patch.object(p, 'json_request', side_effect=responses), patch.object(p, 'request', return_value=b'changed'):
            with self.assertRaisesRegex(RuntimeError, 'bytes differ'): p.run('test-token')

    def test_no_retry_after_failed_import(self):
        responses = [{'primaryAccounts':{p.MAIL:'test-account'}},
                     {'methodResponses':[['Mailbox/get', {'accountId':'test-account','list':[{'id':'inbox','role':'inbox'}]}, 'probe']]},
                     {'accountId':'test-account','blobId':'upload-id','size':len(p.RAW)},
                     {'methodResponses':[['Email/import', {'accountId':'test-account','notCreated':{'fixture':{'type':'serverFail'}}}, 'probe']]}]
        with patch.object(p, 'json_request', side_effect=responses) as request:
            with self.assertRaisesRegex(RuntimeError, 'rejected'): p.run('test-token')
            self.assertEqual(request.call_count, 4)

    def test_invalid_method_response_rejected(self):
        with patch.object(p, 'json_request', return_value={'methodResponses':[['error', {}, 'probe']]}):
            with self.assertRaises(RuntimeError): p.call('token', 'Mailbox/get', {})

if __name__ == '__main__': unittest.main()
