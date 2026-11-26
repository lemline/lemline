"""Give the Writerside entry-point redirect the same social card as its target."""

import re
import sys
from html import escape
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit


class Metadata(HTMLParser):
    def __init__(self, page):
        super().__init__()
        self.tags = {}
        self.refresh = ""
        self.feed(page)

    def handle_starttag(self, tag, attrs):
        if tag != "meta":
            return
        attrs = dict(attrs)
        if attrs.get("http-equiv", "").lower() == "refresh":
            self.refresh = attrs["content"]
        key = attrs.get("property") or attrs.get("name", "")
        if key.startswith(("og:", "twitter:")) and attrs.get("content"):
            self.tags[key] = attrs["content"]


def check_card(site, page):
    tags = Metadata(page.read_text()).tags
    for key in ("og:title", "og:description", "og:image", "twitter:card"):
        if not tags.get(key):
            raise ValueError(f"{page.name}: missing {key}")
    origin = "https://docs.lemline.com"
    if tags.get("og:url") != f"{origin}/{page.name}":
        raise ValueError(f"{page.name}: incorrect og:url: {tags.get('og:url')}")
    image = urlsplit(tags["og:image"])
    if image.scheme != "https" or image.netloc != urlsplit(origin).netloc:
        raise ValueError(f"{page.name}: unexpected og:image: {image.geturl()}")
    if not (site / image.path.lstrip("/")).is_file():
        raise ValueError(f"{page.name}: image missing from site: {image.path}")
    return tags


def apply_web_summaries(site):
    # The pinned Writerside builder can emit empty social descriptions even
    # for Markdown topics with an explicit web-summary.
    topics = Path(__file__).resolve().parents[1] / "topics"
    for page in site.glob("*.html"):
        topic = topics / page.with_suffix(".md").name
        if not topic.exists():
            continue
        summary = re.search(r"^<web-summary>([^<]+)</web-summary>$", topic.read_text(), re.M)
        if not summary:
            continue
        description = escape(" ".join(summary[1].split()))
        html = page.read_text()
        for attr, key in (("property", "og:description"), ("name", "twitter:description")):
            tag = f'<meta {attr}="{key}" content="{description}">'
            pattern = rf'<meta {attr}="{key}"[^>]*>'
            html, count = re.subn(pattern, lambda _: tag, html, count=1)
            if not count:
                html = html.replace("</head>", tag + "</head>", 1)
        page.write_text(html)


def prepare(site):
    index = site / "index.html"
    redirect = index.read_text()
    match = re.fullmatch(r"0;\s*url=([\w.-]+\.html)", Metadata(redirect).refresh, re.I)
    if not match:
        raise ValueError("Expected Writerside's index.html to redirect to a topic")

    apply_web_summaries(site)
    tags = check_card(site, site / match[1])
    check_card(site, site / "lemline-tutorial-hello.html")
    # Writerside emits an implicit head. Insert before the refresh and retain
    # its original fallback link and script for regular browser navigation.
    head = [f"<title>{escape(tags['og:title'])}</title>"]
    for key, value in tags.items():
        attr = "property" if key.startswith("og:") else "name"
        head.append(f'<meta {attr}="{escape(key)}" content="{escape(value)}">')
    redirect, count = re.subn(r"<title>.*?</title>", lambda _: "\n".join(head),
                              redirect, flags=re.I | re.S)
    if count != 1:
        raise ValueError("Expected a single title in the entry-point redirect")
    index.write_text(redirect)


if __name__ == "__main__":
    prepare(Path(sys.argv[1]))
