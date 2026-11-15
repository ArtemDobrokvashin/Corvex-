"""Loopback-published TLS fixture, not a general-purpose or production proxy.

TLS terminates here and traffic to James is HTTP on the isolated lab network.
Only two JMAP endpoints are available. No credentials or request data are logged.
"""
import http.client
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ssl

LIMIT = 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.proxy('GET', '/jmap/session')

    def do_POST(self):
        self.proxy('POST', '/jmap')

    def proxy(self, method, allowed_path):
        if self.path != allowed_path or self.headers.get('Transfer-Encoding'):
            self.send_error(404)
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length < 0 or length > LIMIT:
                self.send_error(413)
                return
            body = self.rfile.read(length)
            if len(body) != length:
                self.send_error(400)
                return
            headers = {name: self.headers[name] for name in ('Authorization', 'Accept', 'Content-Type')
                       if name in self.headers}
            conn = HTTPConnection('james', 80, timeout=10)
            try:
                conn.request(method, allowed_path, body=body, headers=headers)
                response = conn.getresponse()
                payload = response.read(LIMIT + 1)
                if len(payload) > LIMIT:
                    self.send_error(502)
                    return
                self.send_response(response.status)
                self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
                self.send_header('Content-Length', str(len(payload)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                self.wfile.write(payload)
            finally:
                conn.close()
        except (OSError, ValueError, http.client.HTTPException):
            self.close_connection = True


def main():
    server = ThreadingHTTPServer(('0.0.0.0', 8443), Handler)
    server.timeout = 10
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain('/keys/tls-cert.pem', '/keys/tls-key.pem')
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
