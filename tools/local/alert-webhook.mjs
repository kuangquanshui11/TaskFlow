import { appendFileSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { join } from 'node:path';

const [port, output, fixtureDirectory] = process.argv.slice(2);

createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    response.writeHead(200).end();
    return;
  }
  const metrics = /^\/(broker-[ab])\/metrics\/detailed(?:\?|$)/.exec(request.url);
  if (fixtureDirectory && request.method === 'GET' && metrics) {
    try {
      const body = readFileSync(join(fixtureDirectory, metrics[1] + '.prom'), 'utf8');
      if (body === 'unavailable') { response.writeHead(503).end(); return; }
      response.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' }).end(body);
    } catch {
      response.writeHead(503).end();
    }
    return;
  }
  if (request.method !== 'POST' || request.url !== '/webhook') {
    response.writeHead(404).end();
    return;
  }
  try {
    const chunks = [];
    let length = 0;
    for await (const chunk of request) {
      length += chunk.length;
      if (length > 64 * 1024) throw new Error('Payload too large');
      chunks.push(chunk);
    }
    const payload = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    appendFileSync(output, JSON.stringify({ receivedAt: new Date().toISOString(), ...payload }) + '\n');
    response.writeHead(204).end();
  } catch {
    response.writeHead(400).end();
  }
}).listen(Number(port), '127.0.0.1');
