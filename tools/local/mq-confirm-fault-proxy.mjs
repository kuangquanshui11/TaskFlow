import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';

// Hold the real Broker Basic.Ack before it reaches the real publisher's correlation future.
const configPath = process.argv[2];
const config = JSON.parse(fs.readFileSync(configPath, 'utf8'));
const root = path.dirname(path.resolve(configPath));
if (!root.replaceAll('\\', '/').includes('/.tools/dispatch-mysql/')) throw Error('Isolated evidence required');
const marker = name => path.join(root, name);
const clients = new Set();
const server = net.createServer(client => {
  if (fs.existsSync(marker('publisher.block'))) {
    client.destroy();
    return;
  }
  clients.add(client);
  let upstream, pending = Buffer.alloc(0), holding = false;
  const close = () => { client.destroy(); upstream?.destroy(); };
  client.on('error', close);
  client.on('close', () => { clients.delete(client); close(); });
  // Reconnect to a surviving node after the original Broker process exits.
  const connect = index => {
    const target = config.targets[index];
    if (!target) return close();
    upstream = net.connect({host: target.host, port: target.port});
    upstream.setTimeout(1000, () => upstream.destroy(Error('connect timeout')));
    upstream.once('error', () => connect(index + 1));
    upstream.once('connect', () => {
      upstream.setTimeout(0);
      upstream.removeAllListeners('error');
      upstream.on('error', close);
      upstream.on('close', close);
      client.pipe(upstream);
      upstream.on('data', chunk => {
        pending = Buffer.concat([pending, chunk]);
        while (pending.length >= 8) {
          const length = pending.readUInt32BE(3) + 8;
          if (pending.length < length) break;
          const frame = pending.subarray(0, length);
          if (!holding && frame[0] === 1 && length >= 12 && frame.readUInt16BE(7) === 60
              && frame.readUInt16BE(9) === 80 && fs.existsSync(marker('confirm.arm'))) {
            fs.renameSync(marker('confirm.arm'), marker('confirm.hit'));
            holding = true;
            fs.writeFileSync(marker('confirm.json'), JSON.stringify({time: new Date().toISOString(),
              method: 'Basic.Ack', channel: frame.readUInt16BE(1), deliveryTag: frame.readBigUInt64BE(11).toString(),
              target, forwarded: false}));
          }
          if (holding) break;
          client.write(frame);
          pending = pending.subarray(length);
        }
      });
    });
  };
  connect(0);
});
server.listen(config.port, '127.0.0.1', () => fs.writeFileSync(marker('proxy.ready'), 'ready'));
setInterval(() => {
  if (fs.existsSync(marker('publisher.block'))) {
    for (const client of clients) client.destroy();
  }
  if (fs.existsSync(marker('confirm.cut'))) {
    fs.renameSync(marker('confirm.cut'), marker('confirm.cut.done'));
    for (const client of clients) client.destroy();
  }
}, 25);
