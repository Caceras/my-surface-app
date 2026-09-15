#!/usr/bin/env python3
"""Publish verified build-specific downloads, then refresh compatibility aliases.

Uses the authenticated gh CLI with argument arrays; never interpolates branch
names into shell code. Unique build releases are the durable handoff/rollback.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile
from release_evidence import digest, verify


def gh(*args, input_data=None):
    return subprocess.check_output(["gh", *args], input=input_data, text=True).strip()


def release(repo, tag):
    result = subprocess.run(["gh", "release", "view", tag, "--repo", repo, "--json", "isDraft,targetCommitish"], capture_output=True, text=True)
    if result.returncode:
        # Distinguish a missing release from auth/network errors before creating.
        if "not found" not in result.stderr.lower():
            raise RuntimeError(result.stderr)
        return None
    return json.loads(result.stdout)


def verify_download(repo, tag, assets):
    with tempfile.TemporaryDirectory() as tmp:
        for path in assets:
            gh("release", "download", tag, "--repo", repo, "--pattern", path.name, "--dir", tmp)
            if digest(Path(tmp) / path.name) != digest(path):
                raise ValueError(f"Published download mismatch: {path.name}")


def current_head(repo, branch, sha):
    return json.loads(gh("api", f"repos/{repo}/git/ref/heads/{branch}"))["object"]["sha"] == sha


def report_download(meta, url, rolling_updated):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
        out.write(f"\n## Verified download\n\n[Install Ægentica AI build {meta['build']}]({url})\n\n"
                  "Build assets were downloaded and compared by SHA-256. Build-specific release retained for rollback.\n"
                  + ("Rolling assets also verified.\n" if rolling_updated else "Branch advanced; rolling preview left unchanged.\n"))
    print(url)


def main():
    folder = Path("dist/evidence")
    meta = verify(folder)
    repo, sha, branch = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"], os.environ["GITHUB_REF_NAME"]
    if meta["source_sha"] != sha or meta["repository"] != repo or meta["run_id"] != os.environ["GITHUB_RUN_ID"]:
        raise ValueError("Build provenance does not match publication")
    if meta["build"] != int(os.environ["GITHUB_RUN_NUMBER"]) or meta["attempt"] != int(os.environ["GITHUB_RUN_ATTEMPT"]):
        raise ValueError("Build attempt does not match publication")
    if not current_head(repo, branch, sha):
        print("Branch advanced; preserving the newer preview. This run's evidence remains in Actions.")
        return
    slug = re.sub(r"[^a-z0-9._-]+", "-", branch.lower()).strip("-")
    rolling = "debug-latest" if branch == "main" else f"preview-{slug}"
    suffix = f"-r{meta['attempt']}" if meta["attempt"] > 1 else ""
    tag = f"{rolling}-build-{meta['build']}{suffix}"
    # The bundle includes the exact reports/screenshots/APKs, not a summary only.
    bundle = folder.parent / "aegentica-evidence.zip"
    with zipfile.ZipFile(bundle, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(folder.rglob("*")):
            if path.is_file():
                info = zipfile.ZipInfo(str(path.relative_to(folder)), date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, path.read_bytes())
    assets = [*sorted(folder.glob("*.apk")), folder / "release-evidence.json", folder / "SHA256SUMS", bundle]
    url = f"https://github.com/{repo}/releases/download/{tag}/aegentica-ai-nano.apk"
    notes = folder.parent / "release-notes.md"
    changes = (Path(__file__).resolve().parents[1] / "docs/preview-notes.md").read_text().replace("# Current preview changes", "## What changed", 1)
    notes.write_text(f"""Ægentica AI **3.0.{meta['build']}-nano** · Æ signum · sky-blue native Android assistant.

[Download Nano for your Pixel]({url})

{changes}

Source: `{sha}` · [CI evidence](https://github.com/{repo}/actions/runs/{meta['run_id']}).
JVM: **{meta['tests']['tests']} passed**, no skipped tests. Both flavor APKs and Android lint passed.
Download `aegentica-evidence.zip` and open `review.html` for native core-fixture screenshots; these are not real Nano responses or physical-device certification.

Open Settings and verify **3.0.{meta['build']}-nano**. Prepare the model, then use **Set up voice & test playback**.
If Android reports a signature conflict, export your conversations before any uninstall/reinstall, then restore. Persistent signing secrets are owner setup.

[Phone setup](https://github.com/{repo}/blob/{sha}/docs/getting-started.md) · [Workflow audit](https://github.com/{repo}/blob/{sha}/docs/iteration-workflow.md).
Legacy pixel-surface-lab APK names are identical aliases. This is a personal preview, not a Play Store release.
""")
    existing = release(repo, tag)
    if existing and existing["targetCommitish"] != sha:
        raise ValueError("Build release points to a different commit")
    if not existing:
        gh("release", "create", tag, "--repo", repo, "--target", sha, "--draft", "--prerelease", "--latest=false",
           "--title", f"Ægentica AI · build {meta['build']}{suffix}", "--notes-file", str(notes), *map(str, assets))
    elif existing["isDraft"]:
        gh("release", "upload", tag, "--repo", repo, "--clobber", *map(str, assets))
    # A retry must never silently replace a previously published build.
    verify_download(repo, tag, assets)
    gh("release", "edit", tag, "--repo", repo, "--draft=false")
    # A newer push may arrive during upload. Keep the verified unique download,
    # but do not promote an obsolete candidate to the convenient rolling alias.
    if not current_head(repo, branch, sha):
        report_download(meta, url, False)
        return
    existing = release(repo, rolling)
    if not existing:
        gh("release", "create", rolling, "--repo", repo, "--target", sha, *( ["--latest=true"] if branch == "main" else ["--prerelease", "--latest=false"] ),
           "--title", f"Ægentica AI preview · build {meta['build']}", "--notes-file", str(notes), *map(str, assets))
    else:
        gh("release", "upload", rolling, "--repo", repo, "--clobber", *map(str, assets))
        gh("api", f"repos/{repo}/git/refs/tags/{rolling}", "--method", "PATCH", "--input", "-",
           input_data=json.dumps(dict(sha=sha, force=True)))
        gh("release", "edit", rolling, "--repo", repo, "--target", sha,
           "--title", f"Ægentica AI preview · build {meta['build']}", "--notes-file", str(notes))
    verify_download(repo, rolling, assets)
    report_download(meta, url, True)


if __name__ == "__main__":
    main()
