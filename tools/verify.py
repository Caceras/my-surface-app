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
  8b. Every <activity-alias> matches a case of the Task enum, and every Task
      case is registered by some flavour. A mismatch here compiles, installs,
      and then silently runs the wrong task.
  8c. Every shortcut's android:targetPackage matches the applicationId of the
      build it ships in -- a flavour with an applicationIdSuffix cannot share
      one shortcuts.xml from src/main. Qualified copies (res/xml-v31/) are
      checked too: a qualifier replaces the default file rather than merging
      with it, so a stale copy is a second place to get this wrong.
  9. Source sets that are meant to have no dependencies really have none.
 10. The workflow parses and references APK paths that the build produces.
 11. Kotlin that uses SpeechRecognizer is backed by a RECORD_AUDIO permission.
 12. Kotlin that uses SpeechRecognizer or TextToSpeech has the matching
     <queries> entry. Package visibility applies from targetSdk 30, and
     without the entry the service simply never binds -- silently.
 13. Every Kotlin string literal closes on the line it opens. Test sources
     included, since that is where this last went wrong.

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

# Test source sets are not part of the shipped app. Flavour-specific test
# sets are named testCore, androidTestNano and so on, so the prefix is what
# matters rather than an exact list.
IGNORED_PREFIXES = ("test", "androidTest")

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
        if os.path.isdir(os.path.join(root, name))
        and not name.startswith(IGNORED_PREFIXES)
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


# Matches an enum constant carrying a single string argument, e.g.
#   SUMMARIZE("Summarize"),
TASK_CONSTANT = re.compile(r'^\s*([A-Z][A-Z0-9_]*)\("([^"]+)"\)', re.M)


def strip_comments(source):
    """Blank out // and /* */ comments, preserving line structure."""
    out = []
    i, n = 0, len(source)
    while i < n:
        if source.startswith("//", i):
            end = source.find("\n", i)
            if end == -1:
                break
            i = end
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            if end == -1:
                break
            out.append("\n" * source.count("\n", i, end))
            i = end + 2
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def task_aliases(project, sets):
    """The alias strings declared by the Task enum, if the project has one."""
    for source_set in sets:
        for path in kotlin_sources(project, source_set):
            with open(path, encoding="utf-8") as fh:
                body = fh.read()
            marker = "enum class Task("
            if marker not in body:
                continue
            # Comments are stripped before looking for the terminating ";" --
            # a semicolon inside a KDoc line would otherwise cut the enum
            # short and silently hide half its cases from this check.
            region = strip_comments(body[body.index(marker):])
            end = region.find(";")
            if end != -1:
                region = region[:end]
            names = {m.group(2) for m in TASK_CONSTANT.finditer(region)}
            return names, os.path.relpath(path, project)
    return None, None


def manifest_aliases(project, sets):
    """Every <activity-alias> simple name, mapped to the manifest declaring it."""
    found = {}
    for source_set in sets:
        path = os.path.join(project, "app", "src", source_set, "AndroidManifest.xml")
        if not os.path.isfile(path):
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        app = root.find("application")
        if app is None:
            continue
        for component in app.findall("activity-alias"):
            name = component.get(f"{ANDROID_NS}name") or ""
            found[name.lstrip(".").rsplit(".", 1)[-1]] = os.path.relpath(path, project)
    return found


def check_task_parity(project, sets, problems):
    """The alias name is the only link between a manifest entry and the code
    that handles it. Get it wrong and the app compiles, installs, shows the
    menu item, and then quietly runs whichever task the fallback picked."""
    declared, where = task_aliases(project, sets)
    if declared is None:
        return  # a project without the Task enum, e.g. a fresh scaffold

    registered = manifest_aliases(project, sets)

    for alias, manifest in sorted(registered.items()):
        if alias not in declared:
            fail(problems,
                 f"{manifest}: <activity-alias> \"{alias}\" matches no case of "
                 f"the Task enum in {where} -- it will fall back to the wrong "
                 "task at runtime")

    for alias in sorted(declared - set(registered)):
        fail(problems,
             f"{where}: Task alias \"{alias}\" is never registered by any "
             "flavour manifest, so nothing can reach it")


def application_ids(project):
    """Map source set -> the applicationId that build produces.

    A shortcut intent is explicit, so its android:targetPackage has to match
    the id of the build it ships in. Flavours that carry an
    applicationIdSuffix therefore cannot share one shortcuts.xml from
    src/main, and a res/xml resource takes no ${applicationId} placeholder.
    """
    path = os.path.join(project, "app", "build.gradle.kts")
    if not os.path.isfile(path):
        return {}

    with open(path, encoding="utf-8") as fh:
        body = fh.read()

    base = re.search(r'applicationId\s*=\s*"([^"]+)"', body)
    if not base:
        return {}
    base = base.group(1)

    ids = {"main": base}
    for flavour, block in re.findall(
        r'create\("(\w+)"\)\s*\{(.*?)\n        \}', body, re.S
    ):
        suffix = re.search(r'applicationIdSuffix\s*=\s*"([^"]+)"', block)
        ids[flavour] = base + (suffix.group(1) if suffix else "")
    return ids


def shortcut_files(project, sets):
    """Every shortcuts.xml, including qualified copies like res/xml-v31/.

    A qualified resource replaces the default rather than merging with it, so
    res/xml-v31/shortcuts.xml has to carry the entries res/xml/shortcuts.xml
    carries -- and has to get android:targetPackage right all over again.
    """
    found = []
    for source_set in sets:
        res_dir = os.path.join(project, "app", "src", source_set, "res")
        if not os.path.isdir(res_dir):
            continue
        for entry in sorted(os.listdir(res_dir)):
            if entry != "xml" and not entry.startswith("xml-"):
                continue
            path = os.path.join(res_dir, entry, "shortcuts.xml")
            if os.path.isfile(path):
                found.append((source_set, path))
    return found


def check_shortcut_packages(project, sets, problems):
    """Every shortcut points at the applicationId of its own build.

    This one is invisible until you long-press the launcher icon on a phone:
    the shortcuts appear, and open a different app -- or nothing, when that
    app is not installed.
    """
    ids = application_ids(project)
    if len(ids) < 2:
        return  # single-flavour project, nothing to get wrong

    suffixed = {s for s, app_id in ids.items() if s != "main" and app_id != ids["main"]}

    for source_set, path in shortcut_files(project, sets):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue  # already reported by the XML check

        rel = os.path.relpath(path, project)

        # One message per file, not one per shortcut: the fix is the same
        # for every entry in it.
        targets = {
            intent.get(ANDROID_NS + "targetPackage")
            for intent in root.iter("intent")
        } - {None}

        if source_set == "main" and suffixed and targets:
            names = sorted(suffixed)
            which = names[0] if len(names) == 1 else ", ".join(names)
            verb = "changes" if len(names) == 1 else "change"
            fail(problems,
                 f"{rel}: hard-codes android:targetPackage in src/main, but "
                 f"{which} {verb} the applicationId. Give each flavour its "
                 f"own shortcuts.xml.")
            continue

        expected = ids.get(source_set)
        for target in sorted(t for t in targets if expected and t != expected):
            fail(problems,
                 f"{rel}: android:targetPackage=\"{target}\" is not this "
                 f"build's applicationId ({expected}), so the shortcut opens "
                 f"another app or nothing at all.")


# Kotlin that touches these classes needs something in the manifest that no
# compiler and no static resource check will ever ask for. Both failures are
# silent at runtime, which is exactly what this script is for.
SPEECH_REQUIREMENTS = (
    ("SpeechRecognizer", "android.speech.RecognitionService"),
    ("TextToSpeech", "android.intent.action.TTS_SERVICE"),
)


def manifest_text(project, sets):
    """Every manifest concatenated. Flavour manifests merge into main's, so
    for these two checks it does not matter which file carries the entry."""
    body = []
    for source_set in sets:
        path = os.path.join(project, "app", "src", source_set,
                            "AndroidManifest.xml")
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as fh:
                body.append(fh.read())
    return "\n".join(body)


def check_speech(project, sets, all_sources, problems):
    """Speech needs a permission and two package-visibility entries.

    Miss the permission and startListening() fails with
    ERROR_INSUFFICIENT_PERMISSIONS. Miss the <queries> entry and, from
    targetSdk 30 on, the recogniser or the engine cannot be resolved at all
    and simply never binds -- with nothing in the log worth reading. Neither
    shows up in a compile, a resource check, or an emulator running an older
    API level.
    """
    used = {}
    for source_set, paths in all_sources.items():
        for path in paths:
            with open(path, encoding="utf-8") as fh:
                body = strip_comments(fh.read())
            for symbol, _ in SPEECH_REQUIREMENTS:
                if symbol in body:
                    used.setdefault(symbol, os.path.relpath(path, project))

    if not used:
        return

    manifest = manifest_text(project, sets)

    if "android.permission.RECORD_AUDIO" not in manifest:
        where = used.get("SpeechRecognizer")
        if where:
            fail(problems,
                 f"{where}: uses SpeechRecognizer, but no manifest declares "
                 "<uses-permission android:name=\"android.permission."
                 "RECORD_AUDIO\" /> -- startListening() will fail with "
                 "ERROR_INSUFFICIENT_PERMISSIONS")

    for symbol, action in SPEECH_REQUIREMENTS:
        where = used.get(symbol)
        if where and action not in manifest:
            fail(problems,
                 f"{where}: uses {symbol}, but no manifest has a <queries> "
                 f"entry for {action} -- package visibility will stop it "
                 "binding, silently")


def all_kotlin(project):
    """Every .kt under app/src, test source sets included.

    The other checks skip test sources on purpose -- they are allowed
    dependencies the shipped sets are not. Syntax is not a dependency.
    """
    root = os.path.join(project, "app", "src")
    found = []
    for base, _, names in os.walk(root):
        found.extend(os.path.join(base, n) for n in sorted(names)
                     if n.endswith(".kt"))
    return sorted(found)


def check_kotlin_strings(project, problems):
    """Every string literal closes on the line it opens.

    A literal broken across two lines is a compile error, and the compiler
    is a CI round trip away: this repo has no Android SDK to build against
    locally, so a stray newline inside a "..." costs a full red build to
    find out about. It has cost one already.

    Kotlin allows multi-line strings only in a raw \"\"\"...\"\"\" block, so
    outside one an odd number of unescaped quotes on a line is always wrong.
    Comments are stripped first, because prose quotes legitimately span
    lines in a KDoc.
    """
    for path in all_kotlin(project):
        rel = os.path.relpath(path, project)
        raw = False
        block = False
        with open(path, encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if not raw:
                    code = line
                    if block:
                        if "*/" in code:
                            code = code.split("*/", 1)[1]
                            block = False
                        else:
                            continue
                    code = re.sub(r"/\*.*?\*/", "", code)
                    if "/*" in code:
                        code = code.split("/*", 1)[0]
                        block = True
                else:
                    code = line

                if line.count('"""') % 2 == 1:
                    raw = not raw
                    continue
                if raw:
                    continue

                code = re.sub(r"//.*", "", code)
                code = re.sub(r"\\.", "", code)      # escapes, including \"
                code = re.sub(r"'.'", "", code)      # the char literal '"'
                if code.count('"') % 2 == 1:
                    fail(problems,
                         f"{rel}:{number}: string literal is not closed on "
                         f"this line -- {line.strip()[:60]}")


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
    check_task_parity(project, sets, problems)
    xml_files = check_xml(project, all_resources, problems)
    check_manifests(project, sets, class_names, problems)
    check_shortcut_packages(project, sets, problems)
    check_speech(project, sets, all_sources, problems)
    check_kotlin_strings(project, problems)
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
