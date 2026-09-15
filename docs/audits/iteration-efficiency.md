# Request-to-install efficiency audit

15 September 2026 · baseline source `06048dfaa66c1de6d30eb04c01344c387b2ed908` · [build 112](https://github.com/Caceras/my-surface-app/actions/runs/34939156609).

## Finding

The main opportunity is avoiding unnecessary Android runs and release churn, not indiscriminately deleting regression tests. Test count is not a quality target. Retain a test when its distinct failure would matter to the user, and when its maintenance/runtime cost is justified. Remove tests that merely freeze styling, guess semantics from word choice, duplicate the same interaction, or can pass without observing the intended behavior.

Baseline measured from GitHub job timestamps: preflight 7 s, Android quality 127 s (106 s Gradle invocation), publication 26 s; first job start to final completion 164 s. JUnit suite-reported time totals 42.464 s, including initialization. These are one-run observations, not performance guarantees. The slowest first test includes Android/Robolectric startup; deleting the timer test does not remove that shared startup cost.

## Optimization register

| ID | Gap | Change | Acceptance |
|---|---|---|---|
| F01 | A README typo builds and publishes new APKs | Conservative changed-file classification; known documentation changes run preflight only | Policy tests cover mixed paths, unknown files, unavailable history and manual override; verify a docs-only live push |
| F02 | Every active preview draft receives both push and merge-checkout Android builds | Canonical preview push is the draft candidate gate; same-repository canonical draft PR runs preflight only; ready-for-review triggers full merge validation | Forks, other branches and ready PRs keep Android checks; live push/PR job records |
| F03 | Tests enforce 20dp header padding, a 34-character chip limit and absence of the word “this” | Retire three policy-by-constant/wording tests; retain native size/insets/target/render evidence | 3 removals documented below; no claim of meaningful runtime savings |
| F04 | Three tests repeatedly open selection Ask to inspect the same suggestions | One isolated flow verifies suggestions, scroller and editable staging; never reuse an unrelated latest dialog | Equivalent assertions in one fixture; two fewer activity launches |
| F05 | Status test passes when any text except an obsolete loading string appears | Require the provider's known status in a visible status view | A missing callback or wrong/hidden status fails |
| F06 | Direct-child message selectors confuse controls with answer text; exact switch count blocks additions | Shared role-based message selector and nonempty semantic switch checks | Existing chat assertions stay intact; added controls/nesting do not masquerade as answers |
| F07 | Gradle task caching is not enabled; screenshot files are untracked task side effects | Enable build cache and declare native renders as test outputs | Android build succeeds with evidence present; cache benefit depends on inputs/runner, not guaranteed each build |
| F08 | Successful runs upload diagnostics twice; APK/PNG artifact compression wastes CPU | Success keeps the complete evidence bundle; failure/cancellation keeps diagnostics; use measured fast compression for evidence upload | Successful evidence verified, diagnostics retained on failure path |
| F09 | Release verifier starts seven download commands per release | One CLI batch per unique/rolling release, still compare every downloaded file hash | Corrupted second asset regression plus live publication verification |
| F10 | Owner sees signing caveat only after install failure | Each release states actual signing mode, package, version code and Nano checksum before changes; evidence records public certificate fingerprints | Parse/compare both flavor certificates; signature continuity still needs owner key/device |
| F11 | Timing and test necessity must be rediscovered every iteration | Generate recomputable per-suite/top-ten test timings in evidence; this document records necessity decisions | Report recomputed from original XML; timing is advisory, never a flaky hard gate |
| F12 | Agent repeats broad audits, all screenshots, giant PR histories and unnecessary pushes | Scope by requested behavior; review changed states; bounded diagnostics; short current PR acceptance linking historical releases | Contributor/agent workflow updated; source, gate and install evidence remain explicit |

Implementation is tracked in the source change. Final CI, visual acceptance, installation identity and publication evidence are recorded in the PR and exact build release. Documentation-only changes do not manufacture a new APK or assert that existing installed bits changed.

## Compression measurement

A proposed no-compression shortcut was rejected after measurement on the actual build 112 bundle. Local Python ZIP level 0 took 0.050 s / 34,887,729 bytes; level 6 took 1.302 s / 13,864,931 bytes; level 1 took 0.484 s / 15,010,343 bytes. APK contents still benefit substantially from compression. Artifact upload uses level 1; these local timings are not a GitHub transfer-speed guarantee. The downloadable release ZIP retains its deterministic compression.

## Test-by-test decisions

The three removed tests are `ScreenTest.spacing is density-scaled, not raw pixels`, `LogicTest.there are suggestions and they are short enough to be tappable`, and `LogicTest.the chat openers do not refer to a selection that is not there`. The spacing test copied the current 20dp value rather than comparing behavior across densities. The latter two inferred fit/meaning from character count and a banned word. Native rendering, inset/target checks and human copy review are the appropriate evidence.

`Ask offers its suggestions instead of a blank box`, `tapping a suggestion fills the prompt box`, and `the suggestion row scrolls rather than wrapping off screen` become one selection interaction. The original offer test could inspect a previously opened dialog; the combined test always creates its own.

The status assertion is strengthened, not deleted. The switch test checks actual switches' theme/touch semantics without requiring exactly three switches. Privacy defaults and behavior remain independently tested. Message roles replace incidental direct-child view nesting; no assertion about answer contents, retention or failure recovery is weakened.

Net Android test count: **156 → 151**. This is principally a maintenance improvement. Keep all 22 native captures: 5.5 seconds for the baseline suite is reasonable for fresh light/dark/large-text/compact evidence. Most other regressions cost milliseconds after initialization. Do not add screenshot-diff baselines or a device farm to every small iteration without evidence that their benefit pays for their cost.

| Baseline suite | Cases | Reported seconds | Decision / distinct value |
|---|---:|---:|---|
| CoherenceTest | 6 | 20.175 | Keep: navigation cancellation, draft retention, private search, keyboard dispatch, metadata allowlist. Relax only fixed switch count. Includes initialization cost. |
| ConversationTest | 22 | 10.591 | Keep: context wiring, rendered final text, import/export, retention and late-callback races. Pure helper tests cannot prove activity wiring. Improve message selector. |
| ShotTest | 22 | 5.500 | Keep: real native drawing and size/theme evidence. Not proof of device behavior or automatic visual approval. |
| ScreenTest | 26 | 2.091 | Remove fixed padding, consolidate selection, strengthen status. Retain keyboard/insets/selection/entry point checks. |
| VoiceTest | 26 | 1.813 | Keep: recognizer teardown, streaming order, setup re-entry, stop/quiet, opt-in continuation and typed/voice continuity. These represent different state transitions. |
| DeepAuditTest | 12 | 1.356 | Keep: real failures in lifecycle recovery, conflicting archive IDs and selection submission. |
| NativeFeaturesTest | 8 | 0.647 | Keep: intent validation, privacy defaults, media/keyboard routing. Button vs IME dispatch are distinct integration paths. |
| SpeechOutputTest | 7 | 0.113 | Keep: offline voices, initialization, chunk limits, audio focus and headset interruption. |
| MarkdownTest | 4 | 0.083 | Keep: rendered spans differ from plain-text speech cleanup. |
| LogicTest | 20 | 0.034 | Remove two wording/length policies; keep cheap prompt budgets, order, echo and speech parsing boundaries. |
| KeyboardMotionTest | 1 | 0.034 | Keep: inset animation reset protects composer placement. |
| StreamUpdatesTest | 2 | 0.027 | Keep: coalescing and cancellation timing; no real sleep required. |

The source checker overlaps compiler checks intentionally: it catches common resource/manifest mistakes in under two seconds without an SDK or remote round trip. Its 11 mutation regressions protect that fast feedback. Evidence/publication tests cover wrong source/variant, failed/empty tests, missing assets, path traversal, private accidental files, stale publication, immutable downloads and partial repair. Keep them: they are SDK-free and cost a fraction of a second. Add only focused cases for the new skip-build policy, batch-download integrity and signing report parsing. No arbitrary minimum test count is a gate.

## Request through installation

| Stage | Optimized default | Remaining boundary |
|---|---|---|
| Request | Translate intent into a concrete behavior and acceptance check; use current branch/register | Ask only for a material missing input; do not restart a broad redesign each turn |
| Inspect | Read relevant code and latest verified evidence; inspect local/remote changes | Fresh captures when judging changed visuals, not automatic full recapture for prose |
| Edit | One coherent source/docs batch, smallest useful regression | Avoid cosmetic implementation-mirroring tests and mass test renaming |
| Local check | One `python tools/check.py`; focused Gradle test while editing when SDK exists | Full Android gate still required for a release |
| CI | Documentation preflight or full Android by conservative scope; draft duplicate work removed | Ready PRs validate merge result, forks get no private key; unknown paths build |
| Diagnose | Read failed step and specific XML first; one diagnosed infrastructure retry | Do not rerun green checks or conceal flaky failures with retry loops |
| Publish | Unique draft → upload/download/hash check → publish → serialized rolling alias | Alias updates remain non-atomic; use exact build URL |
| Handoff | One Nano download, installed version, key/update caveat and focused “what to try” | Core is demo; evidence ZIP is not an installer |
| Install | If Android offers Update, keep data in place; verify build and history | Signature conflict: export before uninstall; never silently clear data |
| Phone check | Small changed-flow smoke test; broad matrix before wider release | Hardware Nano/audio/gestures cannot be certified by host tests |
| Feedback | Copy app info, redacted steps, expected vs actual; attach exact build | No automatic transcript, telemetry or issue posting |

## Open optimization gaps

1. **P0 — signing continuity:** four private repository secrets are supported but not provisioned. Owner-controlled key custody and two actual successive in-place updates are needed. Public storage, a cache or an artifact is not an acceptable substitute. This is the largest remaining installation friction.
2. **P0 — device acceptance:** no connected physical Pixel. Record installed version, AICore, speech language and one typed/voice follow-up, then changed flows. CI cannot perform this step here.
3. **P1 — default branch/discovery:** main still contains the older experience; this work stays on the preview branch and draft PR. The README points to the right preview, but broader discovery needs an authorized reviewed merge/default-branch decision.
4. **P1 — prove cache benefit over several comparable runs:** enabled caching does not make version-sensitive tasks reusable. Inspect task outcomes and timings before further parallelism or configuration-cache complexity.
5. **P1 — protected broad release:** Play/internal rollout needs private signing, device acceptance and protected promotion. Do not exchange these for faster personal previews.
6. **P2 — runtime and accessibility debt:** retain [D01–D08](2026-09-15.md#open-acceptance-gates); this process audit does not close them.

## Sources

[GitHub job conditions](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-jobs-with-conditions) let preflight remain visible while expensive jobs skip. [PR events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request) include ready-for-review; draft candidate validation differs from merge validation. [Gradle build cache](https://docs.gradle.org/current/userguide/build_cache.html) and [declared outputs](https://docs.gradle.org/current/userguide/incremental_build.html) inform the cache change. [GitHub CLI batch download](https://cli.github.com/manual/gh_release_download) supports multiple patterns; every file remains hash-checked.

## Verified candidate

[Build 116](https://github.com/Caceras/my-surface-app/actions/runs/34950871987), source `10a2dd76437382b2dda6003717e288264f0cc84e`, passed **151 Android JVM tests** with zero failures/errors/skips, both APK builds and signature checks, and lint with zero errors/fatals (158 existing warnings). Local preflight passed 11 source-checker, 3 scope, 11 evidence and 10 publisher regressions plus 23-document link checking. Artifact `10388998192` was downloaded and independently verified, including signing fingerprints and recomputed test timings. Final chat-conversation and selection captures were inspected; semantic tags and test consolidation did not change the visible interaction. The candidate is a native core fixture check, not physical Nano acceptance.

Actual build 116 costs: preflight 9 s, Android quality 200 s (161 s Gradle), publication 16 s (12 s publisher). Eight of 96 Gradle tasks came from cache. The full Android run was **slower** than the 127 s baseline; runner/input differences prevent attributing a per-build speed improvement to the changes. The supported gains are fewer unnecessary runs, fewer download-command invocations, smaller maintenance surface and measured compression tradeoffs. Evaluate comparable future timings before claiming runtime savings.

[Draft PR run 117](https://github.com/Caceras/my-surface-app/actions/runs/34950876570) passed preflight and skipped Android/publication as intended. Superseded run 114 cancelled and retained `android-diagnostics`, exercising the failure/cancellation artifact path. Full successful run 116 retained only the complete evidence bundle, 15,274,875 bytes.

[Install Nano 3.0.116](https://github.com/Caceras/my-surface-app/releases/download/preview-improve-pixel-assistant-build-116/aegentica-ai-nano.apk) · [Evidence ZIP](https://github.com/Caceras/my-surface-app/releases/download/preview-improve-pixel-assistant-build-116/aegentica-evidence.zip). Published Nano SHA-256: `2f425b87f37438dd343e55fde302b694c3213a661ec1b7b6523431386d0bde06`. Signing mode is **ephemeral-debug**; do not assume in-place update compatibility. The later documentation-only acceptance commit intentionally keeps this same APK; its CI result is recorded in PR #7, avoiding another binary merely to record acceptance.
