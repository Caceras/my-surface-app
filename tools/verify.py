#!/usr/bin/env python3
"""Check a scaffolded Android project for the errors AAPT2 would catch.

Runs in a couple of seconds with no Android SDK, so it is worth running before
pushing: a dangling resource reference costs three minutes to discover in CI and
is obvious here.

Checks performed:
  1. Every XML file parses.
  2. Every R.type.name reference in Kotlin resolves to a real resource.
  3. Every @type/name reference in XML resolves to a real resource.
  4. Every class named in AndroidManifest.xml exists as a Kotlin source file.
  5. The manifest declares a launcher activity.
  6. The workflow file parses and references a real APK path.

Usage:
    python verify.py <project-dir>

Exits non-zero if any check fails.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# References to the framework's own resources, which are always available.
FRAMEWORK_PREFIXES = ("@android:", "?android:", "@*android:")


def fail(problems, message):
    problems.append(message)


def collect_resources(res_dir):
    """Map resource type -> set of names defined in res/."""
    resources = {}

    if not os.path.isdir(res_dir):
        return resources

    for entry in sorted(os.listdir(res_dir)):
        path = os.path.join(res_dir, entry)
        if not os.path.isdir(path):
            continue

        # "drawable-night-v31" and "mipmap-anydpi-v26" both contribute to the
        # base type, so qualifiers are stripped.
        res_type = entry.split("-")[0]

        for name in sorted(os.listdir(path)):
            full = os.path.join(path, name)
            if not os.path.isfile(full):
                continue

            if res_type == "values":
                # values/*.xml declares resources by <tag name="...">
                try:
                    root = ET.parse(full).getroot()
                except ET.ParseError:
                    continue
                for child in root:
                    if child.tag is ET.Comment:
                        continue
                    declared = child.get("name")
                    if declared:
                        kind = child.get("type") if child.tag == "item" else child.tag
                        resources.setdefault(kind, set()).add(declared)
            else:
                stem = name.rsplit(".", 1)[0]
                resources.setdefault(res_type, set()).add(stem)

                # @+id/... declarations live inside layout files.
                if res_type == "layout":
                    with open(full, encoding="utf-8") as fh:
                        for declared in re.findall(r'@\+id/([A-Za-z0-9_]+)',
                                                   fh.read()):
                            resources.setdefault("id", set()).add(declared)

    return resources


def check_kotlin_refs(project, resources, problems):
    src_root = os.path.join(project, "app", "src", "main", "java")
    sources = []
    for base, _, names in os.walk(src_root):
        sources.extend(os.path.join(base, n) for n in names if n.endswith(".kt"))

    if not sources:
        fail(problems, "No Kotlin sources found under app/src/main/java")
        return sources

    for path in sources:
        with open(path, encoding="utf-8") as fh:
            body = fh.read()
        for res_type, name in re.findall(r'\bR\.([a-z]+)\.([A-Za-z0-9_]+)', body):
            if name not in resources.get(res_type, set()):
                rel = os.path.relpath(path, project)
                fail(problems,
                     f"{rel}: R.{res_type}.{name} does not exist in res/")

        # The project declares no dependencies on purpose, so any import
        # outside the framework namespace will not resolve at compile time.
        # This is easy to reintroduce by accident when editing the scaffold.
        for line in body.split("\n"):
            match = re.match(r'\s*import\s+((?:androidx|com\.google|kotlinx)\.\S+)',
                             line)
            if match:
                rel = os.path.relpath(path, project)
                fail(problems,
                     f"{rel}: imports {match.group(1)}, but the build declares "
                     f"no dependencies -- add the dependency or use a "
                     f"framework API")
    return sources


def check_xml(project, resources, problems):
    """Parse every XML file and resolve its resource references."""
    targets = []
    for base, _, names in os.walk(project):
        if ".git" in base.split(os.sep):
            continue
        targets.extend(os.path.join(base, n) for n in names if n.endswith(".xml"))

    for path in targets:
        rel = os.path.relpath(path, project)
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            fail(problems, f"{rel}: malformed XML -- {exc}")
            continue

        with open(path, encoding="utf-8") as fh:
            body = fh.read()

        for ref in re.findall(r'["\s](@[a-z]+/[A-Za-z0-9_.]+)', body):
            if ref.startswith(FRAMEWORK_PREFIXES):
                continue
            res_type, name = ref[1:].split("/", 1)
            if name not in resources.get(res_type, set()):
                fail(problems, f"{rel}: {ref} does not exist in res/")

    return targets


def check_manifest(project, sources, problems):
    path = os.path.join(project, "app", "src", "main", "AndroidManifest.xml")
    if not os.path.isfile(path):
        fail(problems, "AndroidManifest.xml is missing")
        return

    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        fail(problems, f"AndroidManifest.xml: malformed XML -- {exc}")
        return

    class_names = {os.path.basename(s)[:-3] for s in sources}
    app = root.find("application")
    if app is None:
        fail(problems, "AndroidManifest.xml: no <application> element")
        return

    launcher = False
    for component in app:
        if component.tag not in ("activity", "service", "receiver", "provider"):
            continue

        declared = component.get(f"{ANDROID_NS}name")
        if declared and declared.startswith("."):
            simple = declared[1:]
            if simple not in class_names:
                fail(problems,
                     f"AndroidManifest.xml: <{component.tag} android:name=\""
                     f"{declared}\"> has no matching Kotlin source")

        # Components with an intent filter must set exported explicitly on
        # API 31+, or installation fails outright.
        filters = component.findall("intent-filter")
        if filters and component.get(f"{ANDROID_NS}exported") is None:
            fail(problems,
                 f"AndroidManifest.xml: <{component.tag} "
                 f"android:name=\"{declared}\"> has an intent-filter but no "
                 "android:exported (required on API 31+)")

        for intent_filter in filters:
            actions = {a.get(f"{ANDROID_NS}name")
                       for a in intent_filter.findall("action")}
            categories = {c.get(f"{ANDROID_NS}name")
                          for c in intent_filter.findall("category")}
            if ("android.intent.action.MAIN" in actions
                    and "android.intent.category.LAUNCHER" in categories):
                launcher = True

    if not launcher:
        fail(problems, "AndroidManifest.xml: no launcher activity declared")


def check_workflow(project, problems):
    path = os.path.join(project, ".github", "workflows", "build.yml")
    if not os.path.isfile(path):
        fail(problems, ".github/workflows/build.yml is missing")
        return

    with open(path, encoding="utf-8") as fh:
        body = fh.read()

    if "app/build/outputs/apk/debug/app-debug.apk" not in body:
        fail(problems, "build.yml: does not reference the debug APK output path")
    if "gh release create" not in body:
        fail(problems, "build.yml: does not publish a release "
                       "(artifacts alone cannot be installed from a phone)")
    if "contents: write" not in body:
        fail(problems, "build.yml: missing 'contents: write' permission, "
                       "so the release step will fail with a 403")

    try:
        import yaml
    except ImportError:
        print("  note: PyYAML not installed, skipped workflow YAML parse")
        return
    try:
        yaml.safe_load(body)
    except yaml.YAMLError as exc:
        fail(problems, f"build.yml: malformed YAML -- {exc}")


def main():
    if len(sys.argv) != 2:
        sys.exit("Usage: python verify.py <project-dir>")

    project = os.path.abspath(sys.argv[1])
    if not os.path.isdir(project):
        sys.exit(f"Not a directory: {project}")

    res_dir = os.path.join(project, "app", "src", "main", "res")
    resources = collect_resources(res_dir)

    problems = []
    sources = check_kotlin_refs(project, resources, problems)
    xml_files = check_xml(project, resources, problems)
    check_manifest(project, sources, problems)
    check_workflow(project, problems)

    total = sum(len(v) for v in resources.values())
    print(f"Checked {len(sources)} Kotlin sources, {len(xml_files)} XML files, "
          f"{total} resources.")

    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(f"  - {problem}")
        sys.exit(1)

    print("All checks passed.")


if __name__ == "__main__":
    main()
