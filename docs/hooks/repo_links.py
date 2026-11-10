"""Rewrites Markdown links that leave docs/ (../SECURITY.md, ../samples/x) to the repository on GitHub.

A target that does not exist in the repository is a warning, so a strict build catches it.
"""

import logging
import os
import posixpath
import re

REPO = "https://github.com/appiaworks/shiguchi/blob/main/"
LINK = re.compile(r"(\]\()(\.\.?/[^)\s#]*)((?:#[^)\s]*)?\))")
log = logging.getLogger("mkdocs.hooks.repo_links")


def on_page_markdown(markdown, page, config, files):
    page_dir = posixpath.dirname(page.file.src_uri)
    root = os.path.dirname(os.path.abspath(config["docs_dir"]))

    def rewrite(match):
        target = posixpath.normpath(posixpath.join(page_dir, match.group(2)))
        if target != ".." and not target.startswith("../"):
            return match.group(0)
        path = target.removeprefix("../")
        if not os.path.exists(os.path.join(root, path)):
            log.warning("%s links to %s, which is not in the repository", page.file.src_uri, path)
        return match.group(1) + REPO + path + match.group(3)

    return LINK.sub(rewrite, markdown)
