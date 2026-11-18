import { createCipheriv, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(process.env.TASKFLOW_ORDER_RACE_ROOT);
if (!/\/\.tools\/dispatch-mysql\/taskflow_it_[a-f0-9]{32}$/.test(root.replaceAll('\\', '/'))) {
  throw Error('An isolated order acceptance directory is required');
}
const file = name => path.join(root, name);
const [mode, tradeNo, transactionId, paidAt] = process.argv.slice(2);
if (mode === 'init') {
  const encoding = { modulusLength: 2048, publicKeyEncoding: {type: 'spki', format: 'pem'},
    privateKeyEncoding: {type: 'pkcs8', format: 'pem'} };
  const merchant = generateKeyPairSync('rsa', encoding);
  const provider = generateKeyPairSync('rsa', encoding);
  writeFileSync(file('payment-merchant.pem'), merchant.privateKey, {flag: 'wx'});
  writeFileSync(file('payment-provider.pem'), provider.privateKey, {flag: 'wx'});
  writeFileSync(file('payment-provider.pub.pem'), provider.publicKey, {flag: 'wx'});
} else if (mode === 'notify') {
  const transaction = {appid: 'wxTest', mchid: '123456', out_trade_no: tradeNo,
    transaction_id: transactionId, trade_type: 'NATIVE', trade_state: 'SUCCESS', success_time: paidAt,
    amount: {total: 250, currency: 'CNY'}};
  const nonce = randomBytes(9).toString('base64');
  const cipher = createCipheriv('aes-256-gcm', process.env.TASKFLOW_ORDER_RACE_PAYMENT_KEY, nonce);
  cipher.setAAD(Buffer.from('transaction'));
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(transaction)), cipher.final(), cipher.getAuthTag()]);
  const body = JSON.stringify({id: randomUUID(), event_type: 'TRANSACTION.SUCCESS', resource_type: 'encrypt-resource',
    resource: {algorithm: 'AEAD_AES_256_GCM', original_type: 'transaction', nonce, associated_data: 'transaction',
      ciphertext: ciphertext.toString('base64')}});
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const signature = sign('RSA-SHA256', Buffer.from(`${timestamp}\n${nonce}\n${body}\n`),
    readFileSync(file('payment-provider.pem'))).toString('base64');
  process.stdout.write(JSON.stringify({body, timestamp, nonce, signature}));
} else {
  throw Error('Expected init or notify');
}
