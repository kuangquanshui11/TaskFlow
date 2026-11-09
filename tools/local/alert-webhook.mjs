import { appendFileSync } from 'node:fs';
import { createServer } from 'node:http';

const [port, output] = process.argv.slice(2);

createServer(async (request, response) => {
  if (request.method === 'GET' && request.url === '/health') {
    response.writeHead(200).end();
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
