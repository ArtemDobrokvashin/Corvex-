'use strict';

// Candidate internal JMAP read boundary. Not a replacement for Corvex's owner,
// permission and current-user checks. Do not expose this listener publicly.
const http = require('node:http');
const https = require('node:https');
const fs = require('node:fs');
const { TextDecoder } = require('node:util');

const MAX_REQUEST = 256 * 1024;
const MAX_RESPONSE = 1024 * 1024;
const METHODS = new Set(['Mailbox/get', 'Email/query', 'Email/get']);
const CORE = 'urn:ietf:params:jmap:core';
const MAIL = 'urn:ietf:params:jmap:mail';
const BEARER = /^Bearer [A-Za-z0-9._~+/-]+=*$/;
const object = x => x !== null && typeof x === 'object' && !Array.isArray(x);

// Parse and reserialize, so neither duplicate JSON members nor escapes can be
// interpreted differently by the downstream parser. Only one call is allowed.
function canonicalReadRequest(bytes) {
  let value;
  try {
    value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  } catch { return null; }
  if (!object(value) || Object.keys(value).length !== 2 || !Object.hasOwn(value, 'using') || !Object.hasOwn(value, 'methodCalls')) return null;
  if (!Array.isArray(value.using) || value.using.length !== 2 ||
      !value.using.includes(CORE) || !value.using.includes(MAIL)) return null;
  if (!Array.isArray(value.methodCalls) || value.methodCalls.length !== 1) return null;
  const call = value.methodCalls[0];
  if (!Array.isArray(call) || call.length !== 3 || !METHODS.has(call[0]) ||
      !object(call[1]) || typeof call[1].accountId !== 'string' ||
      call[1].accountId.trim().length === 0 || call[1].accountId.length > 1024 ||
      call[2] !== 'corvex-read') return null;
  try { return Buffer.from(JSON.stringify(value), 'utf8'); }
  catch { return null; } // excessively nested JSON must not crash the gateway
}

function handle(req, res, options) {
  let upstream;
  let done = false;
  const fail = (status) => {
    if (done) return;
    done = true;
    clearTimeout(deadline);
    if (upstream) upstream.destroy();
    if (!res.headersSent && !res.destroyed) {
      res.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' });
      res.end('Request rejected');
    } else if (!res.destroyed) res.destroy();
    // Drain an already-sending client instead of retaining an unbounded body.
    req.resume();
  };
  const deadline = setTimeout(() => fail(504), options.timeoutMs);
  deadline.unref();
  res.on('close', () => {
    clearTimeout(deadline);
    if (!done && upstream) upstream.destroy();
    done = true;
  });
  req.on('error', () => fail(400));
  req.on('aborted', () => fail(400));

  const isSession = req.method === 'GET' && req.url === '/jmap/session';
  const isRead = req.method === 'POST' && req.url === '/jmap';
  if (!isSession && !isRead) return fail(req.url === '/jmap' || req.url === '/jmap/session' ? 403 : 404);
  const auth = req.headers.authorization;
  if (req.headersDistinct.authorization?.length !== 1 || typeof auth !== 'string' || auth.length > 8200 || !BEARER.test(auth)) return fail(401);
  if (req.headers['content-encoding'] !== undefined || req.headers.expect !== undefined) return fail(400);
  if (isRead && (typeof req.headers['content-type'] !== 'string' ||
      !/^application\/json(?:\s*;\s*charset\s*=\s*utf-8)?\s*$/i.test(req.headers['content-type']))) return fail(415);
  if (req.headers['content-length'] !== undefined &&
      (!/^\d+$/.test(req.headers['content-length']) || Number(req.headers['content-length']) > (isSession ? 0 : MAX_REQUEST))) return fail(413);

  let size = 0;
  const chunks = [];
  req.on('data', chunk => {
    size += chunk.length;
    if (size > (isSession ? 0 : MAX_REQUEST)) return fail(413);
    chunks.push(chunk);
  });
  req.on('end', () => {
    if (done) return;
    const body = isSession ? null : canonicalReadRequest(Buffer.concat(chunks, size));
    if (isRead && (!body || body.length > MAX_REQUEST)) return fail(400);
    const headers = { host: 'james', authorization: auth, accept: 'application/json; jmapVersion=rfc-8621' };
    if (body) { headers['content-type'] = 'application/json'; headers['content-length'] = String(body.length); }
    upstream = http.request({ host: options.upstreamHost, port: options.upstreamPort, method: req.method,
      path: req.url, headers, agent: false }, incoming => {
      if (incoming.headers['content-encoding'] !== undefined) { incoming.resume(); return fail(502); }
      let total = 0;
      const result = [];
      incoming.on('data', chunk => {
        total += chunk.length;
        if (total > MAX_RESPONSE) { incoming.destroy(); return fail(502); }
        result.push(chunk);
      });
      incoming.on('aborted', () => fail(502));
      incoming.on('error', () => fail(502));
      incoming.on('end', () => {
        if (done) return;
        done = true;
        clearTimeout(deadline);
        const bytes = Buffer.concat(result, total);
        res.writeHead(incoming.statusCode || 502, { 'content-type': 'application/json',
          'cache-control': 'no-store', 'content-length': String(bytes.length), 'x-content-type-options': 'nosniff' });
        res.end(bytes);
      });
    });
    upstream.on('error', () => fail(502));
    if (body) upstream.end(body); else upstream.end();
  });
}

function handler(options = {}) {
  const config = { upstreamHost: options.upstreamHost || 'james', upstreamPort: options.upstreamPort || 80,
    timeoutMs: options.timeoutMs || 10000 };
  return (req, res) => handle(req, res, config);
}

if (require.main === module) {
  const server = https.createServer({ key: fs.readFileSync('/run/james-tls/jmap.key'),
    cert: fs.readFileSync('/run/james-tls/jmap.crt'), minVersion: 'TLSv1.2', maxHeaderSize: 16384 }, handler());
  server.headersTimeout = 10000;
  server.requestTimeout = 10000;
  server.listen(8443, '0.0.0.0');
}

module.exports = { canonicalReadRequest, handler, MAX_REQUEST, MAX_RESPONSE };
