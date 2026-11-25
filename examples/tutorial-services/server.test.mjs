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

test('fail, failTimes and key give deterministic failures, counted per order', () => {
  const shop = createShop();
  for (let i = 0; i < 2; i++) {
    assert.throws(() => shop('GET', '/stock?fail=503&failTimes=2&key=A'), status(503));
  }
  assert.throws(() => shop('GET', '/stock?fail=503&failTimes=2&key=B'), status(503));
  assert.equal(shop('GET', '/stock?fail=503&failTimes=2&key=A').available, 100);
  for (const code of [404, 429, 500]) assert.throws(() => shop('GET', '/products?fail=' + code), status(code));
  assert.throws(() => shop('GET', '/stock?fail=418'), status(400));
  const counters = shop('GET', '/state').failures.map(row => [row.reference, row.failed, row.passed]);
  assert.deepEqual(counters, [['GET /stock A', 2, 1], ['GET /stock B', 1, 0], ['GET /products', 3, 0]]);
  const posted = { orderId: 'ORD-9', items };
  assert.throws(() => shop('POST', '/reservations?fail=500&failTimes=1', posted), status(500));
  assert.equal(shop('POST', '/reservations?fail=500&failTimes=1', posted).status, 'reserved');
  assert.equal(shop('GET', '/state').stock, 98);
});

test('partner stock accepts each credential style and refuses missing or wrong ones', () => {
  const shop = createShop();
  const basic = 'Basic ' + Buffer.from('demo:demo-password').toString('base64');
  assert.equal(shop('GET', '/partner/stock', {}, { 'x-api-key': 'demo-api-key' }).authenticatedWith, 'api-key-header');
  assert.equal(shop('GET', '/partner/stock?api_key=demo-api-key').authenticatedWith, 'api-key-query');
  assert.equal(shop('GET', '/partner/stock', {}, { authorization: 'Bearer demo-token' }).authenticatedWith, 'bearer');
  assert.equal(shop('GET', '/partner/stock', {}, { authorization: basic }).authenticatedWith, 'basic');
  assert.throws(() => shop('GET', '/partner/stock'), status(401));
  assert.throws(() => shop('GET', '/partner/stock', {}, { authorization: 'Bearer wrong' }), status(403));
  assert.throws(() => shop('GET', '/partner/stock?api_key=wrong'), status(403));
});

test('products paginate and filter by query parameters; unknown IDs are 404', () => {
  const shop = createShop();
  const first = shop('GET', '/products?size=3');
  assert.deepEqual([first.items.length, first.hasMore, first.nextPage, first.total], [3, true, 2, 7]);
  const last = shop('GET', '/products?page=3&size=3');
  assert.deepEqual([last.items.map(row => row.id), last.hasMore, last.nextPage], [['SKU-7'], false, null]);
  assert.deepEqual(shop('GET', '/products?category=home').items.map(row => row.id), ['SKU-4', 'SKU-5']);
  assert.deepEqual(shop('GET', '/products?search=SOCKS').items.map(row => row.id), ['SKU-7']);
  assert.equal(shop('GET', '/products/SKU-2').name, 'Watering can');
  assert.throws(() => shop('GET', '/products/SKU-404'), status(404));
  assert.throws(() => shop('GET', '/products?page=0'), status(400));
});

test('validation data: customers, emails and addresses', () => {
  const shop = createShop();
  assert.equal(shop('GET', '/customers/C-100').emailValid, true);
  assert.equal(shop('GET', '/customers/C-300').emailValid, false);
  assert.throws(() => shop('GET', '/customers/C-999'), status(404));
  assert.deepEqual(shop('POST', '/email-checks', { email: 'bad' }), { email: 'bad', valid: false });
  const created = shop('POST', '/customers', { name: 'New', email: 'new@example.com' });
  assert.equal(shop('POST', '/customers', { name: 'Again', email: 'new@example.com' }).id, created.id);
  assert.throws(() => shop('POST', '/customers', { name: 'Bad', email: 'bad' }), status(422));
  assert.equal(shop('POST', '/addresses/validate', { address: { street: '1 Main St', city: 'Lyon', country: 'FR' } }).valid, true);
  assert.throws(() => shop('POST', '/addresses/validate', { address: { city: 'Lyon' } }), error => error.status === 422 && /street/.test(error.message));
});

test('saga steps: refund once, cancel a shipment, then release the reservation', () => {
  const shop = createShop();
  const order = { orderId: 'saga', items };
  const { id } = shop('POST', '/reservations', order);
  shop('POST', '/charges', { ...order, paymentInfo: { token: 'approved' } });
  shop('POST', '/quotes/express', order);
  shop('POST', '/shipments', { ...order, carrier: 'express' });
  assert.throws(() => shop('DELETE', '/reservations/' + id), status(409));
  assert.equal(shop('DELETE', '/shipments/saga').status, 'cancelled');
  for (let i = 0; i < 2; i++) assert.equal(shop('POST', '/refunds', order).amount, 50);
  assert.equal(shop('DELETE', '/reservations/' + id).status, 'released');
  const state = shop('GET', '/state');
  assert.deepEqual([state.stock, state.refunds.length], [100, 1]);
  assert.throws(() => shop('POST', '/refunds', { orderId: 'unknown' }), status(409));
});

test('documents are created once, updated and deleted; audit entries are idempotent', () => {
  const shop = createShop();
  const document = shop('POST', '/documents', { reference: 'ORD-1', title: 'Invoice', content: 'x' });
  assert.equal(shop('POST', '/documents', { reference: 'ORD-1', title: 'Invoice' }).id, document.id);
  assert.equal(shop('PATCH', '/documents/' + document.id, { status: 'final' }).status, 'final');
  assert.equal(shop('DELETE', '/documents/' + document.id).status, 'deleted');
  assert.throws(() => shop('PATCH', '/documents/' + document.id, { status: 'final' }), status(409));
  shop('POST', '/audit', { reference: 'ORD-1', message: 'checked' });
  shop('POST', '/audit', { reference: 'ORD-1', message: 'checked' });
  assert.equal(shop('GET', '/state').audit.length, 1);
});

test('GraphQL stand-in answers the customer query and validates the review mutation', () => {
  const shop = createShop();
  const customer = shop('POST', '/graphql', { query: 'query Customer($id: ID!) { customer(id: $id) { id } }', variables: { id: 'C-100' } });
  assert.equal(customer.data.customer.reviews.length, 2);
  assert.equal(shop('POST', '/graphql', { query: 'query { customer(id: $id) { id } }', variables: { id: 'C-9' } }).errors.length, 1);
  const mutation = 'mutation AddReview($review: ReviewInput!) { addReview(review: $review) { id } }';
  assert.equal(shop('POST', '/graphql', { query: mutation, variables: { review: { customerId: 'C-200', productId: 'SKU-2', rating: 4 } } }).data.addReview.id, 'R-3');
  assert.equal(shop('POST', '/graphql', { query: mutation, variables: { review: { customerId: 'C-200', productId: 'SKU-2', rating: 9 } } }).errors.length, 1);
});

test('HTTP layer: request log, delay, redirect and Retry-After', async t => {
  const server = shopServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  await fetch(base + '/partner/stock?api_key=demo-api-key', { headers: { 'X-Trace': 'yes' } });
  const posted = await fetch(base + '/audit', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reference: 'R', message: 'm' }) });
  assert.equal(posted.status, 200);
  const throttled = await fetch(base + '/stock?fail=429&failTimes=1');
  assert.deepEqual([throttled.status, throttled.headers.get('retry-after')], [429, '1']);
  const redirect = await fetch(base + '/old-stock', { redirect: 'manual' });
  assert.deepEqual([redirect.status, redirect.headers.get('location')], [301, '/stock']);
  const started = Date.now();
  assert.equal((await fetch(base + '/stock?delayMs=300')).status, 200);
  assert.ok(Date.now() - started >= 290);
  const { requests } = await (await fetch(base + '/state')).json();
  assert.deepEqual(requests.map(row => [row.method, row.path, row.status]),
    [['GET', '/partner/stock', 200], ['POST', '/audit', 200], ['GET', '/stock', 429], ['GET', '/old-stock', 301], ['GET', '/stock', 200]]);
  assert.equal(requests[0].query.api_key, 'demo-api-key');
  assert.equal(requests[0].headers['x-trace'], 'yes');
  assert.equal(requests[1].body, '{"reference":"R","message":"m"}');
  assert.equal(requests[1].reference, 'R');
});
