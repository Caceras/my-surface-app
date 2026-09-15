# Working on Ægentica AI

Read `README.md`, `docs/architecture.md`, and `docs/iteration-workflow.md` before an implementation pass. The app is native Android; do not replace it with a web mockup. Preserve the Æ signum, sky-blue tokens, package IDs and compatible conversation backups unless the user explicitly requests a migration.

## Iteration contract

1. Inspect branch HEAD and local changes. Preserve unrelated edits and continue the existing branch/PR when that is the current task. Do not merge main or change external distribution scope without authorization.
2. Use `docs/audits/2026-09-15.md` as the current repair register. Identify a concrete user-flow defect or acceptance gap. For visual findings, capture fresh native renders and inspect them; distinguish code inspection, layout fixtures and real-device evidence.
3. Reuse `Design.kt` controls and navigation pause behavior. Keep Settings, History, Actions, chat and Voice consistent. Never let hidden capture/generation continue after an explicit navigation pause.
4. Keep main/core framework-only. Nano SDK code belongs in the Nano source set. Runtime dependencies and test dependencies have different boundaries.
5. Add focused regressions for meaningful behavior changes. Preserve draft recovery, cancellation tokens, speech muting, private defaults and export compatibility. Do not weaken failing assertions merely to make a build green.
6. Update affected guides and `docs/preview-notes.md` in the same implementation batch; the publisher includes these notes. Run `python tools/check.py`. Avoid repeated documentation-only Android rebuilds by finishing prose and links before the final source push when practical.
7. Use the CI Android quality job for real SDK builds when a local SDK is absent. Inspect `android-diagnostics` on failure; distinguish compile/test/lint defects from infrastructure failures. Do not blindly retry indefinitely.
8. On success, download `aegentica-evidence`, run `python tools/release_evidence.py verify <directory>`, inspect affected images in `review.html`, and record the reviewed commit/run in the PR. Generated screenshots are not visual approval.
9. Verify the published build-specific Nano URL, installed-version target, package and checksum. CI performs remote byte comparisons; confirm the publication job succeeded. Keep the unique link in the handoff even when compatibility aliases exist.
10. State physical-device, signing and platform limitations precisely. CI fixtures cannot certify Nano answer quality, real speech, accessibility, frame timing or battery. Turn new device findings into scoped reproductions.

## Data and delivery

Never commit or expose signing keys, secrets, real conversation exports or private user captures. Private signing material belongs only in the documented private secret/local environment mechanism. PR jobs must not receive that material. Do not auto-execute model text as Android commands.

Published build releases are immutable by convention; a retry can repair a draft but must not silently replace a public build. The rolling alias is not transactional. Prefer the exact verified build URL for testing and rollback.
