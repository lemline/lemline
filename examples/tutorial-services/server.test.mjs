import assert from 'node:assert/strict';
import test from 'node:test';
import { createShop, shopServer } from './server.mjs';

const items = [{ id: 'SKU-1', quantity: 2 }];
const status = code => error => error.status === code;

test('an approved order ships once, even when writes are repeated', () => {
  const shop = createShop();
  const order = { orderId: 'paid', items };
  for (let i = 0; i < 2; i++) {
    shop('POST', '/reservations', order);
    shop('POST', '/charges', { ...order, paymentInfo: { token: 'approved' } });
    shop('POST', '/quotes/economy', order);
    shop('POST', '/shipments', { ...order, carrier: 'economy' });
    shop('POST', '/notifications', { reference: 'paid', recipient: 'customer', message: 'Shipment booked with economy' });
  }
  const state = shop('GET', '/state');
  assert.equal(state.stock, 98);
  for (const key of ['reservations', 'charges', 'shipments', 'inbox']) assert.equal(state[key].length, 1);
});

test('declined payment is visible and release restores exactly the reserved quantity', () => {
  const shop = createShop();
  const order = { orderId: 'declined', items };
  const { id } = shop('POST', '/reservations', order);
  assert.throws(() => shop('POST', '/charges', { ...order, paymentInfo: { token: 'declined' } }), status(402));
  assert.throws(() => shop('POST', '/shipments', { ...order, carrier: 'express' }), status(409));
  for (let i = 0; i < 2; i++) assert.equal(shop('DELETE', '/reservations/' + id).status, 'released');
  const state = shop('GET', '/state');
  assert.equal(state.stock, 100);
  assert.deepEqual(state.history.map(row => row.action), ['Stock reserved', 'Payment declined', 'Stock released']);
  assert.equal(state.shipments.length, 0);
  assert.equal(state.inbox.length, 0);
});

test('interleaved orders have independent quote attempts and prices', () => {
  const shop = createShop();
  for (const orderId of ['A', 'B', 'A', 'B']) {
    assert.throws(() => shop('POST', '/quotes/economy', { orderId, failTwice: true }), status(503));
  }
  for (const orderId of ['B', 'A']) {
    assert.equal(shop('POST', '/quotes/express', { orderId }).price, 9);
    const quote = shop('POST', '/quotes/economy', { orderId, failTwice: true });
    assert.equal(quote.price, 5);
    assert.equal(quote.attempts, 3);
  }
});

test('invalid items leave stock untouched and reset clears every collection', () => {
  const shop = createShop();
  for (const quantity of [-1, 0.5, 101]) {
    assert.throws(() => shop('POST', '/reservations', { orderId: 'invalid', items: [{ id: 'SKU-1', quantity }] }));
  }
  assert.equal(shop('GET', '/state').stock, 100);
  shop('POST', '/reservations', { orderId: 'reset', items });
  shop('POST', '/notifications', { reference: 'sensor-1', recipient: 'warehouse-manager', message: 'High temperature: 90' });
  assert.deepEqual(shop('POST', '/reset'), createShop()('GET', '/state'));
});

test('HTTP inspection, malformed JSON and payment status codes', async t => {
  const server = shopServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const post = (path, body) => fetch(base + path, { method: 'POST', body });
  assert.match(await (await fetch(base)).text(), /Tutorial shop/);
  assert.equal((await post('/reservations', '{')).status, 400);
  assert.equal((await post('/reservations', JSON.stringify({ orderId: 'http', items }))).status, 200);
  assert.equal((await post('/charges', JSON.stringify({ orderId: 'http', paymentInfo: { token: 'declined' } }))).status, 402);
  assert.equal((await (await fetch(base + '/state')).json()).stock, 98);
});
