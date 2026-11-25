#!/usr/bin/env python3
"""Check the sources of a Writerside instance: relative links, anchors and snippet inclusions.

  source_check.py lemline-docs/lemline.tree
  source_check.py lemline-docs/docs.tree

Links and code inside fenced blocks or inline code are ignored. A link to a page that exists but is not in the
instance is reported as a warning: it may not resolve in that instance's site. Exit code 1 on problems.
Run from the repository root. Standard library only.
"""
import os
import re
import sys

DOCS = 'lemline-docs'
TOPICS = os.path.join(DOCS, 'topics')
SNIPPETS = os.path.join(DOCS, 'snippets')


def anchors(path):
    text = re.sub(r'```.*?```', '', open(path).read(), flags=re.S)
    found = set(re.findall(r'id="([^"]+)"', text))
    for m in re.finditer(r'^#{1,6} (.+?)\s*(\{id="([^"]+)"\})?\s*$', text, re.M):
        heading = re.sub(r'\{[^}]*\}', '', m.group(1)).strip().lower()
        heading = re.sub(r'[`*_]', '', heading)
        heading = re.sub(r'[^\w\s-]', '', heading)
        found.add(re.sub(r'\s+', '-', heading))
    return found


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    tree = sys.argv[1]
    published = re.findall(r'topic="([^"]+)"', open(tree).read())
    problems, warnings, links, includes = [], [], 0, 0
    for page in published:
        path = os.path.join(TOPICS, page)
        if not os.path.exists(path):
            problems.append(f'{page}: listed in {os.path.basename(tree)} but missing')
            continue
        text = open(path).read()
        body = re.sub(r'`[^`\n]+`', '', re.sub(r'```.*?```', '', text, flags=re.S))
        for m in re.finditer(r'\]\(([^)\s]+)\)', body):
            url = m.group(1)
            if re.match(r'[a-z]+:', url):
                continue
            links += 1
            target, _, anchor = url.partition('#')
            target = target or page
            if not os.path.exists(os.path.join(TOPICS, target)):
                problems.append(f'{page}: missing page {url}')
                continue
            if target not in published:
                warnings.append(f'{page}: {url} is not in this instance')
            if anchor and anchor not in anchors(os.path.join(TOPICS, target)):
                problems.append(f'{page}: missing anchor {url}')
        for m in re.finditer(r'<code-block\b[^>]*\bsrc="([^"]+)"([^>]*)>', text):
            includes += 1
            snippet = os.path.join(SNIPPETS, m.group(1))
            if not os.path.exists(snippet):
                problems.append(f'{page}: missing include {m.group(1)}')
                continue
            lines = re.search(r'include-lines="(\d+)-(\d+)"', m.group(2))
            if lines and int(lines.group(2)) > len(open(snippet).read().rstrip('\n').split('\n')):
                problems.append(f'{page}: include-lines {lines.group(0)} beyond the end of {m.group(1)}')
    print(f'{len(published)} pages, {links} relative links and anchors, {includes} inclusions, '
          f'{len(problems)} problems, {len(warnings)} warnings')
    for line in problems:
        print('  ERROR ' + line)
    for line in warnings:
        print('  WARN  ' + line)
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
