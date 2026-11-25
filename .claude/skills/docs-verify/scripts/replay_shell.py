#!/usr/bin/env python3
"""Second replay of a documentation page: run its shell commands in order, as a reader would copy them.

  replay_shell.py <lemline.jar> lemline-docs/topics/<page>.md --out <dir> [--pause 0] [--final 45]

Unlike examples/tutorial-services/check-page.mjs, it has no expectations and no waits beyond --pause after each
`instance start` and --final at the end. It records what happened; you compare it with the page:

  <dir>/commands.log   every command with its output and exit code
  <dir>/outcomes.txt   the "Workflow completed with output" and fault lines of the worker log
  <dir>/worker.log.gz  the worker log, with the JAR version on its startup line

Reader rules applied: the SQLite setup of the tutorial first; `definition post -f NAME` saves the workflow the page
includes under that name (else the last one shown before the command); `bin/lemline` becomes `java -jar <jar>`;
`export`/`unset` change the environment of later commands; `bin/lemline listen` (re)starts the worker in the
background on a free metrics port; the tutorial shop's `docker compose` runs from the repository root; `localhost:8080`
points to the worker's metrics port. Run from the repository root, outside any sandbox that blocks Docker.
"""
import argparse
import gzip
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time

REPO = os.getcwd()
SNIPPETS = os.path.join(REPO, 'lemline-docs', 'snippets')
COMPOSE = 'examples/tutorial-services/compose.yaml'
NAMESPACES = '  namespaces: [default, examples, tutorial]'


def free_port(start=8150):
    for port in range(start, start + 300):
        with socket.socket() as s:
            try:
                s.bind(('127.0.0.1', port))
                return port
            except OSError:
                continue
    raise SystemExit('no free port')


def parse(page):
    """Includes and shell commands, in document order. Continuations, pipes and here-documents stay one command."""
    text = open(page).read()
    items = []
    for m in re.finditer(r'<code-block\b[^>]*\bsrc="([^"]+\.ya?ml)"[^>]*>|^```(?:bash|shell|sh)\s*\n(.*?)^```', text, re.S | re.M):
        if m.group(1):
            items.append(('include', os.path.join(SNIPPETS, m.group(1))))
            continue
        pending, heredoc = '', None
        for raw in m.group(2).split('\n'):
            line = raw.rstrip()
            if heredoc:
                heredoc['lines'].append(raw)
                if line == heredoc['end']:
                    items.append(('command', '\n'.join(heredoc['lines'])))
                    heredoc = None
                continue
            marker = re.search(r"<<-?\s*'?([A-Za-z_]+)'?\s*$", line)
            if marker:
                heredoc = {'end': marker.group(1), 'lines': [pending + line]}
                pending = ''
                continue
            if line.endswith('\\'):
                pending += line[:-1] + ' '
                continue
            if line.endswith('|') or line.endswith('&&'):
                pending += line + ' '
                continue
            command = (pending + line).strip()
            pending = ''
            if command and not command.startswith('#'):
                items.append(('command', command))
    return items


class Replay:
    def __init__(self, jar, out, pause):
        self.jar, self.out, self.pause = jar, out, pause
        self.env = dict(os.environ)
        self.port = free_port()
        self.project = tempfile.mkdtemp(prefix='lemline-replay-')
        self.compose = f'replay-{os.getpid()}'
        self.worker = None
        self.shop = False
        self.log = open(os.path.join(out, 'commands.log'), 'w')
        self.worker_log = open(os.path.join(out, 'worker.log'), 'ab')

    def note(self, text):
        self.log.write(text + '\n')
        self.log.flush()

    def run(self, command, cwd=None):
        self.note(f'\n$ {command}' + (f'   (in {cwd})' if cwd else ''))
        p = subprocess.run(['/bin/bash', '-c', command], cwd=cwd or self.project, env=self.env,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600)
        self.note(p.stdout.rstrip() + f'\n[exit {p.returncode}]')
        return p

    def start_worker(self, extra=''):
        self.stop_worker()
        args = extra.replace('--debug', '').strip()
        command = f"exec java -jar '{self.jar}' listen --debug --metrics-port {self.port} {args}"
        self.note(f'\n$ bin/lemline listen --debug {args}   (background, metrics port {self.port})')
        self.worker = subprocess.Popen(['/bin/bash', '-c', command], cwd=self.project, env=dict(self.env),
                                       stdout=self.worker_log, stderr=subprocess.STDOUT, start_new_session=True)
        time.sleep(8)

    def stop_worker(self):
        if self.worker and self.worker.poll() is None:
            os.killpg(self.worker.pid, signal.SIGTERM)
            try:
                self.worker.wait(30)
            except subprocess.TimeoutExpired:
                os.killpg(self.worker.pid, signal.SIGKILL)
            self.note('[worker stopped]')
        self.worker = None

    def execute(self, command):
        if re.match(r'(mkdir|cd) ', command) and 'lemline-project' in command:
            return self.note(f'\n# setup directory: {command}')
        exported = re.match(r'export ([A-Za-z_][A-Za-z0-9_]*)=', command)
        if exported:
            # Let bash read the value: quotes and trailing comments are handled as in the reader's shell.
            name = exported.group(1)
            p = subprocess.run(['/bin/bash', '-c', f'{command}\nprintf %s "${name}"'], env=self.env, capture_output=True, text=True)
            self.env[name] = p.stdout
            return self.note(f'\n$ {command}')
        unset = re.match(r'unset ([A-Za-z_][A-Za-z0-9_]*)$', command)
        if unset:
            self.env.pop(unset.group(1), None)
            return self.note(f'\n$ {command}')
        line = re.sub(r'^(?:bin/lemline|\./bin/lemline|lemline|java -jar lemline\.jar)(?=\s)', f"java -jar '{self.jar}'", command)
        line = line.replace('localhost:8080', f'localhost:{self.port}')
        listen = re.match(rf"java -jar '{re.escape(self.jar)}' listen(.*)$", line)
        if listen:
            return self.start_worker(listen.group(1))
        if line.startswith('docker compose') and COMPOSE in line:
            line = line.replace('docker compose', f'docker compose -p {self.compose}', 1)
            if re.search(r'\bup\b', line) and '--wait' not in line:
                line += ' --wait'
            p = self.run(line, cwd=REPO)
            self.shop = p.returncode == 0 and bool(re.search(r'\bup\b', line))
            return
        p = self.run(line)
        if 'instance start' in line and self.pause:
            time.sleep(self.pause)

    def finish(self, final):
        time.sleep(final)
        self.stop_worker()
        if self.shop:
            self.run(f'docker compose -p {self.compose} -f {COMPOSE} down', cwd=REPO)
        self.worker_log.close()
        path = os.path.join(self.out, 'worker.log')
        text = re.sub(r'\x1b\[[0-9;]*m', '', open(path, errors='replace').read())
        seen, outcomes = set(), []
        # Completions print their output; faults print "Failure <id> at position <task>: Error(type=…, status=…, title=…".
        pattern = (r'\[([a-z0-9-]+/[a-z0-9-]+:[^\]]+)\] \[([^\]]+)\] \[[^\]]+\] '
                   r'(?:(Workflow completed with output.{0,200})|Failure \S+ at position (\S+): Error\(type=\S*/errors/(\w+), '
                   r'status=(\d+), .*?title=(.{0,120}))')
        for m in re.finditer(pattern, text):
            outcome = m.group(3) or f'Workflow faulted at {m.group(4)}: {m.group(5)} {m.group(6)} {m.group(7)}'
            entry = f'{m.group(1)} {m.group(2)}  {outcome}'
            if entry not in seen:
                seen.add(entry)
                outcomes.append(entry)
        open(os.path.join(self.out, 'outcomes.txt'), 'w').write('\n'.join(outcomes) + '\n')
        with open(path, 'rb') as src, gzip.open(path + '.gz', 'wb') as dst:
            shutil.copyfileobj(src, dst)
        os.remove(path)
        shutil.rmtree(self.project, ignore_errors=True)
        self.log.close()
        return outcomes


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('jar')
    parser.add_argument('page')
    parser.add_argument('--out', required=True)
    parser.add_argument('--pause', type=float, default=0, help='seconds to wait after each instance start')
    parser.add_argument('--final', type=float, default=45, help='seconds to wait before stopping')
    args = parser.parse_args()
    jar = os.path.abspath(args.jar)
    os.makedirs(args.out, exist_ok=True)
    replay = Replay(jar, os.path.abspath(args.out), args.pause)
    try:
        replay.run(f"java -jar '{jar}' config create --messaging sqlite --database sqlite --analytics sqlite --output .lemline.yaml")
        config = os.path.join(replay.project, '.lemline.yaml')
        text = open(config).read()
        open(config, 'w').write(re.sub(r'^lemline:$', 'lemline:\n' + NAMESPACES, text, flags=re.M))
        replay.note(f'\n# added {NAMESPACES.strip()} below lemline:')
        replay.run(f"java -jar '{jar}' migrate --force")
        replay.start_worker()
        items = parse(args.page)
        includes = {os.path.basename(path): path for kind, path in items if kind == 'include'}
        last = None
        for kind, value in items:
            if kind == 'include':
                last = value
                continue
            posted = re.search(r'definition post (?:-f|--file) (\S+)', value)
            if posted:
                source = includes.get(os.path.basename(posted.group(1)), last)
                if source:
                    shutil.copy(source, os.path.join(replay.project, posted.group(1)))
                    replay.note(f'\n# saved {os.path.relpath(source, SNIPPETS)} as {posted.group(1)}')
            replay.execute(value)
    finally:
        outcomes = replay.finish(args.final)
    print(f'{os.path.basename(args.page)}: {len(outcomes)} outcomes  ({args.out})')
    for line in outcomes:
        print('  ' + line[:200])


if __name__ == '__main__':
    main()
