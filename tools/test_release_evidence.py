#!/usr/bin/env python3
"""Regression gates for release evidence; no SDK or network needed."""
import json
from pathlib import Path
import tempfile
import unittest
from release_evidence import digest
from release_evidence import REQUIRED_SHOTS, certificate_digests, junit_summary, lint_summary, lint_inventory, validate_identity, verify


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

    def test_lint_inventory_keeps_flavors_separate_and_counts_duplicates(self):
        core = self.xml("core.xml", '<issues><issue id="SetTextI18n"/><issue id="SetTextI18n"/><issue id="UnusedResources"/></issues>')
        nano = self.xml("nano.xml", '<issues><issue id="UseKtx"/></issues>')
        self.assertEqual(lint_inventory([core, nano]), {"core.xml": {"SetTextI18n": 2, "UnusedResources": 1}, "nano.xml": {"UseKtx": 1}})

    def test_wrong_variant_or_version_blocks_release(self):
        valid = dict(package="com.caceras.surface.nano", versionName="3.0.102-nano", versionCode=102)
        validate_identity(valid, "nano", 102)
        for flavor, number in (("core", 102), ("nano", 103)):
            with self.assertRaises(ValueError): validate_identity(valid, flavor, number)

    def test_signing_evidence_requires_matching_valid_certificates(self):
        core = self.xml("core.txt", "Signer #1 certificate SHA-256 digest: " + "a" * 64)
        nano = self.xml("nano.txt", "Signer #1 certificate SHA-256 digest: " + "a" * 64)
        self.assertEqual(len(certificate_digests([core, nano])), 2)
        nano.write_text("Signer #1 certificate SHA-256 digest: " + "b" * 64)
        with self.assertRaisesRegex(ValueError, "same signing"): certificate_digests([core, nano])
        nano.write_text("verified, but missing a fingerprint")
        with self.assertRaisesRegex(ValueError, "Missing signing"): certificate_digests([core, nano])

    def test_tampered_missing_or_traversing_files_block(self):
        for path in ("missing.apk", "../outside", "tampered.apk"):
            (self.root / "tampered.apk").write_bytes(b"wrong data")
            required = {f"aegentica-ai-{flavor}.apk" for flavor in ("core", "nano")}
            required |= {f"screenshots/{name}.png" for name in REQUIRED_SHOTS}
            required |= {"review.html", "reports/apk-signature-core.txt", "reports/apk-signature-nano.txt"}
            files = {path: "0" * 64, **{name: "0" * 64 for name in required}}
            meta = dict(schema=1, source_sha="a" * 40, files=files,
                        apks={"aegentica-ai-core.apk": {}, "aegentica-ai-nano.apk": {}})
            (self.root / "release-evidence.json").write_text(json.dumps(meta))
            with self.assertRaisesRegex(ValueError, "Artifact mismatch"): verify(self.root)

    def test_unlisted_files_and_symlinks_block_before_apk_parsing(self):
        required = {f"aegentica-ai-{flavor}.apk" for flavor in ("core", "nano")}
        required |= {f"screenshots/{name}.png" for name in REQUIRED_SHOTS}
        required |= {"review.html", "reports/apk-signature-core.txt", "reports/apk-signature-nano.txt"}
        for name in required:
            path = self.root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b"fixture")
        meta = dict(schema=1, source_sha="a" * 40,
                    files={name: digest(self.root / name) for name in required},
                    apks={"aegentica-ai-core.apk": {}, "aegentica-ai-nano.apk": {}})
        (self.root / "release-evidence.json").write_text(json.dumps(meta))
        (self.root / "SHA256SUMS").write_text("")
        extra = self.root / "accidental-private-export.json"
        extra.write_text("must not ship")
        with self.assertRaisesRegex(ValueError, "unlisted files"): verify(self.root)
        extra.unlink()
        link = self.root / "review.html"
        link.unlink(); link.symlink_to(self.root / "aegentica-ai-core.apk")
        with self.assertRaisesRegex(ValueError, "symbolic links"): verify(self.root)

    def test_missing_apk_inventory_blocks(self):
        (self.root / "release-evidence.json").write_text(json.dumps(dict(schema=1, source_sha="a" * 40, apks={})))
        with self.assertRaisesRegex(ValueError, "Both branded APK"): verify(self.root)

    def test_missing_render_or_signature_inventory_blocks(self):
        meta = dict(schema=1, source_sha="a" * 40, files={}, apks={"aegentica-ai-core.apk": {}, "aegentica-ai-nano.apk": {}})
        (self.root / "release-evidence.json").write_text(json.dumps(meta))
        with self.assertRaisesRegex(ValueError, "Incomplete evidence"): verify(self.root)


if __name__ == "__main__":
    unittest.main()
