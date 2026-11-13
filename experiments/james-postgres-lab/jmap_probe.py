"""Isolated demo-only JMAP read/security checks; never use with real data."""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

HOST = 'http://james:80'  # Only the isolated, synthetic lab network.
USERS = ('user01@james.local', 'user02@james.local')
PASSWORD = '1234'  # Public demonstration credentials, never production.
MAIL = 'urn:ietf:params:jmap:mail'
CORE = 'urn:ietf:params:jmap:core'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never send credentials to a server-selected destination.


def request(path, user=None, payload=None):
    headers = {'Accept': 'application/json; jmapVersion=rfc-8621'}
    if user:
        auth = base64.b64encode((user + ':' + PASSWORD).encode('ascii')).decode('ascii')
        headers['Authorization'] = 'Basic ' + auth
    if payload is not None:
        headers['Content-Type'] = 'application/json'
    body = json.dumps(payload).encode('utf-8') if payload is not None else None
    req = urllib.request.Request(HOST + path, data=body, headers=headers)
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=10) as response:
            data = response.read(1024 * 1024 + 1)
            if len(data) > 1024 * 1024:
                raise RuntimeError('JMAP response exceeded limit')
            return response.status, json.loads(data)
    except urllib.error.HTTPError as error:
        return error.code, None


def mailbox_get(user, account):
    request_body = {'using': [CORE, MAIL], 'methodCalls': [['Mailbox/get', {'accountId': account}, 'c1']]}
    status, data = request('/jmap', user, request_body)
    if status != 200 or not isinstance(data, dict):
        raise RuntimeError(f'Mailbox/get HTTP status {status}')
    responses = data.get('methodResponses')
    if not isinstance(responses, list) or len(responses) != 1 or len(responses[0]) != 3:
        raise RuntimeError('Unexpected JMAP method response')
    return responses[0]


def main():
    if os.environ.get('JAMES_LAB_CONFIRM') != 'synthetic-only':
        raise RuntimeError('This probe is only for the isolated synthetic lab')
    results = []
    def check(name, fn):
        try:
            fn()
        except Exception as exc:
            results.append(('FAIL', name))
            print(f'FAIL: {name}: {type(exc).__name__}: {exc}', flush=True)
        else:
            results.append(('PASS', name))
            print(f'PASS: {name}', flush=True)

    sessions = {}
    def anonymous_denied():
        status, _ = request('/jmap/session')
        if status != 401:
            raise RuntimeError(f'Expected 401, got {status}')
    check('anonymous JMAP session rejected', anonymous_denied)

    def get_session(user):
        status, session = request('/jmap/session', user)
        if status != 200 or not isinstance(session, dict):
            raise RuntimeError(f'JMAP session HTTP status {status}')
        account = session.get('primaryAccounts', {}).get(MAIL)
        if not isinstance(account, str) or not account:
            raise RuntimeError('No primary mail account in JMAP session')
        sessions[user] = account
    for user in USERS:
        check('JMAP session ' + user, lambda user=user: get_session(user))
    if all(user in sessions for user in USERS):
        def own_and_isolation():
            if sessions[USERS[0]] == sessions[USERS[1]]:
                raise RuntimeError('Both users received the same primary account')
            response = mailbox_get(USERS[0], sessions[USERS[0]])
            if response[0] != 'Mailbox/get' or response[2] != 'c1':
                raise RuntimeError('Own mailbox request failed')
            cross = mailbox_get(USERS[1], sessions[USERS[0]])
            if cross[0] != 'error' or cross[2] != 'c1' or cross[1].get('type') not in ('accountNotFound', 'forbidden'):
                raise RuntimeError('Foreign mailbox access was not explicitly rejected')
        check('own mailbox and cross-user denial', own_and_isolation)
    else:
        results.append(('SKIP', 'own mailbox and cross-user denial (session prerequisite failed)'))
    for name in ('Corvex JWT integration', 'SMTP/IMAP interoperability', 'restart and crash recovery',
                 'backup/restore', 'production TLS/AV', 'load'):
        results.append(('SKIP', name))
    print('\n=== POSTGRES JMAP LAB SUMMARY ===')
    for status, name in results:
        print(f'{status}: {name}')
    failed = sum(status == 'FAIL' for status, _ in results)
    print(f'TOTAL: PASS={sum(s == "PASS" for s, _ in results)} FAIL={failed} SKIP={sum(s == "SKIP" for s, _ in results)}')
    print('Synthetic isolated lab only; no production acceptance.')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
