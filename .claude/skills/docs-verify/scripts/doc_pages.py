#!/usr/bin/env python3
"""Inventory the pages of a Writerside instance, or find the pages that include a snippet.

  doc_pages.py lemline-docs/lemline.tree            pages in table-of-contents order, with what each one needs
  doc_pages.py lemline-docs/docs.tree --names       only the page file names, one per line
  doc_pages.py --users workflows/reference/x.yaml   every page of every instance that includes this snippet

Run from the repository root. Standard library only.
"""
import argparse
import glob
import os
import re
import sys

DOCS = 'lemline-docs'
TOPICS = os.path.join(DOCS, 'topics')
SNIPPETS = os.path.join(DOCS, 'snippets')
INCLUDE = re.compile(r'<code-block\b[^>]*\bsrc="([^"]+)"([^>]*)>')
FAKE_HOST = re.compile(r'https?://(?!127\.0\.0\.1|localhost|open-workflow-specification\.org|github\.com|events\.example\.com|errors\.example\.com|example\.com/errors)[a-z0-9.-]*\.(example|example\.com|swagger\.io|dev)\b|petstore|swapi')


def pages(tree):
    return re.findall(r'topic="([^"]+)"', open(tree).read())


def instance_of(page):
    trees = [os.path.basename(t) for t in glob.glob(os.path.join(DOCS, '*.tree')) if page in pages(t)]
    return ','.join(trees) or 'none'


def describe(page):
    path = os.path.join(TOPICS, page)
    text = open(path).read()
    includes = INCLUDE.findall(text)
    partial = sum(1 for _, attrs in includes if 'include-lines' in attrs)
    shell = len(re.findall(r'^```(?:bash|shell|sh)\s*$', text, re.M))
    fake = shop = 0
    for src, _ in includes:
        snippet = os.path.join(SNIPPETS, src)
        if not os.path.exists(snippet):
            continue
        body = open(snippet).read()
        fake += bool(FAKE_HOST.search(body))
        shop += '127.0.0.1:8787' in body
    return dict(page=page, includes=len(includes), partial=partial, shell=shell, fake=fake, shop=shop,
                runs='Run the Examples' in text or bool(re.search(r'instance start', text)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('tree', nargs='?')
    parser.add_argument('--names', action='store_true')
    parser.add_argument('--users')
    args = parser.parse_args()
    if args.users:
        target = args.users.replace(SNIPPETS + '/', '')
        for path in sorted(glob.glob(os.path.join(TOPICS, '*.md'))):
            for src, attrs in INCLUDE.findall(open(path).read()):
                if src == target:
                    lines = re.search(r'include-lines="([^"]+)"', attrs)
                    page = os.path.basename(path)
                    print(f'{page}\tinstance={instance_of(page)}' + (f'\tinclude-lines={lines.group(1)}' if lines else ''))
        return
    if not args.tree:
        parser.error('give a .tree file or --users')
    rows = [describe(p) for p in pages(args.tree)]
    if args.names:
        print('\n'.join(r['page'] for r in rows))
        return
    print('page\tincludes\tpartial\tshell\tfake-hosts\tshop\thas-commands')
    for r in rows:
        print(f"{r['page']}\t{r['includes']}\t{r['partial']}\t{r['shell']}\t{r['fake']}\t{r['shop']}\t{'yes' if r['runs'] else 'no'}")
    todo = [r['page'] for r in rows if (r['includes'] or r['shell']) and (r['partial'] or r['fake'] or not r['runs'])]
    print(f'\n{len(rows)} pages; {len(todo)} with examples that are partial, use fictitious hosts or have no run commands', file=sys.stderr)


if __name__ == '__main__':
    main()
