import http.client
import importlib.util
from pathlib import Path
import threading
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('tls_proxy', Path(__file__).with_name('tls_proxy.py'))
proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(proxy)


class FakeResponse:
    status = 200
    def read(self, limit):
        return b'{"ok":true}'
    def getheader(self, name, default):
        return 'application/json' if name == 'Content-Type' else default


class FakeConnection:
    calls = []
    def __init__(self, host, port, timeout):
        self.host, self.port = host, port
    def request(self, method, path, body, headers):
        self.calls.append((self.host, self.port, method, path, body, headers))
    def getresponse(self):
        return FakeResponse()
    def close(self):
        pass


class ProxyTest(unittest.TestCase):
    def setUp(self):
        FakeConnection.calls.clear()
        self.server = proxy.ThreadingHTTPServer(('127.0.0.1', 0), proxy.Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.connection_patch = patch.object(proxy, 'HTTPConnection', FakeConnection)
        self.connection_patch.start()

    def tearDown(self):
        self.connection_patch.stop()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, method, path, body=None, headers=None):
        # Use an actual socket without the globally patched HTTPConnection.
        import urllib.request
        request = urllib.request.Request('http://127.0.0.1:%d%s' % (self.server.server_port, path),
                                         data=body, headers=headers or {}, method=method)
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def test_forward_allowed_path_and_authorization(self):
        status, data = self.request('GET', '/jmap/session', headers={'Authorization': 'Bearer synthetic'})
        self.assertEqual((200, b'{"ok":true}'), (status, data))
        host, port, method, path, _, headers = FakeConnection.calls[0]
        self.assertEqual(('james', 80, 'GET', '/jmap/session'), (host, port, method, path))
        self.assertEqual('Bearer synthetic', headers['Authorization'])

    def test_forward_post_body(self):
        status, _ = self.request('POST', '/jmap', b'{}', {'Content-Type': 'application/json'})
        self.assertEqual(200, status)
        self.assertEqual(('POST', '/jmap', b'{}'), FakeConnection.calls[0][2:5])

    def test_reject_other_path_before_forwarding(self):
        status, _ = self.request('GET', '/admin')
        self.assertEqual(404, status)
        self.assertEqual([], FakeConnection.calls)


if __name__ == '__main__':
    unittest.main()
