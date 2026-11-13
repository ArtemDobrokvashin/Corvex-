"""Disposable synthetic-only JMAP import probe; NEVER use for real mail."""
import hashlib
import http.client
import json
import os
import urllib.parse
from pathlib import Path

MAX = 2 * 1024 * 1024
CORE = 'urn:ietf:params:jmap:core'
MAIL = 'urn:ietf:params:jmap:mail'
RAW = (b'From: User Two <user02@james.local>\r\nTo: user01@james.local\r\n'
       b'Subject: Synthetic JMAP import\r\nX-Corvex-Lab: jmap-import-fixture\r\n'
       b'X-Folded: one\r\n\ttwo\r\nMIME-Version: 1.0\r\n'
       b'Content-Type: multipart/mixed; boundary="lab-boundary"\r\n\r\n'
       b'--lab-boundary\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nHello.\r\n'
       b'--lab-boundary\r\nContent-Type: application/octet-stream\r\n'
       b'Content-Disposition: attachment; filename="fixture.bin"\r\n'
       b'Content-Transfer-Encoding: base64\r\n\r\nAAEC/w==\r\n--lab-boundary--\r\n')


def request(method, path, token, body=None, content_type=None):
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/json'}
    if content_type:
        headers['Content-Type'] = content_type
    conn = http.client.HTTPConnection('james', 80, timeout=15)
    try:
        conn.request(method, path, body=body, headers=headers)
        response = conn.getresponse()
        data = response.read(MAX + 1)
        if not 200 <= response.status < 300 or len(data) > MAX:
            raise RuntimeError('Synthetic JMAP request failed: HTTP ' + str(response.status))
        return data
    finally:
        conn.close()


def ident(value):
    if not isinstance(value, str) or not value or len(value) > 1024:
        raise RuntimeError('Invalid synthetic JMAP identifier')
    return urllib.parse.quote(value, safe='')


def json_request(method, path, token, body=None, content_type=None):
    data = request(method, path, token, body, content_type)
    try:
        result = json.loads(data)
    except (ValueError, UnicodeDecodeError) as e:
        raise RuntimeError('Invalid synthetic JMAP response') from e
    if not isinstance(result, dict):
        raise RuntimeError('Invalid synthetic JMAP response')
    return result


def call(token, method, args):
    payload = json.dumps({'using': [CORE, MAIL], 'methodCalls': [[method, args, 'probe']]}).encode('utf-8')
    result = json_request('POST', '/jmap', token, payload, 'application/json')
    responses = result.get('methodResponses')
    if not isinstance(responses, list) or len(responses) != 1 or not isinstance(responses[0], list) or len(responses[0]) != 3 or responses[0][0] != method or responses[0][2] != 'probe' or not isinstance(responses[0][1], dict):
        raise RuntimeError('Synthetic JMAP method failed')
    return responses[0][1]


def run(token):
    session = json_request('GET', '/jmap/session', token)
    account = session.get('primaryAccounts', {}).get(MAIL)
    encoded_account = ident(account)
    folders = call(token, 'Mailbox/get', {'accountId': account})
    if folders.get('accountId') != account or not isinstance(folders.get('list'), list):
        raise RuntimeError('Invalid synthetic mailbox response')
    inboxes = [x.get('id') for x in folders['list'] if isinstance(x, dict) and x.get('role') == 'inbox']
    if len(inboxes) != 1:
        raise RuntimeError('Synthetic INBOX missing or ambiguous')
    ident(inboxes[0])
    uploaded = json_request('POST', '/upload/' + encoded_account, token, RAW, 'message/rfc822')
    blob = uploaded.get('blobId')
    ident(blob)
    if uploaded.get('accountId') != account or uploaded.get('size') != len(RAW):
        raise RuntimeError('Invalid synthetic upload response')
    response = call(token, 'Email/import', {'accountId': account, 'emails': {'fixture': {
        'blobId': blob, 'mailboxIds': {inboxes[0]: True}, 'keywords': {'$seen': True, '$flagged': True}}}})
    if response.get('accountId') != account or response.get('notCreated'):
        raise RuntimeError('Synthetic Email/import rejected')
    created = response.get('created', {}).get('fixture', {})
    message_id, download_blob = created.get('id'), created.get('blobId')
    ident(message_id)
    downloaded = request('GET', '/download/' + encoded_account + '/' + ident(download_blob), token)
    if downloaded != RAW:
        raise RuntimeError('Synthetic JMAP import bytes differ: source_sha256=' + hashlib.sha256(RAW).hexdigest()
                           + ' target_sha256=' + hashlib.sha256(downloaded).hexdigest())
    email = call(token, 'Email/get', {'accountId': account, 'ids': [message_id], 'properties': ['id', 'keywords', 'mailboxIds']})
    items = email.get('list')
    if email.get('accountId') != account or email.get('notFound') != [] or not isinstance(items, list) or len(items) != 1 or items[0].get('id') != message_id or items[0].get('keywords', {}).get('$seen') is not True or items[0].get('keywords', {}).get('$flagged') is not True or items[0].get('mailboxIds', {}).get(inboxes[0]) is not True:
        raise RuntimeError('Synthetic JMAP imported flags or mailbox differ')
    print('PASS: synthetic JMAP upload/import/download exact bytes and flags; sha256=' + hashlib.sha256(RAW).hexdigest())


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise SystemExit('Synthetic-only confirmation required')
    token = Path('/lab/import-token.txt').read_text(encoding='ascii')
    if not token or len(token) > 4096 or '\n' in token:
        raise RuntimeError('Invalid synthetic token')
    run(token)

if __name__ == '__main__': main()
