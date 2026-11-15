'use strict';
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { handler, MAX_REQUEST, MAX_RESPONSE } = require('./gateway.cjs');

let backend, gateway, port, calls;
const core = 'urn:ietf:params:jmap:core', mail = 'urn:ietf:params:jmap:mail';
function payload(method = 'Email/get') {
  return JSON.stringify({ using: [core, mail], methodCalls: [[method, { accountId: 'own' }, 'corvex-read']] });
}
function listen(server) { return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port))); }
function close(server) { return new Promise(resolve => server.close(resolve)); }
function request(path, method = 'POST', body = '', headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, path, method,
      headers: { authorization: 'Bearer synthetic.token', 'content-type': 'application/json', ...headers } }, res => {
      const chunks = [];
      res.on('data', x => chunks.push(x));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString() }));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.end(body);
  });
}
before(async () => {
  calls = [];
  backend = http.createServer((req, res) => {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      calls.push({ path: req.url, auth: req.headers.authorization, headers: req.headers, body });
      if (req.headers.authorization === 'Bearer oversize') return res.end('x'.repeat(MAX_RESPONSE + 1));
      if (req.headers.authorization === 'Bearer stall') return; // gateway must bound a stalled upstream
      res.setHeader('set-cookie', 'do-not-forward=secret');
      res.end('{"ok":true}');
    });
  });
  const upstreamPort = await listen(backend);
  gateway = http.createServer(handler({ upstreamHost: '127.0.0.1', upstreamPort, timeoutMs: 1500 }));
  port = await listen(gateway);
});
after(async () => { await close(gateway); await close(backend); });

test('only the exact session endpoint and three read methods pass', async () => {
  assert.equal((await request('/jmap/session', 'GET')).status, 200);
  for (const method of ['Mailbox/get', 'Email/query', 'Email/get']) {
    const reply = await request('/jmap', 'POST', payload(method));
    assert.equal(reply.status, 200);
    assert.equal(reply.headers['set-cookie'], undefined);
    assert.equal(reply.headers['cache-control'], 'no-store');
  }
  assert.equal(calls.length, 4);
  assert.equal(calls[0].auth, 'Bearer synthetic.token');
  assert.equal(calls[0].headers.host, 'james');
});

test('write operations, mixed calls, alternate paths and methods never reach James', async () => {
  const before = calls.length;
  for (const method of ['Email/import', 'Email/set', 'EmailSubmission/set', 'Mailbox/set', 'Core/echo']) {
    assert.equal((await request('/jmap', 'POST', payload(method))).status, 400);
  }
  const mixed = JSON.stringify({ using: [core, mail], methodCalls: [
    ['Email/get', { accountId: 'own' }, 'corvex-read'], ['Email/import', { accountId: 'own' }, 'x'] ] });
  assert.equal((await request('/jmap', 'POST', mixed)).status, 400);
  assert.equal((await request('/jmap?x=1', 'POST', payload())).status, 404);
  assert.equal((await request('/%6amap', 'POST', payload())).status, 404);
  assert.equal((await request('/jmap/session', 'POST', payload())).status, 403);
  assert.equal((await request('/jmap', 'GET')).status, 403);
  assert.equal((await request('/download/id', 'GET')).status, 404);
  assert.equal((await request('/jmap', 'POST', payload('Email/import').replace('Email/import', 'Email/\\u0069mport'))).status, 400);
  assert.equal((await request('/jmap', 'POST', JSON.stringify({ using: [core, mail], methodCalls: [['Email/get', {accountId: 'own'}, 'corvex-read']], extra: 1 }))).status, 400);
  assert.equal(calls.length, before);
});

test('parser discrepancies and duplicate members cannot smuggle a write', async () => {
  const before = calls.length;
  // The last duplicate is used by Node, then serialized into a single member.
  const duplicate = `{"using":["${core}","${mail}"],"methodCalls":[["Email/import",{"accountId":"own"},"x"]],"methodCalls":[["Email/get",{"accountId":"own"},"corvex-read"]]}`;
  assert.equal((await request('/jmap', 'POST', duplicate)).status, 200);
  assert.equal(calls.length, before + 1);
  assert.deepEqual(JSON.parse(calls.at(-1).body).methodCalls, [['Email/get', { accountId: 'own' }, 'corvex-read']]);
  assert.equal((calls.at(-1).body.match(/methodCalls/g) || []).length, 1);
  const reversed = duplicate.replace('Email/import', 'Email/get').replace('"x"', '"corvex-read"')
    .replace(/"Email\/get",\{"accountId":"own"\},"corvex-read"\]\]\}$/, '"Email/import",{"accountId":"own"},"corvex-read"]]}');
  assert.equal((await request('/jmap', 'POST', reversed)).status, 400);
  assert.equal(calls.length, before + 1);
});

test('rejects malformed input, oversized body, encodings and unauthenticated requests', async () => {
  const before = calls.length;
  assert.equal((await request('/jmap', 'POST', '{')).status, 400);
  assert.equal((await request('/jmap', 'POST', payload(), { authorization: 'Basic abc' })).status, 401);
  assert.equal((await request('/jmap', 'POST', payload(), { 'content-encoding': 'gzip' })).status, 400);
  assert.equal((await request('/jmap', 'POST', payload(), { 'content-type': 'text/plain' })).status, 415);
  assert.equal((await request('/jmap', 'POST', 'x'.repeat(MAX_REQUEST + 1))).status, 413);
  assert.equal((await request('/jmap/session', 'GET', 'x', { 'content-length': '1' })).status, 413);
  assert.equal(calls.length, before);
});

test('never forwards untrusted client headers and bounds upstream responses', async () => {
  const result = await request('/jmap', 'POST', payload(), { cookie: 'secret', 'x-forwarded-for': 'anything' });
  assert.equal(result.status, 200);
  assert.equal(calls.at(-1).headers.cookie, undefined);
  assert.equal(calls.at(-1).headers['x-forwarded-for'], undefined);
  assert.equal((await request('/jmap', 'POST', payload(), { authorization: 'Bearer oversize' })).status, 502);
  assert.equal((await request('/jmap', 'POST', payload(), { authorization: 'Bearer stall' })).status, 504);
});

test('excessively nested JSON fails closed without terminating the gateway', async () => {
  const nested = '['.repeat(15000) + '0' + ']'.repeat(15000);
  const body = `{"using":["${core}","${mail}"],"methodCalls":[["Email/get",{"accountId":"own","nest":${nested}},"corvex-read"]]}`;
  const before = calls.length;
  assert.equal((await request('/jmap', 'POST', body)).status, 400);
  assert.equal(calls.length, before);
  assert.equal((await request('/jmap/session', 'GET')).status, 200);
});
