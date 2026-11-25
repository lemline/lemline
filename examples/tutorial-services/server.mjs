import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const index = readFileSync(new URL('./index.html', import.meta.url));
class ShopError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
function requireValue(condition, message, status = 400) {
  if (!condition) throw new ShopError(status, message);
}
function string(value) {
  return typeof value === 'string' && value.trim().length > 0;
}
function initialState() {
  return { stock: 100, reservations: [], charges: [], quotes: [], shipments: [], inbox: [], history: [] };
}

export function createShop() {
  let state = initialState();
  function record(reference, action) {
    state.history.push({ reference, action });
  }
  return function dispatch(method, path, body = {}) {
    if (method === 'GET' && path === '/state') return state;
    if (method === 'POST' && path === '/reset') {
      state = initialState();
      return state;
    }
    if (method === 'DELETE' && path.startsWith('/reservations/')) {
      const reservation = state.reservations.find(row => row.id === path.slice('/reservations/'.length));
      requireValue(reservation, 'Unknown reservation', 404);
      requireValue(!state.shipments.some(row => row.orderId === reservation.orderId), 'Order already shipped', 409);
      if (reservation.status === 'reserved') {
        reservation.status = 'released';
        state.stock += reservation.quantity;
        record(reservation.orderId, 'Stock released');
      }
      return reservation;
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
        state.stock -= quantity;
        state.reservations.push(row);
        record(orderId, 'Stock reserved');
        return row;
      }
      case '/charges': {
        const token = body.paymentInfo?.token;
        requireValue(['approved', 'declined'].includes(token), 'Unknown payment token');
        requireValue(reservation?.status === 'reserved', 'Missing active reservation', 409);
        let charge = state.charges.find(row => row.orderId === orderId);
        if (!charge) {
          charge = { orderId, paid: token === 'approved' };
          state.charges.push(charge);
          record(orderId, charge.paid ? 'Payment approved' : 'Payment declined');
        }
        requireValue(charge.paid, 'Payment declined', 402);
        return charge;
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

export function shopServer() {
  const dispatch = createShop();
  return createServer(async (request, response) => {
    try {
      if (request.headers.origin) {
        requireValue(request.headers.origin === 'http://' + request.headers.host, 'Unexpected origin', 403);
      }
      if (request.method === 'GET' && request.url === '/') {
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
      const data = size ? JSON.parse(Buffer.concat(chunks).toString()) : {};
      const result = dispatch(request.method, request.url, data);
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
    } catch (error) {
      const status = error.status ?? (error instanceof SyntaxError ? 400 : 500);
      response.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: error.message }));
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  shopServer().listen(8787, process.env.SHOP_HOST ?? '127.0.0.1');
}
