// Run against a disposable SQLite project and a dedicated shop container.
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { once } from 'node:events';
import { accessSync, appendFileSync, closeSync, existsSync, mkdtempSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { setTimeout as pause } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

if (!process.argv[2]) throw Error('Usage: node verify.mjs /path/to/lemline.jar');
const jar = resolve(process.argv[2]);
accessSync(jar);
const composeFile = fileURLToPath(new URL('compose.yaml', import.meta.url));
const workflows = fileURLToPath(new URL('../../lemline-docs/snippets/workflows/reference/', import.meta.url));
const directory = mkdtempSync(join(tmpdir(), 'lemline-shop-test-'));
console.log('SQLite project and logs:', directory);
const env = { ...process.env, QUARKUS_HTTP_HOST_ENABLED: 'false' };

function run(program, args) {
  const log = join(directory, 'commands.log');
  appendFileSync(log, '\n' + JSON.stringify([program, ...args]) + '\n');
  try {
    const output = execFileSync(program, args, { cwd: directory, env, encoding: 'utf8', timeout: 180000 });
    appendFileSync(log, output);
    return output;
  } catch (error) {
    appendFileSync(log, String(error.stdout ?? '') + String(error.stderr ?? ''));
    throw error;
  }
}
const cli = (...args) => run('java', ['-jar', jar, ...args]);
const compose = (...args) => run('docker', ['compose', '-p', 'lemline-shop-' + process.pid, '-f', composeFile, ...args]);
async function shop(path = '/state', method = 'GET') {
  const response = await fetch('http://127.0.0.1:8787' + path, { method, signal: AbortSignal.timeout(5000) });
  assert.equal(response.status, 200);
  return response.json();
}
async function until(description, read) {
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    const value = await read();
    if (value) return value;
    await pause(300);
  }
  throw Error('Timed out waiting for ' + description);
}
let analytics;
let worker;
async function start(name, input) {
  const output = cli('instance', 'start', 'examples', name, '--input', JSON.stringify(input));
  const match = output.match(/Instance ([0-9a-f-]+) started/);
  assert.ok(match, output);
  const event = await until(name, () => analytics.prepare(
    "SELECT payload FROM lemline_lifecycle_events WHERE lemline_workflow_id = ? AND json_extract(payload,'$.data.type') IN ('workflowCompleted','workflowFaulted','workflowFailed')"
  ).get(match[1]));
  return JSON.parse(event.payload).data;
}
async function checkout(orderId, token) {
  return start('compensate-reservation', { orderId, items: [{ id: 'SKU-1', quantity: 2 }], paymentInfo: { token }, failTwice: true });
}
async function reading(deviceId, temperature) {
  const result = await start('tutorial-publisher', { type: 'com.example.device.telemetry.received', data: { deviceId, temperature } });
  assert.equal(result.type, 'workflowCompleted');
  await until('sensor workflow for ' + deviceId, () => analytics.prepare(
    "SELECT id FROM lemline_lifecycle_events WHERE lemline_workflow_name='device-telemetry' AND json_extract(payload,'$.data.type')='workflowCompleted' AND json_extract(payload,'$.data.output.deviceId')=?"
  ).get(deviceId));
}
let failure;
try {
  compose('up', '-d', '--wait');
  cli('config', 'create', '--messaging', 'sqlite', '--database', 'sqlite', '--analytics', 'sqlite', '--output', '.lemline.yaml');
  const config = join(directory, '.lemline.yaml');
  writeFileSync(config, readFileSync(config, 'utf8').replace(/^lemline:$/m, 'lemline:\n  namespaces: [default, examples, tutorial]'));
  cli('migrate', '--force');
  for (const name of ['compensate-reservation', 'device-telemetry', 'warehouse-alert', 'tutorial-publisher']) {
    cli('definition', 'post', '-f', join(workflows, name + '.yaml'));
  }
  const fd = openSync(join(directory, 'worker.log'), 'w');
  try {
    worker = spawn('java', ['-jar', jar, 'listen', '--debug'], { cwd: directory, env, stdio: ['ignore', fd, fd] });
  } finally {
    closeSync(fd);
  }
  // The worker creates and migrates analytics.db at startup; wait for its history table.
  analytics = await until('the worker to create analytics.db', () => {
    if (worker.exitCode !== null) throw Error('The worker exited early; see worker.log');
    if (!existsSync(join(directory, 'analytics.db'))) return null;
    const database = new DatabaseSync(join(directory, 'analytics.db'), { readOnly: true });
    try {
      database.exec('PRAGMA busy_timeout=5000');
      if (database.prepare("SELECT 1 FROM sqlite_master WHERE name='lemline_lifecycle_events'").get()) return database;
    } catch {
      // The file exists but is still being initialized.
    }
    database.close();
    return null;
  });
  for (const pass of [1, 2]) {
    await shop('/reset', 'POST');
    const success = await checkout('ORD-201', 'approved');
    assert.equal(success.type, 'workflowCompleted', JSON.stringify(success));
    const declined = await checkout('ORD-202', 'declined');
    assert.equal(declined.error?.title, 'Order completion failed', JSON.stringify(declined));
    const state = await shop();
    assert.equal(state.stock, 98);
    assert.deepEqual(state.reservations.map(row => [row.orderId, row.status]), [['ORD-201', 'reserved'], ['ORD-202', 'released']]);
    assert.deepEqual(state.quotes.map(row => [row.carrier, row.attempts]).sort(), [['economy', 3], ['express', 1]]);
    assert.deepEqual(state.shipments, [{ orderId: 'ORD-201', carrier: 'economy' }]);
    assert.deepEqual(state.inbox, [{ reference: 'ORD-201', recipient: 'customer', message: 'Shipment booked with economy' }]);
    await reading('cool-' + pass, 70);
    assert.equal((await shop()).inbox.length, 1);
    await reading('hot-' + pass, 90);
    const notice = await until('manager notification', async () => (await shop()).inbox.find(row => row.recipient === 'warehouse-manager'));
    assert.deepEqual(notice, { reference: 'hot-' + pass, recipient: 'warehouse-manager', message: 'High temperature: 90' });
    console.log('Verified checkout, compensation, retries and sensor alerts, pass', pass);
  }
} catch (error) {
  failure = error;
  throw error;
} finally {
  analytics?.close();
  if (worker && worker.exitCode === null && worker.signalCode === null) {
    const exited = once(worker, 'exit');
    worker.kill();
    await exited;
  }
  try {
    compose('down');
  } catch (cleanupError) {
    if (!failure) throw cleanupError;
    console.error('Compose cleanup failed:', cleanupError.message);
  }
}
