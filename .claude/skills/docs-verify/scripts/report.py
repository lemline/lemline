#!/usr/bin/env python3
"""Write the verification table of a run, one row per page of the instance, in table-of-contents order.

  report.py --run build/reports/docs-verify/<run-name> --tree lemline-docs/lemline.tree [--notes notes.json] > verification.md

notes.json maps a page name (without .md) to {"level": ..., "observed": ..., "unverified": ...}: what you concluded
after reading the logs. Without a note, the row only shows what the runs produced. The Lemline version of each run is
read from the startup line of its worker log. Standard library only.
"""
import argparse
import glob
import gzip
import json
import os
import re

VERSION = re.compile(rb'lemline (\S+) on JVM')


def version(folder):
    for path in sorted(glob.glob(os.path.join(folder, 'worker*.log.gz'))):
        m = VERSION.search(gzip.open(path).read())
        if m:
            return m.group(1).decode()
    return '?'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--tree', required=True)
    parser.add_argument('--notes')
    args = parser.parse_args()
    notes = json.load(open(args.notes)) if args.notes else {}
    print(f'# Verification of {os.path.basename(args.tree)}\n')
    print('| Page | Level | Tool replay | Shell replay | Observed | Logs | Not verified / missing prerequisite |')
    print('|---|---|---|---|---|---|---|')
    for page in re.findall(r'topic="([^"]+)"', open(args.tree).read()):
        name = page[:-3]
        folder = os.path.join(args.run, name)
        tool, shell = os.path.join(folder, 'tool'), os.path.join(folder, 'shell')
        note = notes.get(name, {})
        if not os.path.isdir(folder) and not note:
            continue
        tool_text = '—'
        if os.path.exists(os.path.join(tool, 'results.json')):
            r = json.load(open(os.path.join(tool, 'results.json')))
            others = f" + {len(r['others'])} event-started" if r.get('others') else ''
            failures = len(r.get('failures', []))
            tool_text = f"{version(tool)}: {len(r.get('instances', []))} instances{others}, " + \
                        ('passed' if not failures else f'{failures} failures')
        shell_text = '—'
        if os.path.exists(os.path.join(shell, 'outcomes.txt')):
            count = sum(1 for line in open(os.path.join(shell, 'outcomes.txt')) if line.strip())
            shell_text = f'{version(shell)}: {count} outcomes'
        print(f"| {page} | {note.get('level', '—')} | {tool_text} | {shell_text} | {note.get('observed', '—')} "
              f"| `{name}/` | {note.get('unverified', '—')} |")


if __name__ == '__main__':
    main()
