// Replay the shell commands of one documentation page against a fresh SQLite project.
//
//   node check-page.mjs <lemline.jar> <page.md> [--out <dir>] [--expect <file.json>]
//
// The page is followed as a reader would: the SQLite setup of the tutorial, then every ```bash block in order.
// `definition post -f NAME.yaml` saves the workflow the page includes under that file name first.
// Expectations default to expected/<page>.json next to this script. See README.md.
import { execFileSync, spawn } from 'node:child_process';
import { once } from 'node:events';
import {
  closeSync, copyFileSync, createReadStream, createWriteStream, existsSync, mkdirSync, mkdtempSync, openSync,
  readFileSync, rmSync, writeFileSync, appendFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { setTimeout as pause } from 'node:timers/promises';
import { pipeline } from 'node:stream/promises';
import { createGzip } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repository = resolve(here, '../..');
const snippets = join(repository, 'lemline-docs/snippets');
const args = process.argv.slice(2);
function option(name) {
  const at = args.indexOf(name);
  if (at < 0) return undefined;
  const [, value] = args.splice(at, 2);
  return value;
}
const outOption = option('--out');
const expectOption = option('--expect');
if (args.length !== 2) throw Error('Usage: node check-page.mjs <lemline.jar> <page.md> [--out dir] [--expect file.json]');
const jar = resolve(args[0]);
const page = resolve(args[1]);
const pageName = basename(page, '.md');
const expectFile = expectOption ? resolve(expectOption) : join(here, 'expected', pageName + '.json');
const expected = existsSync(expectFile) ? JSON.parse(readFileSync(expectFile, 'utf8')) : {};
const out = resolve(outOption ?? join(tmpdir(), 'lemline-page-' + pageName + '-' + Date.now()));
mkdirSync(out, { recursive: true });
const project = mkdtempSync(join(tmpdir(), 'lemline-page-project-'));
const log = join(out, 'commands.log');
writeFileSync(log, `page ${page}\nproject ${project}\njar ${jar}\n`);
const env = { ...process.env, QUARKUS_HTTP_HOST_ENABLED: 'false' };
const composeProject = 'lemline-docs-check-' + process.pid;
const metricsPort = String(expected.metricsPort ?? 8090 + (process.pid % 50));
const settleSeconds = expected.settleSeconds ?? 20;
const timeoutSeconds = expected.timeoutSeconds ?? 240;
const skip = (expected.skip ?? []).map(pattern => new RegExp(pattern));
const allowFailure = (expected.allowFailure ?? []).map(pattern => new RegExp(pattern));

function note(text) {
  appendFileSync(log, text + '\n');
}

// ---- Parse the page: included workflows and bash commands, in document order.
const markdown = readFileSync(page, 'utf8');
const includes = new Map();
const stream = [];
for (const match of markdown.matchAll(/<code-block\b[^>]*\bsrc="([^"]+\.ya?ml)"[^>]*>|^```(?:bash|shell|sh)\n([\s\S]*?)^```/gm)) {
  if (match[1]) {
    const source = join(snippets, match[1]);
    const name = basename(source);
    if (includes.has(name) && includes.get(name) !== source) note(`warning: two included files are named ${name}`);
    includes.set(name, source);
    stream.push({ include: source });
    continue;
  }
  let pending = '';
  for (const raw of match[2].split('\n')) {
    const line = raw.trimEnd();
    if (line.endsWith('\\')) {
      pending += line.slice(0, -1) + ' ';
      continue;
    }
    const command = (pending + line).trim();
    pending = '';
    if (command && !command.startsWith('#')) stream.push({ command });
  }
}
const commands = stream.filter(item => item.command).map(item => item.command);
/** The file a reader saves before `definition post -f name`: the include of that name, else the last one shown before. */
function sourceFor(name, position) {
  if (includes.has(basename(name))) return includes.get(basename(name));
  for (let index = position; index >= 0; index--) if (stream[index].include) return stream[index].include;
  return undefined;
}

// ---- Processes.
let worker;
let workerCount = 0;
let analytics;
let shopUp = false;
let lastState;
const started = [];
const failures = [];

function shellQuote(value) {
  return "'" + value.replaceAll("'", "'\\''") + "'";
}
function run(command, { cwd = project, check = true } = {}) {
  note('\n$ ' + command + (cwd === project ? '' : `   (in ${cwd})`));
  try {
    const output = execFileSync('/bin/sh', ['-c', command], { cwd, env, encoding: 'utf8', timeout: 300000, stdio: ['ignore', 'pipe', 'pipe'] });
    note(output.trimEnd());
    return { ok: true, output };
  } catch (error) {
    const output = String(error.stdout ?? '') + String(error.stderr ?? '');
    note(output.trimEnd() + `\n[exit ${error.status ?? error.signal}]`);
    if (check) failures.push(`command failed: ${command}`);
    return { ok: false, output };
  }
}
const cli = `java -jar ${shellQuote(jar)}`;
async function startWorker(extra = '') {
  await stopWorker();
  workerCount++;
  const file = join(out, `worker-${workerCount}.log`);
  const fd = openSync(file, 'w');
  const flags = /--metrics-port/.test(extra) ? extra : `${extra} --metrics-port ${metricsPort}`;
  note(`\n$ ${cli} listen ${flags.trim()}   (background, worker-${workerCount}.log)`);
  try {
    worker = spawn('/bin/sh', ['-c', `exec ${cli} listen ${flags}`], { cwd: project, env: { ...env }, stdio: ['ignore', fd, fd] });
  } finally {
    closeSync(fd);
  }
  analytics ??= await until('the worker to create analytics.db', 120, () => {
    if (worker.exitCode !== null) throw Error(`The worker exited early; see worker-${workerCount}.log`);
    if (!existsSync(join(project, 'analytics.db'))) return null;
    const database = new DatabaseSync(join(project, 'analytics.db'), { readOnly: true });
    try {
      database.exec('PRAGMA busy_timeout=5000');
      if (database.prepare("SELECT 1 FROM sqlite_master WHERE name='lemline_lifecycle_events'").get()) return database;
    } catch {
      // Still being created.
    }
    database.close();
    return null;
  });
}
async function stopWorker() {
  if (!worker || worker.exitCode !== null || worker.signalCode !== null) return;
  const exited = once(worker, 'exit');
  worker.kill('SIGTERM');
  await Promise.race([exited, pause(30000)]);
  if (worker.exitCode === null && worker.signalCode === null) worker.kill('SIGKILL');
  note(`[worker-${workerCount} stopped]`);
}
async function until(description, seconds, read) {
  const deadline = Date.now() + seconds * 1000;
  while (Date.now() < deadline) {
    const value = await read();
    if (value) return value;
    await pause(300);
  }
  throw Error('Timed out waiting for ' + description);
}
function terminal(id) {
  if (!analytics) return undefined;
  const row = analytics.prepare(
    "SELECT payload FROM lemline_lifecycle_events WHERE lemline_workflow_id = ? AND json_extract(payload,'$.data.type') IN ('workflowCompleted','workflowFaulted','workflowFailed','workflowCancelled') ORDER BY id DESC LIMIT 1",
  ).get(id);
  return row ? JSON.parse(row.payload).data : undefined;
}
async function snapshot() {
  if (!shopUp) return;
  try {
    const response = await fetch('http://127.0.0.1:8787/state', { signal: AbortSignal.timeout(3000) });
    lastState = await response.json();
  } catch {
    // The shop was stopped by the page.
  }
}

// ---- Expectation matching: objects are partial, arrays are exact in length.
function matches(actual, wanted, path = '$') {
  if (wanted !== null && typeof wanted === 'object' && !Array.isArray(wanted)) {
    if (actual === null || typeof actual !== 'object' || Array.isArray(actual)) return [`${path}: expected an object, got ${JSON.stringify(actual)}`];
    return Object.entries(wanted).flatMap(([key, value]) => matches(actual[key], value, `${path}.${key}`));
  }
  if (Array.isArray(wanted)) {
    if (!Array.isArray(actual) || actual.length !== wanted.length) return [`${path}: expected ${wanted.length} items, got ${JSON.stringify(actual)}`];
    return wanted.flatMap((value, index) => matches(actual[index], value, `${path}[${index}]`));
  }
  if (typeof wanted === 'string' && wanted.startsWith('~')) {
    return new RegExp(wanted.slice(1)).test(String(actual)) ? [] : [`${path}: ${JSON.stringify(actual)} does not match /${wanted.slice(1)}/`];
  }
  return actual === wanted ? [] : [`${path}: expected ${JSON.stringify(wanted)}, got ${JSON.stringify(actual)}`];
}

async function execute(command) {
  if (skip.some(pattern => pattern.test(command))) {
    note('\n# skipped by expectations: ' + command);
    return;
  }
  if (/^(mkdir|cd) /.test(command) && /lemline-project/.test(command)) return note('\n# setup directory: ' + command);
  const exported = command.match(/^export ([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
  if (exported) {
    env[exported[1]] = execFileSync('/bin/sh', ['-c', `printf %s ${exported[2]}`], { encoding: 'utf8' });
    return note(`\n$ ${command}`);
  }
  let line = command.replace(/^(?:bin\/lemline|\.\/bin\/lemline|lemline|java -jar lemline\.jar)(?=\s)/, cli);
  if (line.startsWith(cli + ' listen')) return startWorker(line.slice((cli + ' listen').length));
  if (/^docker compose\b/.test(line) && /examples\/tutorial-services\/compose\.yaml/.test(line)) {
    line = line.replace(/^docker compose/, `docker compose -p ${composeProject}`);
    if (/\bup\b/.test(line) && !/--wait/.test(line)) line += ' --wait';
    if (/\bdown\b/.test(line)) {
      // The reader checks the shop before stopping it: let running and event-started instances finish first.
      await until('instances to finish before the shop stops', timeoutSeconds, () =>
        started.every(record => terminal(record.id)) && others().length >= (expected.others?.length ?? 0)).catch(error => note('# ' + error.message));
      await snapshot();
    }
    const result = run(line, { cwd: repository, check: !allowFailure.some(pattern => pattern.test(command)) });
    shopUp = result.ok && /\bup\b/.test(line);
    if (shopUp) await snapshot();
    return;
  }
  const result = run(line, { check: !allowFailure.some(pattern => pattern.test(command)) });
  const instance = result.output.match(/Instance ([0-9a-f-]{36}) started/);
  if (instance) {
    const record = { index: started.length, command, id: instance[1] };
    started.push(record);
    try {
      await until('instance ' + record.id, settleSeconds, () => terminal(record.id));
    } catch {
      note(`# instance ${record.id} still running after ${settleSeconds}s; continuing`);
    }
  }
  await snapshot();
}

process.on('exit', () => { if (worker && worker.exitCode === null) worker.kill('SIGTERM'); });
let crash;
try {
  run(`${cli} config create --messaging sqlite --database sqlite --analytics sqlite --output .lemline.yaml`);
  const config = join(project, '.lemline.yaml');
  writeFileSync(config, readFileSync(config, 'utf8').replace(/^lemline:$/m, 'lemline:\n  namespaces: [default, examples, tutorial]'));
  note('\n# added namespaces: [default, examples, tutorial] below lemline:');
  run(`${cli} migrate --force`);
  // "Keep the worker running": the page's own `listen` command restarts it.
  if (!expected.noInitialWorker) await startWorker();
  for (const [position, item] of stream.entries()) {
    if (!item.command) continue;
    const posted = item.command.match(/definition post -f (\S+)/);
    if (posted) {
      const source = sourceFor(posted[1], position);
      if (source) {
        copyFileSync(source, join(project, posted[1]));
        note(`\n# saved ${source.slice(snippets.length + 1)} as ${posted[1]}`);
      } else failures.push(`the page posts ${posted[1]} but shows no workflow before it`);
    }
    await execute(item.command);
  }
  await until('every started instance to finish', timeoutSeconds, () => started.every(record => terminal(record.id))).catch(error => note('# ' + error.message));
  if (expected.others) await until('the event-started instances', timeoutSeconds, () => others().length >= expected.others.length).catch(error => note('# ' + error.message));
  await snapshot();
} catch (error) {
  crash = error;
  failures.push('runner error: ' + error.message);
} finally {
  await snapshot().catch(() => {});
}

function others() {
  if (!analytics) return [];
  const ids = new Set(started.map(record => record.id));
  return analytics.prepare(
    "SELECT lemline_workflow_id AS id, payload FROM lemline_lifecycle_events WHERE json_extract(payload,'$.data.type') IN ('workflowCompleted','workflowFaulted','workflowFailed','workflowCancelled') ORDER BY id",
  ).all().filter(row => !ids.has(row.id)).map(row => ({ id: row.id, ...pick(JSON.parse(row.payload).data) }));
}
function pick(data) {
  return data ? { workflow: data.name, type: data.type, output: data.output, error: data.error } : { type: 'running' };
}
const results = {
  page: basename(page),
  jar,
  instances: started.map(record => ({ ...record, ...pick(terminal(record.id)) })),
  others: others(),
  state: lastState,
};
analytics?.close();
await stopWorker();
if (shopUp) run(`docker compose -p ${composeProject} -f examples/tutorial-services/compose.yaml down`, { cwd: repository, check: false });

(expected.instances ?? []).forEach((wanted, index) => {
  if (wanted === null) return;
  const actual = results.instances[index];
  if (!actual) return failures.push(`instance ${index}: never started`);
  for (const problem of matches(actual, wanted, `instance ${index} (${actual.command})`)) failures.push(problem);
});
(expected.others ?? []).forEach((wanted, index) => {
  for (const problem of matches(results.others[index], wanted, `event-started instance ${index}`)) failures.push(problem);
});
if (expected.state) for (const problem of matches(results.state, expected.state, 'shop state')) failures.push(problem);
if (!expected.instances) {
  for (const record of results.instances) if (record.type !== 'workflowCompleted') failures.push(`instance ${record.index} ended with ${record.type}: ${record.command}`);
}
results.failures = failures;
writeFileSync(join(out, 'results.json'), JSON.stringify(results, null, 2));
for (let index = 1; index <= workerCount; index++) {
  const file = join(out, `worker-${index}.log`);
  await pipeline(createReadStream(file), createGzip(), createWriteStream(file + '.gz'));
  rmSync(file);
}
rmSync(project, { recursive: true, force: true });
console.log(`${pageName}: ${results.instances.length} instances, ${results.others.length} event-started, ${failures.length ? 'FAILED' : 'passed'}  (${out})`);
for (const problem of failures) console.log('  - ' + problem);
if (crash) console.error(crash);
process.exitCode = failures.length ? 1 : 0;
