import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';

// Only the acceptance publisher connects through this proxy. No business sockets are touched.
const [configPath] = process.argv.slice(2);
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const root = path.dirname(path.resolve(configPath));
if (!root.replaceAll('\\', '/').includes('/.tools/dispatch-faults/')) throw new Error('Isolated evidence directory required');
const sockets = new Set();
const blocked = () => fs.existsSync(path.join(root, 'amqp.block'));
const server = net.createServer(client => {
  if (blocked()) return client.destroy();
  const upstream = net.connect(config.targetPort, config.targetHost);
  sockets.add(client); sockets.add(upstream);
  const close = () => { client.destroy(); upstream.destroy(); sockets.delete(client); sockets.delete(upstream); };
  client.on('error', close); upstream.on('error', close);
  client.on('close', close); upstream.on('close', close);
  client.pipe(upstream); upstream.pipe(client);
});
server.listen(config.port, '127.0.0.1', () => fs.writeFileSync(path.join(root, 'proxy.ready'), 'ready'));
setInterval(() => {
  if (blocked()) {
    for (const socket of sockets) socket.destroy();
    sockets.clear();
    fs.writeFileSync(path.join(root, 'amqp.blocked'), 'connections closed');
  }
}, 25);
