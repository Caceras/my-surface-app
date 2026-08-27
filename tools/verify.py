#!/usr/bin/env python3
"""Check an Android project for the errors AAPT2 and the manifest merger would
catch, without an Android SDK and without a CI round trip.

A push-build-download cycle costs minutes. This costs two seconds.

Checks performed:
  1. Every XML file parses.
  2. Every R.type.name reference in Kotlin resolves to a real resource.
  3. Every @type/name reference in XML resolves to a real resource.
  4. A resource used from src/main that only exists in a flavour is defined in
     EVERY flavour -- otherwise one variant builds and the others do not.
  5. Every class named in a manifest exists as a Kotlin source file, in that
     source set or in main.
  6. Every <activity-alias> points at an activity that actually exists.
  7. Any component with an intent-filter sets android:exported (a hard error
     from API 31 on).
  8. The manifest declares a launcher activity.
  9. Source sets that are meant to have no dependencies really have none.
 10. The workflow parses and references APK paths that the build produces.

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

# Source sets that must stay buildable with framework APIs alone. Everything
# else in the app leans on this invariant: it is why the template builds first
# time on a machine with nothing installed.
DEPENDENCY_FREE = {"main", "core"}

# Test source sets are not part of the shipped app.
IGNORED_SETS = {"test", "androidTest", "testFixtures"}

# "android.R.style.Foo" is the framework's own resource table, not this
# project's, so the lookbehind keeps it out of the results.
R_REFERENCE = re.compile(r"(?<![\w.])R\.([a-z]+)\.([A-Za-z0-9_]+)")

NON_FRAMEWORK_IMPORT = re.compile(
    r"\s*import\s+((?:androidx|com\.google|kotlinx|dagger|retrofit2|okhttp3)\.\S+)"
)


def fail(problems, message):
    problems.append(message)


def source_sets(project):
    """Every source set directory under app/src, main first."""
    root = os.path.join(project, "app", "src")
    if not os.path.isdir(root):
        return []
    found = [
        name for name in sorted(os.listdir(root))
        if os.path.isdir(os.path.join(root, name)) and name not in IGNORED_SETS
    ]
    return sorted(found, key=lambda n: (n != "main", n))


def collect_resources(project, source_set):
    """Map resource type -> set of names defined in this source set's res/."""
    res_dir = os.path.join(project, "app", "src", source_set, "res")
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
                        for declared in re.findall(r"@\+id/([A-Za-z0-9_]+)",
                                                   fh.read()):
                            resources.setdefault("id", set()).add(declared)

    return resources


def merge(maps):
    merged = {}
    for one in maps:
        for kind, names in one.items():
            merged.setdefault(kind, set()).update(names)
    return merged


def kotlin_sources(project, source_set):
    src_root = os.path.join(project, "app", "src", source_set, "java")
    found = []
    for base, _, names in os.walk(src_root):
        found.extend(os.path.join(base, n) for n in names if n.endswith(".kt"))
    return found


def check_kotlin(project, source_set, resources, sources, problems):
    for path in sources:
        with open(path, encoding="utf-8") as fh:
            body = fh.read()
        rel = os.path.relpath(path, project)

        for res_type, name in R_REFERENCE.findall(body):
            if name not in resources.get(res_type, set()):
                fail(problems,
                     f"{rel}: R.{res_type}.{name} does not exist in any res/")

        if source_set not in DEPENDENCY_FREE:
            continue

        # These source sets declare no dependencies on purpose, so an import
        # outside the framework namespace will not resolve at compile time.
        for line in body.split("\n"):
            match = NON_FRAMEWORK_IMPORT.match(line)
            if match:
                fail(problems,
                     f"{rel}: imports {match.group(1)}, but source set "
                     f"'{source_set}' declares no dependencies -- put the code "
                     f"in a flavour that does, or use a framework API")


def check_flavour_parity(project, sets, per_set, problems):
    """A resource main depends on must exist in every flavour, or only some
    variants build. This is the single easiest way to break one flavour while
    the other stays green."""
    flavours = [s for s in sets if s != "main"]
    if len(flavours) < 2:
        return

    main_res = per_set.get("main", {})
    used = set()
    for path in kotlin_sources(project, "main"):
        with open(path, encoding="utf-8") as fh:
            body = fh.read()
        used.update(R_REFERENCE.findall(body))

    for res_type, name in sorted(used):
        if name in main_res.get(res_type, set()):
            continue
        missing = [f for f in flavours
                   if name not in per_set.get(f, {}).get(res_type, set())]
        if missing and len(missing) < len(flavours):
            fail(problems,
                 f"R.{res_type}.{name} is used from src/main but is missing "
                 f"from flavour(s): {', '.join(missing)}")


def check_xml(project, resources, problems):
    """Parse every XML file and resolve its resource references."""
    targets = []
    for base, _, names in os.walk(project):
        parts = base.split(os.sep)
        if ".git" in parts or "build" in parts:
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

        for ref in re.findall(r"[\"\s](@[a-z]+/[A-Za-z0-9_.]+)", body):
            if ref.startswith(FRAMEWORK_PREFIXES):
                continue
            res_type, name = ref[1:].split("/", 1)
            if name not in resources.get(res_type, set()):
                fail(problems, f"{rel}: {ref} does not exist in any res/")

    return targets


def check_manifests(project, sets, class_names, problems):
    launcher = False
    seen_any = False

    for source_set in sets:
        path = os.path.join(project, "app", "src", source_set, "AndroidManifest.xml")
        if not os.path.isfile(path):
            continue
        seen_any = True
        rel = os.path.relpath(path, project)

        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            fail(problems, f"{rel}: malformed XML -- {exc}")
            continue

        app = root.find("application")
        if app is None:
            fail(problems, f"{rel}: no <application> element")
            continue

        declared_components = {
            c.get(f"{ANDROID_NS}name") for c in app
            if c.tag in ("activity", "service", "receiver", "provider")
        }

        for component in app:
            if component.tag not in (
                "activity", "service", "receiver", "provider", "activity-alias"
            ):
                continue

            name = component.get(f"{ANDROID_NS}name")

            if component.tag == "activity-alias":
                # An alias is only a label and a filter; the real class is the
                # target, and a typo there installs fine and then crashes.
                target = component.get(f"{ANDROID_NS}targetActivity")
                if not target:
                    fail(problems,
                         f"{rel}: <activity-alias android:name=\"{name}\"> has "
                         "no android:targetActivity")
                elif (target.startswith(".")
                      and target[1:] not in class_names
                      and target not in declared_components):
                    fail(problems,
                         f"{rel}: <activity-alias android:name=\"{name}\"> "
                         f"targets {target}, which has no Kotlin source")
            elif name and name.startswith("."):
                if name[1:] not in class_names:
                    fail(problems,
                         f"{rel}: <{component.tag} android:name=\"{name}\"> "
                         "has no matching Kotlin source")

            # Components with an intent filter must set exported explicitly on
            # API 31+, or installation fails outright.
            filters = component.findall("intent-filter")
            if filters and component.get(f"{ANDROID_NS}exported") is None:
                fail(problems,
                     f"{rel}: <{component.tag} android:name=\"{name}\"> has an "
                     "intent-filter but no android:exported (required on API 31+)")

            for intent_filter in filters:
                actions = {a.get(f"{ANDROID_NS}name")
                           for a in intent_filter.findall("action")}
                categories = {c.get(f"{ANDROID_NS}name")
                              for c in intent_filter.findall("category")}
                if ("android.intent.action.MAIN" in actions
                        and "android.intent.category.LAUNCHER" in categories):
                    launcher = True

    if not seen_any:
        fail(problems, "No AndroidManifest.xml found under app/src")
    elif not launcher:
        fail(problems, "No launcher activity declared in any manifest")


def check_workflow(project, problems):
    path = os.path.join(project, ".github", "workflows", "build.yml")
    if not os.path.isfile(path):
        fail(problems, ".github/workflows/build.yml is missing")
        return

    with open(path, encoding="utf-8") as fh:
        body = fh.read()

    if "app/build/outputs/apk/" not in body:
        fail(problems, "build.yml: does not reference any APK output path")
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

    sets = source_sets(project)
    if not sets:
        sys.exit("No source sets found under app/src")

    per_set = {s: collect_resources(project, s) for s in sets}
    all_resources = merge(per_set.values())

    all_sources = {s: kotlin_sources(project, s) for s in sets}
    class_names = {
        os.path.basename(p)[:-3]
        for paths in all_sources.values() for p in paths
    }

    problems = []
    if not any(all_sources.values()):
        fail(problems, "No Kotlin sources found under app/src/*/java")

    for source_set in sets:
        check_kotlin(project, source_set, all_resources,
                     all_sources[source_set], problems)

    check_flavour_parity(project, sets, per_set, problems)
    xml_files = check_xml(project, all_resources, problems)
    check_manifests(project, sets, class_names, problems)
    check_workflow(project, problems)

    total_sources = sum(len(v) for v in all_sources.values())
    total_res = sum(len(v) for v in all_resources.values())
    print(f"Source sets: {', '.join(sets)}")
    print(f"Checked {total_sources} Kotlin sources, {len(xml_files)} XML files, "
          f"{total_res} resources.")

    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print(f"  - {problem}")
        sys.exit(1)

    print("All checks passed.")


if __name__ == "__main__":
    main()
