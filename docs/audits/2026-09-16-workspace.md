# Everyday workspace: implementation and acceptance

16 September 2026 · Native Android personal preview · `improve-pixel-assistant` · Draft PR #7.

## Delivered paths

| Area | Implemented behavior | Acceptance boundary |
|---|---|---|
| Navigation | Today · **AI** · Library; shared Æ / sky-blue controls | Native light/dark/compact renders; Pixel gesture and keyboard review remains |
| Notes and memory | SQLite records, FTS, autosave, originals, pin, Trash/undo, people/projects and backlinks | Migration, conflict handling, deletion and recreation regressions |
| Tables | Collection membership, typed fields, editable cells through row forms, remembered text filter and sort | Native table capture and typed-value validation; no formulas or arbitrary joins |
| Text and audio | Editable dictation, saved-answer playback, sentence highlighting, speed, media service and microphone interruption | Lifecycle regression and compact layout; actual voice/audio routing needs Pixel |
| AI context | Up to five selected live records, bounded excerpts, provenance, saved routine results | Scope regression; citations still require checking |
| Today | Linked tasks, selected-calendar agenda, reviewed reminder times, Done/snooze, recurrence | DST and durable-claim regressions; Android timing/permissions require device drill |
| Beeper | Permission-separated reads/sends, selected snapshot, retained draft, exact recipient/text review | Provider URI validation; installed provider/account remains untested |
| Connected AI | Explicit HTTPS endpoint/model/key, encrypted credential, no silent fallback | Endpoint/scope validation; no real provider request or billing test |
| Scheduled AI | Foreground Nano reminder or separately approved JobScheduler connected routine; run ledger and linked results | Durable occurrence claims; background execution timing/provider behavior remains device/account-gated |
| Recovery | Legacy conversation support plus transactional whole-workspace export/merge | Oversized draft, rollback, conflict-copy and duplicate-import regressions |

## Defects found and addressed before handoff

1. **Provider query compilation:** Kotlin could not infer generic query result types. Added explicit chat/message result types; both Android variants compile.
2. **Oversized legacy draft:** SQLite CursorWindow could not read an existing 4.2 MB draft, preventing the old export-size guard from reporting correctly. Chunked reads preserve the draft and keep the original regression.
3. **Reminder disappearance:** scheduling a repeat cancelled the notification just delivered. Alarm replacement now preserves that notification; disabled reminders still cancel it.
4. **Editor recreation/navigation:** persisted editor identity, flushed autosave and reset dictation state on pause/navigation; unavailable recognition offers voice setup while preserving typing.
5. **Backup conflicts:** imported conversation/draft conflicts previously risked becoming invisible retained content. They now appear as Library notes without replacing live drafts.
6. **Reader interruption:** pausing before TTS initialization must prevent delayed auto-play. Added a focused regression.
7. **Compact playback control:** Previous wrapped mid-word. The visible control is Back, with Previous sentence accessibility semantics.
8. **Native capture correctness:** corrected dark qualifier ordering, opaque editor window coverage and stale child invalidation in software frame capture. Retained the existing painted-area assertion.
9. **Table view:** edits refresh immediately; typed numeric sorting and text filtering are remembered per collection.
10. **Interrupted routines:** process death could leave a repeating routine stuck on its claimed occurrence. Recovery marks it interrupted, never resends it and advances its next date; a regression covers one-time and repeating behavior.
11. **Static checker:** URL literals containing `//` were misidentified as comments. Fixed parsing and retained a small checker regression.

## Test necessity and iteration cost

The existing regression suite remains intact. Its oversized-draft test caught a real migration defect; deleting it to speed up the iteration would have hidden data-loss risk. A compact-chat assertion was updated to follow the new reader destination, instead of expecting the removed inline Stop speaking control. New workspace checks cover data integrity, action deduplication, scope and audio lifecycle. Native captures provide review evidence rather than asserting cosmetic wording.

Build 127's actual JUnit XML reports **15 workspace regressions in 0.707 seconds** and 27 native capture cases in 11.796 seconds. These are suite-reported times, not isolated benchmarks or end-to-end CI time; shared runtime initialization dominates some earlier suites. There is no evidence to justify deleting these checks for speed. Final evidence includes recomputable per-suite timings.

The established workflow still uses one full candidate gate for both APKs, skips duplicate Android jobs for the canonical draft PR, and avoids APK rebuilds for documentation-only acceptance updates. Compile/test failures were repaired from their specific diagnostics, without relaxing assertions or blindly rerunning failures.

## Physical acceptance still required

On the Pixel: export current data; install/update the Nano candidate; restore if a signature conflict requires reinstall; create/dictate/edit a note; link a task; save and use a source with AI; lock the screen during explicit playback; pause from headset controls; interrupt with microphone capture; check a reminder and calendar permission denial. With compatible Beeper, read a chosen chat and review a disposable message before explicitly sending it. With your own provider, first run one small foreground request, then one separately approved routine and inspect its ledger/result.

No actual Beeper message or provider request was sent during implementation. CI cannot certify Nano quality, AICore readiness, speech, accessibility, frame timing, battery, exact background timing or update-signature continuity. Preview signing remains ephemeral until the owner-controlled private signing secrets are provisioned. Export before any uninstall.

See the [everyday guide](../everyday-workspace.md) for controls, privacy and limits; [iteration workflow](../iteration-workflow.md) for the repeatable release contract.
