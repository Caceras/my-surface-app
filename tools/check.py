#!/usr/bin/env python3
"""One local preflight command; stop on the first actionable failure."""
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
for command in (["tools/verify.py", "."], ["tools/test_verify.py"],
                ["tools/check_docs.py", "."], ["tools/test_release_evidence.py"]):
    subprocess.run([sys.executable, *command], cwd=root, check=True)
print("Preflight passed. Android validation: see docs/testing.md")
