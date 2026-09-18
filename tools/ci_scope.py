#!/usr/bin/env python3
"""Choose CI work conservatively. Unknown paths/history always need Android."""
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess

PREVIEW_BRANCHES = {"main", "improve-pixel-assistant"}
DOC_FILES = {"README.md", "CONTRIBUTING.md", "AGENTS.md", "LICENSE"}


def documentation_only(paths):
    return bool(paths) and all(
        p in DOC_FILES or (PurePosixPath(p).parts[0] == "docs" and
                           PurePosixPath(p).suffix.lower() in {".md", ".png", ".jpg", ".jpeg", ".svg"})
        for p in paths
    )


def decide(event_name, event, paths, repository):
    if event_name == "workflow_dispatch":
        return True, "Manual run: full Android validation; no publication."
    pr = event.get("pull_request", {})
    head = pr.get("head", {})
    if (event_name == "pull_request" and pr.get("draft") is True and
            head.get("repo", {}).get("full_name") == repository and
            head.get("ref") in PREVIEW_BRANCHES):
        return False, "Draft preview PR: branch push validates the candidate; merge validation runs when ready for review."
    if documentation_only(paths):
        return False, "Documentation only: preflight validates links and tools; installed app is unchanged."
    return True, "App, tooling, unknown path or unavailable diff: full Android validation."


def changed_paths(event_name, event):
    base = event.get("before") if event_name == "push" else event.get("pull_request", {}).get("base", {}).get("sha")
    if not base or not re.fullmatch(r"[a-f0-9]{40}", base) or base == "0" * 40:
        return None
    try:
        # No rename folding: moving an app file into docs must still build.
        data = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", "-z", base, "HEAD", "--"], stderr=subprocess.DEVNULL)
        return [p for p in data.decode().split("\0") if p]
    except (subprocess.CalledProcessError, UnicodeError):
        return None


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    name = os.environ["GITHUB_EVENT_NAME"]
    android, reason = decide(name, event, changed_paths(name, event), os.environ["GITHUB_REPOSITORY"])
    with open(os.environ["GITHUB_OUTPUT"], "a") as out:
        out.write(f"android={str(android).lower()}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
        out.write(f"## Validation scope\n\n{reason}\n")
    print(reason)


if __name__ == "__main__":
    main()
