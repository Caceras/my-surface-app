#!/usr/bin/env python3
"""Tests for verify.py: prove it actually catches what it claims to.

A checker nobody tests is a checker that quietly stops checking. Each case
below scaffolds a throwaway project, breaks exactly one thing, and asserts a
non-zero exit.

Usage:
    python tools/test_verify.py
"""

import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PY = sys.executable


def scaffold(dest):
    subprocess.run(
        [PY, os.path.join(HERE, "scaffold.py"),
         "--out", dest,
         "--package", "com.example.probe",
         "--app-name", "Probe",
         "--surface", "tile"],
        check=True, capture_output=True,
    )


def verify(project):
    return subprocess.run(
        [PY, os.path.join(HERE, "verify.py"), project],
        capture_output=True, text=True,
    )


def edit(path, find, replace):
    with open(path, encoding="utf-8") as fh:
        body = fh.read()
    assert find in body, f"fixture drifted: {find!r} not in {path}"
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(body.replace(find, replace, 1))


def case(name, break_it):
    work = tempfile.mkdtemp(prefix="verify-test-")
    project = os.path.join(work, "probe")
    try:
        scaffold(project)
        clean = verify(project)
        if clean.returncode != 0:
            return f"FAIL {name}: the untouched scaffold did not pass\n{clean.stdout}"
        break_it(project)
        broken = verify(project)
        if broken.returncode == 0:
            return f"FAIL {name}: the broken project still passed"
        return f"ok   {name}"
    finally:
        shutil.rmtree(work, ignore_errors=True)


def kotlin_file(project):
    src = os.path.join(project, "app", "src", "main", "java")
    for base, _, names in os.walk(src):
        for n in names:
            if n.endswith(".kt"):
                return os.path.join(base, n)
    raise AssertionError("no Kotlin source in the scaffold")


def manifest(project):
    return os.path.join(project, "app", "src", "main", "AndroidManifest.xml")


def dangling_resource(project):
    path = kotlin_file(project)
    with open(path, encoding="utf-8") as fh:
        body = fh.read()
    body = body.replace("R.string.app_name", "R.string.does_not_exist")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(body)


def forbidden_import(project):
    path = kotlin_file(project)
    with open(path, encoding="utf-8") as fh:
        body = fh.read()
    lines = body.split("\n")
    for i, line in enumerate(lines):
        if line.startswith("import "):
            lines.insert(i, "import androidx.core.app.NotificationCompat")
            break
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))


def broken_xml(project):
    with open(manifest(project), "a", encoding="utf-8") as fh:
        fh.write("<not-closed>")


def missing_exported(project):
    edit(manifest(project), 'android:exported="true"', 'android:label="@string/app_name"')


def missing_launcher(project):
    edit(manifest(project), "android.intent.category.LAUNCHER",
         "android.intent.category.DEFAULT")


SPEECH_SOURCE = """package com.example.probe

import android.content.Context
import android.speech.SpeechRecognizer

object Listener {
    fun start(context: Context) {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    }
}
"""

QUERIES = """    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
    </queries>
"""

PERMISSION = """    <uses-permission android:name="android.permission.RECORD_AUDIO" />
"""


def add_speech(project, manifest_addition):
    """Use the recogniser from Kotlin, with only half of what it needs.

    Both halves are invisible to the compiler and to every other check here:
    without the permission startListening() fails at runtime, and without the
    <queries> entry the service never binds at all.
    """
    src = os.path.dirname(kotlin_file(project))
    with open(os.path.join(src, "Listener.kt"), "w", encoding="utf-8") as fh:
        fh.write(SPEECH_SOURCE)
    edit(manifest(project), "    <application", manifest_addition + "\n    <application")


def speech_without_permission(project):
    add_speech(project, QUERIES)


def speech_without_queries(project):
    add_speech(project, PERMISSION)


def unterminated_string(project):
    """A string literal broken across two lines, exactly as one shipped.

    A heredoc turned a \\n into a real newline, the checker had nothing to
    say about it, and the compile error arrived from CI two minutes later.
    """
    path = kotlin_file(project)
    with open(path, encoding="utf-8") as fh:
        body = fh.read()
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(body + '\n\nprivate val broken = "not closed\n"\n')


def unterminated_string_in_a_test(project):
    """The same thing in a test source set, which the other checks skip."""
    path = os.path.join(project, "app", "src", "test", "java", "Probe.kt")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write('class Probe {\n    val x = "not closed\n"\n}\n')


def nested_block_comment(project):
    """A path inside a KDoc, which is how this actually happened.

    "app/build/screenshots/*.png" contains /*, Kotlin nests block comments,
    and the */ meant to close the doc closes only the nested level. The
    compiler reports "Unclosed comment" against the last line of the file.
    """
    path = kotlin_file(project)
    with open(path, encoding="utf-8") as fh:
        body = fh.read()
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("/**\n * Writes to build/screenshots/*.png\n */\n" + body)


def workflow_without_permission(project):
    path = os.path.join(project, ".github", "workflows", "build.yml")
    edit(path, "contents: write", "contents: read")


CASES = [
    ("dangling resource reference", dangling_resource),
    ("import with no dependency", forbidden_import),
    ("malformed XML", broken_xml),
    ("intent-filter without exported", missing_exported),
    ("no launcher activity", missing_launcher),
    ("workflow without contents: write", workflow_without_permission),
    ("speech without RECORD_AUDIO", speech_without_permission),
    ("speech without a <queries> entry", speech_without_queries),
    ("string literal split across lines", unterminated_string),
    ("the same, in a test source set", unterminated_string_in_a_test),
    ("a path that nests a block comment", nested_block_comment),
]


def main():
    results = [case(name, fn) for name, fn in CASES]
    for line in results:
        print(line)
    failures = [r for r in results if r.startswith("FAIL")]
    print()
    print(f"{len(results) - len(failures)}/{len(results)} passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
