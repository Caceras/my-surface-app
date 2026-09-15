#!/usr/bin/env python3
"""Build a verifiable APK/test/render evidence bundle using only the Python stdlib.

Usage: python tools/release_evidence.py collect|verify <bundle-directory>
Collection requires successful JUnit + lint XML, both APKs, and native renders.
It proves artifact identity and checks, not visual approval or physical AI quality.
"""
import argparse
from collections import Counter
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile

REQUIRED_SHOTS = {"chat-empty", "chat-night", "chat-conversation", "chat-settings",
                  "voice-setup", "voice-answer", "conversations", "aegentica-actions",
                  "aegentica-widget", "aegentica-large-font", "aegentica-voice-landscape"}


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def apk_identity(path):
    """Read typed manifest attributes from Android's binary XML string pool."""
    with zipfile.ZipFile(path) as archive:
        data = archive.read("AndroidManifest.xml")
    u16 = lambda p: struct.unpack_from("<H", data, p)[0]
    u32 = lambda p: struct.unpack_from("<I", data, p)[0]
    def length(p, utf8):
        value = data[p] if utf8 else u16(p)
        mask, shift, step = (128, 8, 1) if utf8 else (32768, 16, 2)
        if value & mask:
            tail = data[p + step] if utf8 else u16(p + step)
            return ((value & (mask - 1)) << shift) | tail, p + step * 2
        return value, p + step
    strings, pos = [], 8
    while pos < len(data):
        kind, header, size = u16(pos), u16(pos + 2), u32(pos + 4)
        if size < 8 or pos + size > len(data):
            raise ValueError("Invalid binary XML chunk")
        if kind == 1:
            utf8 = bool(u32(pos + 16) & 256)
            for i in range(u32(pos + 8)):
                start = pos + u32(pos + 20) + u32(pos + header + 4 * i)
                count, start = length(start, utf8)
                if utf8:
                    count, start = length(start, True)
                strings.append(data[start:start + count * (1 if utf8 else 2)].decode("utf-8" if utf8 else "utf-16-le"))
        elif kind == 0x102 and strings[u32(pos + 20)] == "manifest":
            attrs = {}
            for i in range(u16(pos + 28)):
                a = pos + 16 + u16(pos + 24) + i * u16(pos + 26)
                raw, value = u32(a + 8), u32(a + 16)
                attrs[strings[u32(a + 4)]] = strings[raw] if raw != 0xffffffff else strings[value] if data[a + 15] == 3 else value
            return {key: attrs[key] for key in ("package", "versionName", "versionCode")}
        pos += size
    raise ValueError("APK has no manifest identity")


def junit_summary(paths):
    total = dict(tests=0, failures=0, errors=0, skipped=0)
    if not paths:
        raise ValueError("Missing JUnit results")
    for path in paths:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            for key in total:
                total[key] += int(suite.get(key, "0"))
    if total["tests"] <= 0 or any(total[key] for key in ("failures", "errors", "skipped")):
        raise ValueError(f"JUnit gate failed: {total}")
    return total


def lint_summary(paths):
    if len(paths) != 2:
        raise ValueError("Both flavor lint reports are required")
    counts = {}
    for path in paths:
        for issue in ET.parse(path).getroot().iter("issue"):
            severity = issue.get("severity", "Unknown")
            counts[severity] = counts.get(severity, 0) + 1
    if counts.get("Error", 0) or counts.get("Fatal", 0):
        raise ValueError(f"Lint gate failed: {counts}")
    return counts


def lint_inventory(paths):
    """Stable category counts per report, without leaking runner paths."""
    return {path.name: dict(sorted(Counter(
        issue.get("id", "Unknown") for issue in ET.parse(path).getroot().iter("issue")
    ).items())) for path in paths}


def validate_identity(identity, flavor, number):
    expected = "com.caceras.surface" + (".nano" if flavor == "nano" else "")
    if identity != dict(package=expected, versionName=f"3.0.{number}-{flavor}", versionCode=int(number)):
        raise ValueError(f"Unexpected {flavor} APK: {identity}")


def audit_report(destination, root, sha):
    register = root / "docs/audits/2026-09-15.md"
    if not register.is_file():
        return
    shutil.copy2(register, destination / "audit.md")
    flows = [
        ("Enter and type", "chat-empty", "Native layout checked. Keyboard, TalkBack and display scaling still need Pixel acceptance."),
        ("Read and recover", "audit-answer-large-font", "Actions adapt to large type; Edit question protects a different draft. Lifecycle regressions accompany the render."),
        ("Talk and recover", "audit-voice-recovery", "Failed questions have typed recovery; recreation stays paused. This fixture does not exercise real Nano or speech."),
        ("Save and resume", "conversations", "History controls and previews are visible; import identity regressions preserve distinct entries."),
        ("Configure", "chat-settings", "Settings is shorter; close controls require visual review in both themes. See review.html for dark/feedback states."),
        ("Select text", "audit-selection", "Shared private prompt and validation. This is native dialog content on a test host, not a source-app overlay."),
        ("Android handoff", "aegentica-actions", "Explicit validated handoffs. Installed destination apps, launcher and assistant gestures need physical-device acceptance.")
    ]
    figures = []
    for step, (title, shot, note) in enumerate(flows, 1):
        if not (destination / f"screenshots/{shot}.png").is_file():
            raise ValueError(f"Missing audit flow screenshot: {shot}")
        figures.append(f'<section><h2>{step}. {html.escape(title)}</h2><p>{html.escape(note)}</p><img src="screenshots/{shot}.png" alt="{html.escape(title)}" loading="lazy"></section>')
    (destination / "audit.html").write_text(f'<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Ægentica AI audit</title><style>body{{font:16px/1.6 system-ui;color:#142d3d;background:#f6fafd;max-width:960px;margin:24px auto;padding:0 20px}}section{{border-top:1px solid #cddde8;padding:20px 0}}img{{max-width:100%;width:360px;height:auto}}code{{overflow-wrap:anywhere}}</style><h1>Ægentica AI · audit evidence</h1><p>Source <code>{sha}</code>. Native core fixtures; human review and physical Pixel checks remain separate.</p><p><a href="audit.md">Full repair register and open gates</a> · <a href="review.html">All native states</a> · <a href="reports/lint-inventory.json">Lint inventory</a></p>'+"".join(figures)+"</html>")


def collect(destination, root=Path(".")):
    if destination.exists() and any(destination.iterdir()):
        raise ValueError("Use an empty evidence directory to prevent stale results")
    number = os.environ["GITHUB_RUN_NUMBER"]
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if sha != os.environ["GITHUB_SHA"]:
        raise ValueError("Checkout does not match workflow SHA")
    build = root / "app/build"
    tests = sorted((build / "test-results/testCoreDebugUnitTest").glob("TEST-*.xml"))
    lint = sorted((build / "reports").glob("lint-results-*-debug.xml"))
    # AGP uses variant camel case in report names.
    if not lint:
        lint = sorted((build / "reports").glob("lint-results-*Debug.xml"))
    results, lint_results = junit_summary(tests), lint_summary(lint)
    shots = sorted((build / "screenshots").glob("*.png"))
    if not REQUIRED_SHOTS <= {p.stem for p in shots}:
        raise ValueError("Missing required native screenshots")
    destination.mkdir(parents=True, exist_ok=True)
    meta = dict(schema=1, source_sha=sha, run_id=os.environ["GITHUB_RUN_ID"],
                build=int(number), attempt=int(os.environ.get("GITHUB_RUN_ATTEMPT", "1")),
                repository=os.environ["GITHUB_REPOSITORY"], tests=results, lint=lint_results,
                visual_review="required", device_validation="required", apks={}, screenshots=[])
    for flavor in ("core", "nano"):
        src = build / f"outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"
        identity = apk_identity(src)
        validate_identity(identity, flavor, number)
        name = f"aegentica-ai-{flavor}.apk"
        shutil.copy2(src, destination / name)
        shutil.copy2(src, destination / f"pixel-surface-lab-{flavor}.apk")
        meta["apks"][name] = identity
    signatures = [build / f"reports/apk-signature-{flavor}.txt" for flavor in ("core", "nano")]
    if any(not p.is_file() or "Signer #1 certificate SHA-256 digest:" not in p.read_text() for p in signatures):
        raise ValueError("Missing APK signature verification reports")
    for src in [*tests, *lint, *signatures]:
        folder = destination / "reports"
        folder.mkdir(exist_ok=True)
        shutil.copy2(src, folder / src.name)
    (destination / "reports/lint-inventory.json").write_text(json.dumps(lint_inventory(lint), indent=2) + "\n")
    for src in shots:
        data = src.read_bytes()
        if data[:8] != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"Invalid screenshot: {src.name}")
        width, height = struct.unpack(">II", data[16:24])
        if min(width, height) < 100:
            raise ValueError(f"Empty screenshot: {src.name}")
        folder = destination / "screenshots"
        folder.mkdir(exist_ok=True)
        shutil.copy2(src, folder / src.name)
        meta["screenshots"].append(dict(file=f"screenshots/{src.name}", width=width, height=height))
    figures = "\n".join(f'<figure><img src="{html.escape(s["file"])}" loading="lazy"><figcaption>{html.escape(Path(s["file"]).stem)} · {s["width"]} × {s["height"]}</figcaption></figure>' for s in meta["screenshots"])
    (destination / "review.html").write_text(f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Ægentica AI · build {number}</title><style>body{{font:16px system-ui;background:#f6fafd;color:#142d3d;margin:32px}}main{{display:flex;flex-wrap:wrap;gap:24px}}figure{{margin:0;width:320px}}img{{width:100%;height:auto;border-radius:16px}}figcaption{{padding:12px 0}}code{{overflow-wrap:anywhere}}</style><h1>Ægentica AI · build {number}</h1><p>Source <code>{sha}</code> · {results['tests']} tests. Native core fixtures; visual review and physical Pixel validation remain required.</p><main>{figures}</main></html>''')
    audit_report(destination, root, sha)
    meta["files"] = {str(p.relative_to(destination)): digest(p) for p in sorted(destination.rglob("*")) if p.is_file()}
    (destination / "release-evidence.json").write_text(json.dumps(meta, indent=2) + "\n")
    checks = dict(meta["files"])
    checks["release-evidence.json"] = digest(destination / "release-evidence.json")
    (destination / "SHA256SUMS").write_text("".join(f"{value}  {name}\n" for name, value in sorted(checks.items())))
    summary = f"## Ægentica AI · build {number}\n\n- Source: `{sha}`\n- JVM: {results['tests']} passed, 0 skipped\n- Lint: {lint_results}\n- Native renders: {len(shots)}\n- Download artifact **aegentica-evidence**, open **review.html**. Visual review and device validation are separate gates.\n"
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
            out.write(summary)
    verify(destination)
    print(summary)


def verify(folder):
    meta = json.loads((folder / "release-evidence.json").read_text())
    if meta.get("schema") != 1 or not re.fullmatch(r"[a-f0-9]{40}", meta["source_sha"]):
        raise ValueError("Invalid provenance")
    if set(meta.get("apks", {})) != {"aegentica-ai-core.apk", "aegentica-ai-nano.apk"}:
        raise ValueError("Both branded APK identities are required")
    required = {f"{prefix}-{flavor}.apk" for prefix in ("aegentica-ai", "pixel-surface-lab") for flavor in ("core", "nano")}
    required |= {f"screenshots/{name}.png" for name in REQUIRED_SHOTS}
    required |= {"review.html", "reports/apk-signature-core.txt", "reports/apk-signature-nano.txt"}
    if not required <= set(meta["files"]):
        raise ValueError("Incomplete evidence inventory")
    for name, checksum in meta["files"].items():
        path = (folder / name).resolve()
        if not path.is_relative_to(folder.resolve()) or not path.is_file() or digest(path) != checksum:
            raise ValueError(f"Artifact mismatch: {name}")
    expected_files = set(meta["files"]) | {"release-evidence.json", "SHA256SUMS"}
    actual_files = {str(p.relative_to(folder)) for p in folder.rglob("*") if p.is_file()}
    if actual_files != expected_files or any(p.is_symlink() for p in folder.rglob("*")):
        raise ValueError("Evidence contains unlisted files or symbolic links")
    for name, identity in meta["apks"].items():
        if apk_identity(folder / name) != identity:
            raise ValueError(f"APK identity mismatch: {name}")
        validate_identity(identity, name.removeprefix("aegentica-ai-").removesuffix(".apk"), meta["build"])
        if digest(folder / name) != digest(folder / name.replace("aegentica-ai", "pixel-surface-lab")):
            raise ValueError("Compatibility alias differs from branded APK")
    checks = dict(meta["files"])
    checks["release-evidence.json"] = digest(folder / "release-evidence.json")
    expected = "".join(f"{value}  {name}\n" for name, value in sorted(checks.items()))
    if (folder / "SHA256SUMS").read_text() != expected:
        raise ValueError("Checksum index mismatch")
    if junit_summary(sorted((folder / "reports").glob("TEST-*.xml"))) != meta["tests"]:
        raise ValueError("JUnit summary mismatch")
    if lint_summary(sorted((folder / "reports").glob("lint-results-*.xml"))) != meta["lint"]:
        raise ValueError("Lint summary mismatch")
    inventory = folder / "reports/lint-inventory.json"
    if inventory.exists() and json.loads(inventory.read_text()) != lint_inventory(sorted((folder / "reports").glob("lint-results-*.xml"))):
        raise ValueError("Lint inventory mismatch")
    return meta


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("collect", "verify"))
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    (collect if args.command == "collect" else verify)(args.directory)
