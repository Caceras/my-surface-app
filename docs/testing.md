# Testing and release checks

## Reproduce CI

Use JDK 21, Gradle 9.7.1, Android SDK platform 36 and build-tools 36.0.0. Configure `ANDROID_HOME` or a local `sdk.dir` in `local.properties`. The Gradle version is pinned in CI; this repository does not contain a wrapper.

```bash
python tools/check.py
gradle testCoreDebugUnitTest lintCoreDebug lintNanoDebug assembleCoreDebug assembleNanoDebug --no-daemon --stacktrace
```

JUnit/Robolectric reports: `app/build/reports/tests/testCoreDebugUnitTest/`.
Native screenshot output: `app/build/screenshots/`.
CI retains test/lint reports and screenshots on all runs, and packages verified APKs with evidence on success. Downloadable phone previews are APK release assets; workflow artifacts are ZIP files.

## What is covered

| Layer | Evidence |
|---|---|
| Static checker | Resources, components, flavor boundaries, permissions, workflow invariants |
| JVM logic | Prompt handling, Markdown, conversation context, backup parsing and retention |
| Activity interaction | Send/stop, draft recovery, stale callback rejection, searchable archives, voice setup re-entry |
| Streaming | First partial arrives immediately; bursts are coalesced; pending renders cannot replace final/cancelled results |
| Speech | Offline voice selection, queued initialization, chunk limits, focus interruption, recognizer teardown |
| Native layout | Actual activity drawing through Skia, chat reading position, settings containment, screenshot states |
| Build | Core and Nano compile against their real dependency sets |

Native graphics are required for tests that depend on text wrapping and measured scroll ranges. Assert that a fixture actually exceeds the viewport before testing scroll behavior. Prefer meaningful interaction checks over snapshots of private implementation details.

Screenshots are inspection evidence, not a pixel-perfect comparison gate or proof that every font size and orientation works. Keep the source commit and run alongside any published screenshot. Demo/core screenshots must not be presented as proof of a real Nano answer.

## Native capability regressions

NativeFeaturesTest exercises Clock validation and visible intent handoff, shortcut navigation with a retained draft, keyboard send/new behavior, widget privacy, screen/clipboard protection and media interruption. SpeechOutputTest checks that a headset Stop mutes future generated sentences. ShotTest adds Actions, landscape voice, a wide reading column, 200% text, compact widget and the adaptive Æ icon.

Use real framework view measurements before asserting scroll or touch geometry. Color contrast calculations cover text/background token pairs; they do not certify all rendered states. The evidence report names the screenshot source commit and distinguishes fixtures from real Nano output.

## Physical Pixel checklist

Before calling a release ready for wider use, record build number, Android/AICore versions, speech language, keyboard, font/display size, and output device. Do not mark this checklist passed based on CI.

- [ ] Install/update with the intended signing key; export/restore survives a required reinstall.
- [ ] Prepare the model and both speech components; test missing, queued, failed and successful downloads.
- [ ] Type, revise, send, stop, retry, and ask follow-up questions. Verify interrupted questions remain recoverable.
- [ ] Open/close the keyboard repeatedly, including gesture back; check composer motion and text focus.
- [ ] Read earlier messages during a long answer, then tap Latest reply.
- [ ] Dictate into an existing draft, including silence, errors, permission denial, and interrupted capture.
- [ ] Test Voice, Type, Quiet voice, Stop response, Stop speaking, and opt-in Keep talking.
- [ ] Interrupt playback with a call or another audio app; confirm no unexpected microphone restart.
- [ ] Background, lock, rotate and reopen the app; confirm microphone/speech stop and no stale answer appears.
- [ ] Exercise assistant gesture, widget Type/Talk, tile, launcher shortcuts, share text and editable/read-only selection.
- [ ] Test Actions against installed Clock/Calendar/Maps/Dialer apps, including cancel and absent-handler behavior.
- [ ] Pin shortcuts, resize widgets, toggle preview privacy, and verify Private screen for activities/dialogs.
- [ ] Verify Ctrl shortcuts, media Pause/Stop, wired unplug, splash and themed launcher icon.
- [ ] Check TalkBack order/actions, large font/display settings, dark theme, contrast, landscape and reduced animation.
- [ ] Check model quality and refusal/error behavior with representative English and Swedish prompts; speech language support is not evidence of answer accuracy.

For performance, measure actual frame timing, startup, first-token/first-audio latency, memory and battery on hardware. The repository has no automated benchmark proving a frame-rate, power-use or latency target.

## Release gate

1. Static checks and checker tests pass.
2. JVM tests pass, including changed flows.
3. Both flavors pass lint and build.
4. CI publishes only the mechanically verified commit; inspect fresh native screenshots before recommending that preview to a user.
5. Record remaining physical-device checks and signing limitations in the handoff.

A green build is necessary; it is not an approval for a Play Store rollout. Production signing, privacy disclosures, device testing and a support process remain separate work.

## Unified iteration gate

Run `python tools/check.py` for local preflight, then `gradle testCoreDebugUnitTest lintCoreDebug lintNanoDebug assembleCoreDebug assembleNanoDebug --no-daemon` in the pinned SDK environment. CI uses one invocation with the build number. Lint errors/fatals and missing/failed/skipped JVM results block release evidence. Lint warnings remain visible.

Every run retains **android-diagnostics** (JUnit XML, lint reports, screenshots); successful runs additionally provide **aegentica-evidence**. Extract it, run `python tools/release_evidence.py verify <directory>`, and open `review.html`. The bundle records source/build/run, test counts, APK identities and hashes. [Iteration workflow](iteration-workflow.md) explains reproducible reproduction, visual review and release verification.

Additional regressions cover Settings/History/Actions cancelling hidden streams, dictation/draft retention, stale completion, quiet voice state, dark switch colors, private search and reachable History close. Native renders include dark Settings and the timer form. On a Pixel, also open each sheet during dictation/streaming and confirm no automatic restart when returning.

Both APKs also pass Android `apksigner verify`; certificate fingerprints are retained in the evidence bundle. This proves signature integrity, not continuity with a previously installed preview key.

## Narrow lint exceptions

Two source-local exceptions are documented beside the code: the Intent tile overload is used only under an explicit runtime API < 34 check because the PendingIntent overload does not exist there; `PresenceView` deliberately extends the framework ImageView and applies its own tint despite Nano's transitive AppCompat dependency. Neither exception disables lint globally. Other errors/fatals still block the release.

Screenshot capture drains immediate dialog layout work, remeasures the frame and verifies shared-header close controls before drawing. This catches missing or clipped exits that a painted-pixel threshold alone cannot detect.
