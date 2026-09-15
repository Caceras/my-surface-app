#!/usr/bin/env python3
"""Guard skipped-build decisions; no Android SDK or network."""
import unittest
from ci_scope import decide


class ScopeTests(unittest.TestCase):
    def scope(self, paths, event_name="push", event=None):
        return decide(event_name, event or {}, paths, "Caceras/my-surface-app")[0]

    def test_only_known_documentation_skips_android(self):
        self.assertFalse(self.scope(["README.md", "docs/audits/process.md", "docs/images/chat.png"]))
        for paths in (None, [], ["docs/build.py"], ["app/src/main/README.md"], ["new-config.toml"],
                      ["README.md", ".github/workflows/build.yml"], ["docs/x.md", "app/src/main/Old.kt"]):
            with self.subTest(paths=paths): self.assertTrue(self.scope(paths))

    def test_draft_deduplication_is_only_for_trusted_preview_branches(self):
        pr = {"draft": True, "head": {"ref": "improve-pixel-assistant", "repo": {"full_name": "Caceras/my-surface-app"}}}
        self.assertFalse(self.scope(["app/src/main/App.kt"], "pull_request", {"pull_request": pr}))
        for draft, branch, repo in ((False, "improve-pixel-assistant", "Caceras/my-surface-app"),
                                   (True, "feature", "Caceras/my-surface-app"),
                                   (True, "improve-pixel-assistant", "contributor/fork")):
            candidate = {"draft": draft, "head": {"ref": branch, "repo": {"full_name": repo}}}
            self.assertTrue(self.scope(["app/src/main/App.kt"], "pull_request", {"pull_request": candidate}))

    def test_manual_run_always_validates_android(self):
        self.assertTrue(self.scope(["README.md"], "workflow_dispatch"))


if __name__ == "__main__":
    unittest.main()
