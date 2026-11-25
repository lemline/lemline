import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const index = readFileSync(new URL('./index.html', import.meta.url));
const DEMO_API_KEY = 'demo-api-key';
const DEMO_TOKEN = 'demo-token';
const DEMO_USER = 'demo';
const DEMO_PASSWORD = 'demo-password';
const MAX_DELAY_MS = 60000;
const MAX_REQUESTS = 500;
const FAILURE_STATUSES = [400, 401, 403, 404, 409, 422, 429, 500, 502, 503, 504];
const IGNORED_HEADERS = new Set(['host', 'connection', 'content-length', 'accept-encoding', 'user-agent', 'accept', 'origin']);

const PRODUCTS = [
  { id: 'SKU-1', name: 'Garden hose', category: 'garden', price: 25 },
  { id: 'SKU-2', name: 'Watering can', category: 'garden', price: 12 },
  { id: 'SKU-3', name: 'Pruning shears', category: 'garden', price: 18 },
  { id: 'SKU-4', name: 'Desk lamp', category: 'home', price: 30 },
  { id: 'SKU-5', name: 'Bookshelf', category: 'home', price: 80 },
  { id: 'SKU-6', name: 'Rain jacket', category: 'clothing', price: 45 },
  { id: 'SKU-7', name: 'Wool socks', category: 'clothing', price: 9 },
];
const CUSTOMERS = [
  { id: 'C-100', name: 'Ada Lovelace', email: 'ada@example.com', tier: 'gold', country: 'GB' },
  { id: 'C-200', name: 'Alan Turing', email: 'alan@example.com', tier: 'standard', country: 'GB' },
  { id: 'C-300', name: 'Grace Hopper', email: 'not-an-email', tier: 'standard', country: 'US' },
];
const REVIEWS = [
  { id: 'R-1', customerId: 'C-100', productId: 'SKU-1', rating: 5, title: 'Never kinks' },
  { id: 'R-2', customerId: 'C-100', productId: 'SKU-4', rating: 4, title: 'Bright enough' },
];

const OPENAPI = {
  openapi: '3.0.3',
  info: { title: 'Tutorial shop catalog', version: '1.0.0' },
  servers: [{ url: 'http://127.0.0.1:8787' }],
  paths: {
    '/products': {
      get: {
        operationId: 'listProducts',
        parameters: ['page', 'size', 'category', 'search'].map(name => ({ name, in: 'query', required: false, schema: { type: name === 'page' || name === 'size' ? 'integer' : 'string' } })),
        responses: { 200: { description: 'One page of products' } },
      },
    },
    '/products/{productId}': {
      get: {
        operationId: 'getProduct',
        parameters: [{ name: 'productId', in: 'path', required: true, schema: { type: 'string' } }],
        responses: { 200: { description: 'The product' }, 404: { description: 'Unknown product' } },
      },
    },
  },
};

class ShopError extends Error {
  constructor(status, message, headers = {}) {
    super(message);
    this.status = status;
    this.headers = headers;
  }
}
/** A response other than "200 with a JSON body". */
export class Reply {
  constructor(status, body, headers = {}) {
    this.status = status;
    this.body = body;
    this.headers = headers;
  }
}
function requireValue(condition, message, status = 400) {
  if (!condition) throw new ShopError(status, message);
}
function string(value) {
  return typeof value === 'string' && value.trim().length > 0;
}
function validEmail(value) {
  return typeof value === 'string' && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value);
}
function initialState() {
  return {
    stock: 100, reservations: [], charges: [], refunds: [], quotes: [], shipments: [], inbox: [],
    customers: [], reviews: [], documents: [], audit: [], history: [], requests: [], failures: [],
  };
}
function integerParameter(query, name, fallback, min, max) {
  if (!query.has(name)) return fallback;
  const value = Number(query.get(name));
  requireValue(Number.isSafeInteger(value) && value >= min && value <= max, `Query parameter ${name} must be an integer from ${min} to ${max}`);
  return value;
}
/** Reads the fail, failTimes and delayMs controls that every route accepts. */
export function controls(query) {
  const fail = integerParameter(query, 'fail', null, 100, 599);
  requireValue(fail === null || FAILURE_STATUSES.includes(fail), 'Query parameter fail must be one of ' + FAILURE_STATUSES.join(', '));
  return {
    fail,
    failTimes: integerParameter(query, 'failTimes', Infinity, 0, 1000),
    delayMs: integerParameter(query, 'delayMs', 0, 0, MAX_DELAY_MS),
  };
}
function authorize(query, headers) {
  const header = headers['x-api-key'];
  const authorization = headers.authorization;
  let method;
  let valid;
  if (header !== undefined) {
    method = 'api-key-header';
    valid = header === DEMO_API_KEY;
  } else if (query.has('api_key')) {
    method = 'api-key-query';
    valid = query.get('api_key') === DEMO_API_KEY;
  } else if (/^bearer /i.test(authorization ?? '')) {
    method = 'bearer';
    valid = authorization.slice(7).trim() === DEMO_TOKEN;
  } else if (/^basic /i.test(authorization ?? '')) {
    method = 'basic';
    valid = Buffer.from(authorization.slice(6).trim(), 'base64').toString() === DEMO_USER + ':' + DEMO_PASSWORD;
  }
  if (!method) throw new ShopError(401, 'Missing credentials', { 'WWW-Authenticate': 'Bearer realm="shop-partner"' });
  if (!valid) throw new ShopError(403, 'Invalid credentials for ' + method);
  return method;
}
function graphql(state, body) {
  requireValue(string(body.query), 'Expected a GraphQL query');
  const variables = body.variables ?? {};
  if (/\bmutation\b/.test(body.query) && /\baddReview\b/.test(body.query)) {
    const input = variables.review ?? {};
    const errors = [];
    if (!CUSTOMERS.some(row => row.id === input.customerId)) errors.push({ message: 'Unknown customer ' + input.customerId, path: ['addReview'] });
    if (!PRODUCTS.some(row => row.id === input.productId)) errors.push({ message: 'Unknown product ' + input.productId, path: ['addReview'] });
    if (!(Number.isSafeInteger(input.rating) && input.rating >= 1 && input.rating <= 5)) errors.push({ message: 'Rating must be from 1 to 5', path: ['addReview'] });
    if (errors.length) return { data: { addReview: null }, errors };
    let review = state.reviews.find(row => row.customerId === input.customerId && row.productId === input.productId);
    if (!review) {
      review = { id: 'R-' + (REVIEWS.length + state.reviews.length + 1), reference: input.customerId, ...input };
      state.reviews.push(review);
      state.history.push({ reference: input.customerId, action: 'Review added for ' + input.productId });
    }
    return { data: { addReview: { id: review.id, rating: review.rating, title: review.title ?? null } } };
  }
  if (/\bcustomer\s*\(/.test(body.query)) {
    const customer = CUSTOMERS.find(row => row.id === variables.id);
    if (!customer) return { data: { customer: null }, errors: [{ message: 'Unknown customer ' + variables.id, path: ['customer'] }] };
    const reviews = [...REVIEWS, ...state.reviews].filter(row => row.customerId === customer.id)
      .map(({ id, productId, rating, title }) => ({ id, productId, rating, title: title ?? null }));
    const orders = state.reservations.filter(row => row.customerId === customer.id).length;
    return { data: { customer: { id: customer.id, name: customer.name, email: customer.email, tier: customer.tier, reviews, orders: { totalCount: orders } } } };
  }
  return { errors: [{ message: 'This demo endpoint answers the customer query and the addReview mutation only' }] };
}

export function createShop() {
  let state = initialState();
  function record(reference, action) {
    state.history.push({ reference, action });
  }
  function simulated(method, path, query, body, rules) {
    if (rules.fail === null) return;
    const key = query.get('key') ?? body?.orderId ?? body?.reference ?? '';
    const reference = method + ' ' + path + (key ? ' ' + key : '');
    let counter = state.failures.find(row => row.reference === reference);
    if (!counter) {
      counter = { reference, status: rules.fail, failed: 0, passed: 0 };
      state.failures.push(counter);
    }
    if (counter.failed < rules.failTimes) {
      counter.failed++;
      counter.status = rules.fail;
      record(key || path, `Simulated ${rules.fail} on ${method} ${path} (${counter.failed} of ${rules.failTimes === Infinity ? 'every call' : rules.failTimes})`);
      const headers = rules.fail === 429 ? { 'Retry-After': '1' } : {};
      throw new ShopError(rules.fail, `Simulated ${rules.fail} failure ${counter.failed}`, headers);
    }
    counter.passed++;
  }
  return function dispatch(method, target, body = {}, headers = {}) {
    const url = new URL(target, 'http://shop.local');
    const path = url.pathname;
    const query = url.searchParams;
    if (method === 'GET' && path === '/state') return state;
    if (method === 'POST' && path === '/reset') {
      state = initialState();
      return state;
    }
    const rules = controls(query);
    simulated(method, path, query, body, rules);
    const segments = path.split('/').filter(Boolean).map(decodeURIComponent);

    if (method === 'GET') {
      if (path === '/stock' || path === '/inventory/SKU-1') {
        return { sku: 'SKU-1', available: state.stock, reserved: state.reservations.filter(row => row.status === 'reserved').length };
      }
      if (path === '/old-stock') return new Reply(301, { moved: '/stock' }, { Location: '/stock' });
      if (path === '/openapi.json') return OPENAPI;
      if (path === '/products') {
        const page = integerParameter(query, 'page', 1, 1, 1000);
        const size = integerParameter(query, 'size', 3, 1, 50);
        const category = query.get('category');
        const search = query.get('search')?.toLowerCase();
        const matches = PRODUCTS.filter(row => (!category || row.category === category) && (!search || row.name.toLowerCase().includes(search)));
        const items = matches.slice((page - 1) * size, page * size);
        const hasMore = page * size < matches.length;
        return { items, page, size, total: matches.length, hasMore, nextPage: hasMore ? page + 1 : null };
      }
      if (segments[0] === 'products' && segments.length === 2) {
        const product = PRODUCTS.find(row => row.id === segments[1]);
        requireValue(product, 'Unknown product ' + segments[1], 404);
        return { ...product, available: product.id === 'SKU-1' ? state.stock : 10 };
      }
      if (segments[0] === 'customers' && segments.length === 2) {
        const customer = CUSTOMERS.find(row => row.id === segments[1]) ?? state.customers.find(row => row.id === segments[1]);
        requireValue(customer, 'Unknown customer ' + segments[1], 404);
        return { ...customer, emailValid: validEmail(customer.email) };
      }
      if (segments[0] === 'reservations' && segments.length === 2) {
        const reservation = state.reservations.find(row => row.id === segments[1] || row.orderId === segments[1]);
        requireValue(reservation, 'Unknown reservation', 404);
        return reservation;
      }
      if (path === '/partner/stock') {
        const authenticatedWith = authorize(query, headers);
        record('partner', 'Partner stock read with ' + authenticatedWith);
        return { sku: 'SKU-1', available: state.stock, authenticatedWith };
      }
      if (segments[0] === 'documents' && segments.length === 2) {
        const document = state.documents.find(row => row.id === segments[1]);
        requireValue(document, 'Unknown document', 404);
        return document;
      }
      throw new ShopError(404, 'Unknown route');
    }

    if (method === 'DELETE') {
      if (segments[0] === 'reservations' && segments.length === 2) {
        const reservation = state.reservations.find(row => row.id === segments[1]);
        requireValue(reservation, 'Unknown reservation', 404);
        requireValue(!state.shipments.some(row => row.orderId === reservation.orderId && row.status !== 'cancelled'), 'Order already shipped', 409);
        if (reservation.status === 'reserved') {
          reservation.status = 'released';
          state.stock += reservation.quantity;
          record(reservation.orderId, 'Stock released');
        }
        return reservation;
      }
      if (segments[0] === 'shipments' && segments.length === 2) {
        const shipment = state.shipments.find(row => row.orderId === segments[1]);
        requireValue(shipment, 'Unknown shipment', 404);
        if (shipment.status !== 'cancelled') {
          shipment.status = 'cancelled';
          record(shipment.orderId, 'Shipment cancelled');
        }
        return shipment;
      }
      if (segments[0] === 'documents' && segments.length === 2) {
        const document = state.documents.find(row => row.id === segments[1]);
        requireValue(document, 'Unknown document', 404);
        if (document.status !== 'deleted') {
          document.status = 'deleted';
          record(document.reference, 'Document deleted: ' + document.title);
        }
        return document;
      }
      throw new ShopError(404, 'Unknown route');
    }

    if (method === 'PATCH' && segments[0] === 'documents' && segments.length === 2) {
      const document = state.documents.find(row => row.id === segments[1]);
      requireValue(document, 'Unknown document', 404);
      requireValue(document.status !== 'deleted', 'Document deleted', 409);
      requireValue(body && typeof body === 'object' && !Array.isArray(body), 'Expected a JSON object');
      for (const field of ['title', 'content', 'status', 'attachmentId']) if (field in body) document[field] = body[field];
      record(document.reference, 'Document updated: ' + document.title);
      return document;
    }

    requireValue(method === 'POST', 'Unknown route', 404);
    requireValue(body && typeof body === 'object' && !Array.isArray(body), 'Expected a JSON object');
    const { orderId, items, carrier, reference, recipient, message } = body;
    if (path === '/notifications') {
      requireValue([reference, recipient, message].every(string), 'Expected reference, recipient and message');
      let entry = state.inbox.find(row => row.reference === reference && row.recipient === recipient && row.message === message);
      if (!entry) {
        entry = { reference, recipient, message };
        state.inbox.push(entry);
        record(reference, 'Notification sent to ' + recipient);
      }
      return entry;
    }
    if (path === '/audit') {
      requireValue([reference, message].every(string), 'Expected reference and message');
      let entry = state.audit.find(row => row.reference === reference && row.message === message);
      if (!entry) {
        entry = { reference, message };
        state.audit.push(entry);
      }
      return entry;
    }
    if (path === '/email-checks') {
      requireValue(typeof body.email === 'string', 'Expected an email');
      return { email: body.email, valid: validEmail(body.email) };
    }
    if (path === '/customers') {
      requireValue(string(body.name), 'Expected a name');
      requireValue(validEmail(body.email), 'Invalid email', 422);
      let customer = [...CUSTOMERS, ...state.customers].find(row => row.email === body.email);
      if (!customer) {
        customer = { id: 'C-' + (900 + state.customers.length + 1), reference: body.email, name: body.name, email: body.email, tier: 'standard' };
        state.customers.push(customer);
        record(body.email, 'Customer created: ' + customer.id);
      }
      return customer;
    }
    if (path === '/graphql') return graphql(state, body);
    if (path === '/documents') {
      requireValue([reference, body.title].every(string), 'Expected reference and title');
      let document = state.documents.find(row => row.reference === reference && row.title === body.title && row.status !== 'deleted');
      if (!document) {
        document = { id: randomUUID(), reference, title: body.title, content: body.content ?? null, status: 'draft' };
        state.documents.push(document);
        record(reference, 'Document created: ' + body.title);
      }
      return document;
    }
    if (path === '/addresses/validate') {
      const address = body.address ?? body;
      const problems = [];
      if (!string(address.street)) problems.push('street is required');
      if (!string(address.city)) problems.push('city is required');
      if (!/^[A-Z]{2}$/.test(address.country ?? '')) problems.push('country must be a two-letter code');
      if (problems.length) throw new ShopError(422, 'Invalid address: ' + problems.join(', '));
      return { valid: true, address };
    }
    requireValue(string(orderId), 'Expected an orderId');
    const reservation = state.reservations.find(row => row.orderId === orderId);
    switch (path) {
      case '/reservations': {
        requireValue(Array.isArray(items) && items.length && items.every(item =>
          item?.id === 'SKU-1' && Number.isSafeInteger(item.quantity) && item.quantity > 0), 'Invalid items');
        const quantity = items.reduce((sum, item) => sum + item.quantity, 0);
        if (reservation) {
          requireValue(reservation.quantity === quantity && reservation.status === 'reserved', 'Order already used', 409);
          return reservation;
        }
        requireValue(quantity <= state.stock, 'Out of stock', 409);
        const row = { id: randomUUID(), orderId, quantity, status: 'reserved' };
        if (string(body.customerId)) row.customerId = body.customerId;
        state.stock -= quantity;
        state.reservations.push(row);
        record(orderId, 'Stock reserved');
        return row;
      }
      case '/charges': {
        const token = body.paymentInfo?.token;
        requireValue(['approved', 'declined', 'review'].includes(token), 'Unknown payment token');
        requireValue(reservation?.status === 'reserved', 'Missing active reservation', 409);
        let charge = state.charges.find(row => row.orderId === orderId);
        if (!charge) {
          charge = { id: 'CH-' + (state.charges.length + 1), orderId, paid: token === 'approved', status: token, amount: reservation.quantity * PRODUCTS[0].price };
          state.charges.push(charge);
          record(orderId, { approved: 'Payment approved', declined: 'Payment declined', review: 'Payment held for review' }[token]);
        }
        requireValue(charge.status !== 'declined', 'Payment declined', 402);
        return charge;
      }
      case '/refunds': {
        const charge = state.charges.find(row => row.orderId === orderId && row.paid);
        requireValue(charge, 'No approved charge to refund', 409);
        let refund = state.refunds.find(row => row.orderId === orderId);
        if (!refund) {
          refund = { orderId, chargeId: charge.id, amount: charge.amount };
          state.refunds.push(refund);
          record(orderId, 'Payment refunded');
        }
        return refund;
      }
      case '/quotes/economy':
      case '/quotes/express': {
        const name = path.split('/').pop();
        let quote = state.quotes.find(row => row.orderId === orderId && row.carrier === name);
        if (!quote) {
          quote = { orderId, carrier: name, attempts: 0 };
          state.quotes.push(quote);
        }
        quote.attempts++;
        record(orderId, name + ' quote attempt ' + quote.attempts);
        if (name === 'economy' && body.failTwice === true && quote.attempts <= 2) {
          throw new ShopError(503, 'Carrier temporarily unavailable');
        }
        quote.price = name === 'economy' ? 5 : 9;
        return quote;
      }
      case '/shipments': {
        requireValue(reservation?.status === 'reserved' && state.charges.some(row => row.orderId === orderId && row.paid), 'Missing approved payment', 409);
        requireValue(state.quotes.some(row => row.orderId === orderId && row.carrier === carrier && row.price), 'Missing quote', 409);
        const existing = state.shipments.find(row => row.orderId === orderId);
        if (existing) {
          requireValue(existing.carrier === carrier, 'Shipment already booked', 409);
          return existing;
        }
        const shipment = { orderId, carrier };
        state.shipments.push(shipment);
        record(orderId, 'Shipment booked with ' + carrier);
        return shipment;
      }
      default: throw new ShopError(404, 'Unknown route');
    }
  };
}

function receivedHeaders(headers) {
  return Object.fromEntries(Object.entries(headers).filter(([name]) => !IGNORED_HEADERS.has(name)));
}

export function shopServer() {
  const dispatch = createShop();
  const inspect = () => dispatch('GET', '/state');
  return createServer(async (request, response) => {
    const url = new URL(request.url, 'http://shop.local');
    const traced = !(request.method === 'GET' && ['/', '/state'].includes(url.pathname)) && url.pathname !== '/reset';
    let entry;
    let status = 200;
    let payload;
    let headers = { 'Content-Type': 'application/json' };
    try {
      if (request.headers.origin) {
        requireValue(request.headers.origin === 'http://' + request.headers.host, 'Unexpected origin', 403);
      }
      if (request.method === 'GET' && url.pathname === '/') {
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(index);
        return;
      }
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        requireValue(size <= 65536, 'Request too large', 413);
        chunks.push(chunk);
      }
      const text = Buffer.concat(chunks).toString();
      if (traced) {
        entry = {
          reference: url.searchParams.get('key') ?? '', method: request.method, path: url.pathname,
          query: Object.fromEntries(url.searchParams), headers: receivedHeaders(request.headers), body: text || null, status: null,
        };
      }
      const data = size ? JSON.parse(text) : {};
      if (entry && data && typeof data === 'object') entry.reference = entry.reference || data.orderId || data.reference || '';
      const rules = controls(url.searchParams);
      if (rules.delayMs) await new Promise(resolve => setTimeout(resolve, rules.delayMs));
      const result = dispatch(request.method, request.url, data, request.headers);
      if (result instanceof Reply) {
        status = result.status;
        payload = result.body;
        headers = { ...headers, ...result.headers };
      } else {
        payload = result;
      }
    } catch (error) {
      status = error.status ?? (error instanceof SyntaxError ? 400 : 500);
      payload = { error: error.message, status };
      headers = { ...headers, ...(error.headers ?? {}) };
    }
    if (entry) {
      entry.status = status;
      const { requests } = inspect();
      requests.push(entry);
      if (requests.length > MAX_REQUESTS) requests.splice(0, requests.length - MAX_REQUESTS);
    }
    response.writeHead(status, headers).end(JSON.stringify(payload));
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  shopServer().listen(8787, process.env.SHOP_HOST ?? '127.0.0.1');
}
