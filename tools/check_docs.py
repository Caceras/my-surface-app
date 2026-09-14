#!/usr/bin/env python3
"""Check local Markdown links without fetching external sites or needing dependencies."""
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
files = [root / "README.md", root / "CONTRIBUTING.md", *sorted((root / "docs").glob("*.md"))]
errors = []
for path in files:
    source = re.sub(r"```.*?```", "", path.read_text(), flags=re.S)
    links = re.findall(r"!?\[[^\]]*\]\(([^\s)]+)(?:\s+[^)]*)?\)", source)
    links += re.findall(r'(?:src|href)="([^"]+)"', source)
    for raw in links:
        link = urlsplit(raw)
        if link.scheme or link.netloc or raw.startswith("//"):
            continue
        target = (path.parent / unquote(link.path)).resolve() if link.path else path
        if not target.exists():
            errors.append(f"{path.relative_to(root)}: missing {raw}")
        elif link.fragment and target.suffix == ".md":
            headings = re.findall(r"^#{1,6}\s+(.+?)\s*#*\s*$", target.read_text(), flags=re.M)
            anchors = {re.sub(r"[^\w\- ]", "", h.lower()).replace(" ", "-") for h in headings}
            if unquote(link.fragment) not in anchors:
                errors.append(f"{path.relative_to(root)}: missing heading {raw}")
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"Checked local links in {len(files)} documentation files.")
