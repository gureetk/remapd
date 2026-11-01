#!/usr/bin/env python3
"""Checks the website in site/ against the rules it promises.

The page says it sets no cookies and loads nothing from other sites, and its
Content-Security-Policy blocks scripts, inline styles and other origins. This
checks that the files agree, so a mistake fails CI instead of showing up as a
broken page. Standard library only.

Run from the repository root:  python3 tools/check_site.py
"""
import os
import re
import sys
from html.parser import HTMLParser

SITE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "site")
REQUIRED = [
    "index.html",
    "style.css",
    "favicon.svg",
    "apple-touch-icon.png",
    "og.png",
    "fonts/familjen-grotesk-latin-wght-normal.woff2",
    "fonts/space-mono-latin-400-normal.woff2",
    "fonts/space-mono-latin-700-normal.woff2",
    "fonts/OFL-FamiljenGrotesk.txt",
    "fonts/OFL-SpaceMono.txt",
]

errors = []


def fail(message):
    errors.append(message)


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.anchors = []   # href="#..." targets
        self.files = []     # local files the page points at
        self.has_csp = False
        self.line = 1

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        where = "index.html:%d" % self.getpos()[0]
        if "id" in a:
            self.ids.add(a["id"])
        if tag == "script":
            fail("%s: <script> is blocked by the policy and the site has no JavaScript" % where)
        if tag in ("iframe", "object", "embed", "form"):
            fail("%s: <%s> isn't allowed on this site" % (where, tag))
        if "style" in a and tag != "svg":
            fail("%s: inline style attributes are blocked by the policy (use style.css)" % where)
        if tag == "style":
            fail("%s: <style> blocks are blocked by the policy (use style.css)" % where)
        for key in ("onclick", "onload", "onerror"):
            if key in a:
                fail("%s: inline event handlers are blocked by the policy" % where)
        if tag == "meta" and a.get("http-equiv", "").lower() == "content-security-policy":
            self.has_csp = True
        if tag == "img" and "alt" not in a:
            fail("%s: <img> needs alt text" % where)
        # what the browser will fetch on load
        loads = None
        if tag == "link" and a.get("rel", "") in ("stylesheet", "icon", "apple-touch-icon", "preload"):
            loads = a.get("href")
        elif tag in ("img", "source"):
            loads = a.get("src")
        if loads:
            if re.match(r"^(https?:)?//", loads):
                fail("%s: loads %s from another site" % (where, loads))
            else:
                self.files.append(loads)
        # links the visitor clicks
        if tag == "a" and a.get("href", "").startswith("#"):
            self.anchors.append((where, a["href"][1:]))
        if tag == "a" and a.get("href") and not re.match(r"^(https?:|#|mailto:)", a["href"]):
            self.files.append(a["href"])


def read(path):
    with open(os.path.join(SITE, path), encoding="utf-8") as f:
        return f.read()


def main():
    for name in REQUIRED:
        if not os.path.isfile(os.path.join(SITE, name)):
            fail("missing site/%s" % name)
    if errors:
        return

    page = Page()
    page.feed(read("index.html"))

    if not page.has_csp:
        fail("index.html: the Content-Security-Policy meta tag is missing")
    for where, target in page.anchors:
        if target and target not in page.ids:
            fail("%s: #%s points at an id that doesn't exist" % (where, target))
    for ref in page.files:
        if not os.path.isfile(os.path.join(SITE, ref)):
            fail("index.html: %s points at a file that doesn't exist" % ref)

    css = read("style.css")
    if re.search(r"@import|url\(\s*[\"']?(https?:)?//", css):
        fail("style.css: loads something from another site")
    for ref in re.findall(r"url\(\s*[\"']?([^)\"']+)", css):
        if not ref.startswith("data:") and not os.path.isfile(os.path.join(SITE, ref)):
            fail("style.css: url(%s) points at a file that doesn't exist" % ref)

    svg = read("favicon.svg")
    if "<script" in svg or "http://" in svg.replace("http://www.w3.org/2000/svg", ""):
        fail("favicon.svg: contains a script or an outside reference")

    for root, _, names in os.walk(SITE):
        for name in names:
            if name.startswith("."):
                fail("site/%s: dotfiles are left out of the published site" % os.path.relpath(os.path.join(root, name), SITE))


main()
if errors:
    print("site check failed:")
    for e in errors:
        print("  " + e)
    sys.exit(1)
print("site check passed")
