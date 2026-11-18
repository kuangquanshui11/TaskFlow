import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const configPath = path.resolve(process.argv[2]);
const root = path.dirname(configPath);
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const validPort = value => Number.isInteger(value) && value >= 1024 && value <= 65535;
if (!root.replaceAll('\\', '/').includes('/.tools/dispatch-mysql/')
    || !validPort(config.port) || !validPort(config.schedulerPort) || config.port === config.schedulerPort) {
  throw new Error('An isolated evidence directory and distinct loopback ports are required');
}
const marker = name => path.join(root, name);
const sockets = new Set();
const blocked = () => fs.existsSync(marker('worker-link.block'));
const server = http.createServer((request, response) => {
  if (request.method === 'POST' && request.url === '/internal/v1/attempts/updateResult'
      && fs.existsSync(marker('worker-result.arm'))) {
    fs.renameSync(marker('worker-result.arm'), marker('worker-link.block'));
    fs.writeFileSync(marker('worker-result.hit'), new Date().toISOString());
  }
  if (blocked()) {
    request.socket.destroy();
    return;
  }
  const upstream = http.request({
    host: '127.0.0.1', port: config.schedulerPort, path: request.url,
    method: request.method, headers: request.headers,
  }, incoming => {
    response.writeHead(incoming.statusCode, incoming.headers);
    incoming.on('error', () => response.destroy());
    incoming.pipe(response);
  });
  upstream.on('error', () => response.destroy());
  response.on('close', () => upstream.destroy());
  request.pipe(upstream);
});
server.on('connection', socket => {
  sockets.add(socket);
  socket.on('close', () => sockets.delete(socket));
});
setInterval(() => {
  if (blocked()) for (const socket of sockets) socket.destroy();
}, 25);
server.listen(config.port, '127.0.0.1', () => fs.writeFileSync(marker('worker-link.ready'), 'ready'));
