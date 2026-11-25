#!/usr/bin/env python3
"""Check the site Writerside built: links and anchors in the HTML, and that each included snippet is shown in full.

  render_check.py <out-dir of writerside_build.sh> lemline-docs/<instance>.tree

Reads report.json (Writerside's own inspection) and webHelp*-all.zip. For every YAML inclusion of the instance's
pages, the rendered code block must contain the snippet (or the lines selected by include-lines). Exit code 1 on
broken links, missing anchors or mismatches. Run from the repository root. Standard library only.
"""
import glob
import html
import json
import os
import re
import sys
import zipfile

TOPICS = os.path.join('lemline-docs', 'topics')
SNIPPETS = os.path.join('lemline-docs', 'snippets')


def rendered(text):
    return re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]+>', '', text))).strip()


def source(text):
    return re.sub(r'\s+', ' ', text).strip()


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    out, tree = sys.argv[1:]
    report = json.load(open(os.path.join(out, 'report.json')))
    print(f"report.json: {report.get('testsTotal')} tests, {report.get('testsErrorsCount')} errors, "
          f"{report.get('testsWarningsCount')} warnings")
    for entry in report.get('errors', [])[:20]:
        print('  REPORT ' + json.dumps(entry)[:200])
    archives = glob.glob(os.path.join(out, 'webHelp*-all.zip'))
    if not archives:
        raise SystemExit('no webHelp*-all.zip: see writerside-build.log')
    archive = zipfile.ZipFile(archives[0])
    pages = {n: archive.read(n).decode('utf-8', 'replace') for n in archive.namelist() if n.endswith('.html')}
    ids = {n: set(re.findall(r'\bid="([^"]+)"', t)) for n, t in pages.items()}
    broken, links = [], 0
    for name, text in pages.items():
        for target, anchor in re.findall(r'<a [^>]*href="([^"#:][^"]*?)?(#[^"]*)?"', text):
            if target and (target.startswith('http') or not target.endswith('.html')):
                continue
            links += 1
            target = target or name
            if target not in pages:
                broken.append(f'{name}: missing page {target}{anchor}')
            elif anchor and anchor[1:] not in ids[target]:
                broken.append(f'{name}: missing anchor {target}{anchor}')
    print(f'{len(pages)} pages, {links} relative links and anchors, {len(broken)} broken')
    compared, mismatches = 0, []
    for page in re.findall(r'topic="([^"]+)"', open(tree).read()):
        built = pages.get(page[:-3] + '.html')
        if built is None:
            continue
        blocks = [rendered(c) for c in re.findall(r'<div class="code-block"[^>]*>(.*?)</div>', built, re.S)]
        for m in re.finditer(r'<code-block\b[^>]*\bsrc="([^"]+\.ya?ml)"([^>]*)>', open(os.path.join(TOPICS, page)).read()):
            lines = open(os.path.join(SNIPPETS, m.group(1))).read().split('\n')
            selected = re.search(r'include-lines="(\d+)-(\d+)"', m.group(2))
            if selected:
                lines = lines[int(selected.group(1)) - 1:int(selected.group(2))]
            want = source('\n'.join(lines))
            compared += 1
            if not any(want in b or (b in want and len(b) > 0.9 * len(want)) for b in blocks):
                mismatches.append(f'{page}: {m.group(1)}')
    print(f'{compared} YAML inclusions compared, {len(mismatches)} not shown as in the snippet')
    for line in broken[:30] + mismatches:
        print('  ' + line)
    sys.exit(1 if broken or mismatches else 0)


if __name__ == '__main__':
    main()
