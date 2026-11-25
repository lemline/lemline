#!/usr/bin/env python3
"""Replay pages twice each, one after the other: with the checking tool, then as a reader copying the commands.

  run_pages.py <lemline.jar> --run build/reports/docs-verify/<run-name> <page.md> [<page.md> ...]
  run_pages.py <lemline.jar> --run DIR --tree lemline-docs/docs.tree      every page that has commands
  run_pages.py ... --only tool | --only shell                             one replay only

Per page, <run>/<page>/tool/ comes from examples/tutorial-services/check-page.mjs (results.json, commands.log,
worker-N.log.gz) and <run>/<page>/shell/ from replay_shell.py (commands.log, outcomes.txt, worker.log.gz).
<run>/summary.tsv gets one line per page. Pages run sequentially: they share the tutorial shop's port 8787.
Needs Docker, Node 22.5+ and Java 17+; run from the repository root outside any sandbox that blocks Docker.
"""
import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join('examples', 'tutorial-services', 'check-page.mjs')


def pages_with_commands(tree):
    for page in re.findall(r'topic="([^"]+)"', open(tree).read()):
        text = open(os.path.join('lemline-docs', 'topics', page)).read()
        if re.search(r'^```(?:bash|shell|sh)\s*$', text, re.M) and 'instance start' in text:
            yield os.path.join('lemline-docs', 'topics', page)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('jar')
    parser.add_argument('pages', nargs='*')
    parser.add_argument('--run', required=True)
    parser.add_argument('--tree')
    parser.add_argument('--only', choices=['tool', 'shell'])
    parser.add_argument('--pause', default='0', help='passed to replay_shell.py')
    args = parser.parse_args()
    if not args.pages and not args.tree:
        parser.error('give pages or --tree')
    pages = args.pages or list(pages_with_commands(args.tree))
    if not pages:
        raise SystemExit(f'no page of {args.tree} has commands to replay: make them runnable first (docs-update skill)')
    os.makedirs(args.run, exist_ok=True)
    summary = open(os.path.join(args.run, 'summary.tsv'), 'a')
    for page in pages:
        name = os.path.basename(page)[:-3]
        folder = os.path.join(args.run, name)
        tool = shell = '-'
        if args.only != 'shell':
            p = subprocess.run(['node', CHECK, args.jar, page, '--out', os.path.join(folder, 'tool')])
            results = os.path.join(folder, 'tool', 'results.json')
            if os.path.exists(results):
                r = json.load(open(results))
                tool = f"{len(r.get('instances', []))} instances, {len(r.get('failures', []))} failures"
            else:
                tool = f'no results (exit {p.returncode})'
        if args.only != 'tool':
            subprocess.run([sys.executable, os.path.join(HERE, 'replay_shell.py'), args.jar, page,
                            '--out', os.path.join(folder, 'shell'), '--pause', args.pause])
            outcomes = os.path.join(folder, 'shell', 'outcomes.txt')
            shell = f"{sum(1 for l in open(outcomes) if l.strip())} outcomes" if os.path.exists(outcomes) else 'no outcomes'
        summary.write(f'{name}\t{tool}\t{shell}\n')
        summary.flush()
        print(f'== {name}: tool {tool}; shell {shell}', flush=True)


if __name__ == '__main__':
    main()
