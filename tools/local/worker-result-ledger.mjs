import { appendFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { resolve, sep } from 'node:path';

const [port, ledgerFile] = process.argv.slice(2);
const ledger = resolve(ledgerFile);
if (!ledger.includes(`${sep}.tools${sep}execution-e2e${sep}`)) {
  throw new Error('The ledger requires an isolated execution-e2e directory');
}

createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    response.writeHead(200, { 'Content-Type': 'application/json' }).end('{}');
    return;
  }
  if (request.method !== 'POST' || request.url !== '/internal/v1/orders/updateTimeout') {
    response.writeHead(404).end();
    return;
  }

  request.resume();
  request.once('end', () => {
    const key = request.headers['x-request-id'];
    if (typeof key !== 'string' || !/^[0-9a-f-]{36}:close-0$/.test(key)) {
      response.writeHead(400).end();
      return;
    }
    const recordedAt = new Date().toISOString();
    appendFileSync(ledger, `${JSON.stringify({ key, recordedAt })}\n`);
    response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
      scanned: 1, closed: 1, skipped: 0, failed: 0, cutoff: recordedAt,
      afterExpiresAt: recordedAt, afterId: key.split(':')[0],
    }));
  });
}).listen(Number(port), '127.0.0.1');
