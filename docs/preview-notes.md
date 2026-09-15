# Current preview changes

- Audited test necessity and the full request-to-install process. Retired three constant/wording tests, consolidated three overlapping selection checks into one, and strengthened model-status validation. The Android suite is now 151 meaningful cases with all 22 native captures retained.
- Chat tests identify messages by role rather than view nesting; adding an action no longer masquerades as an answer. Switch checks allow additions while retaining theme/touch behavior.
- Documentation-only changes skip Android builds and APK publication. Canonical draft preview PRs defer duplicate merge validation until ready-for-review; forks and other branches retain PR checks. Unknown changes still receive full validation.
- Enabled Gradle task caching with native screenshots declared as outputs; removed duplicate successful artifact uploads and redundant release-download command setup while preserving every hash check.
- Each release now states actual signing/update behavior, package, version code and checksum before installation. Evidence includes test timings, certificate fingerprints and the full process audit.

This iteration primarily improves engineering and delivery. App behavior and the Æ / sky-blue experience are preserved. Actual in-place update continuity still requires owner-controlled signing and a Pixel check.
