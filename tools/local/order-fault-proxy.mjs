import http from 'node:http';
import fs from 'node:fs';

const [port, targetPort, mode, evidence] = process.argv.slice(2);
let injected = false;
http.createServer((request, response) => {
  const upstream = http.request({ hostname: '127.0.0.1', port: Number(targetPort),
    path: request.url, method: request.method, headers: request.headers }, reply => {
    const chunks = [];
    reply.on('data', chunk => chunks.push(chunk));
    reply.on('end', () => {
      if (!injected && reply.statusCode === 200 && mode !== 'pass') {
        injected = true;
        fs.writeFileSync(evidence, JSON.stringify({ status: reply.statusCode, result: JSON.parse(Buffer.concat(chunks).toString()) }));
        if (mode === 'drop') response.destroy();
        // hang 模式保留未完成响应，供脚本在已提交后杀死 Worker。
        return;
      }
      response.writeHead(reply.statusCode, reply.headers);
      response.end(Buffer.concat(chunks));
    });
  });
  upstream.on('error', () => { response.writeHead(503); response.end(); });
  request.pipe(upstream);
}).listen(Number(port), '127.0.0.1');
