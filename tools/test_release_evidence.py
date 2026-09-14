#!/usr/bin/env python3
"""Regression gates for release evidence; no SDK or network needed."""
import json
from pathlib import Path
import tempfile
import unittest
from release_evidence import junit_summary, lint_summary, validate_identity, verify


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def xml(self, name, text):
        path = self.root / name
        path.write_text(text)
        return path

    def test_missing_or_empty_tests_block_release(self):
        with self.assertRaises(ValueError): junit_summary([])
        with self.assertRaises(ValueError): junit_summary([self.xml("empty.xml", '<testsuite tests="0"/>')])

    def test_failed_and_skipped_tests_block_release(self):
        for key in ("failures", "errors", "skipped"):
            with self.assertRaises(ValueError):
                junit_summary([self.xml("bad.xml", f'<testsuite tests="8" {key}="1"/>')])

    def test_counts_are_read_from_results_not_source_annotations(self):
        result = junit_summary([self.xml("a.xml", '<testsuite tests="4"/>'), self.xml("b.xml", '<testsuite tests="6"/>')])
        self.assertEqual(result, dict(tests=10, failures=0, errors=0, skipped=0))

    def test_both_lint_reports_required_and_errors_block(self):
        good = self.xml("core.xml", '<issues><issue severity="Warning"/></issues>')
        bad = self.xml("nano.xml", '<issues><issue severity="Error"/></issues>')
        with self.assertRaises(ValueError): lint_summary([good])
        with self.assertRaises(ValueError): lint_summary([good, bad])

    def test_wrong_variant_or_version_blocks_release(self):
        valid = dict(package="com.caceras.surface.nano", versionName="3.0.102-nano", versionCode=102)
        validate_identity(valid, "nano", 102)
        for flavor, number in (("core", 102), ("nano", 103)):
            with self.assertRaises(ValueError): validate_identity(valid, flavor, number)

    def test_tampered_missing_or_traversing_files_block(self):
        for path in ("missing.apk", "../outside", "tampered.apk"):
            (self.root / "tampered.apk").write_bytes(b"wrong data")
            (self.root / "release-evidence.json").write_text(json.dumps(dict(schema=1, source_sha="a" * 40, files={path: "0" * 64})))
            with self.assertRaises(ValueError): verify(self.root)


if __name__ == "__main__":
    unittest.main()
