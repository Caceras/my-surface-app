# Testing and release checks

> **Everyday workspace update:** [Current behavior and limits](everyday-workspace.md) covers Today · AI · Library, SQLite migration, notes/relations/tables, explicit background reading, calendar/Beeper access and optional connected AI/routines. Earlier foreground-only and preferences-only descriptions below apply to the original chat/Voice path unless updated here.

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

Failed/cancelled Android runs retain **android-diagnostics** (JUnit XML, lint reports, screenshots); successful runs provide the complete **aegentica-evidence** bundle without a duplicate upload. Extract it, run `python tools/release_evidence.py verify <directory>`, and open `review.html`. The bundle records source/build/run, test counts, APK identities and hashes. [Iteration workflow](iteration-workflow.md) explains reproducible reproduction, visual review and release verification.

Additional regressions cover Settings/History/Actions cancelling hidden streams, dictation/draft retention, stale completion, quiet voice state, dark switch colors, private search and reachable History close. Native renders include dark Settings and the timer form. On a Pixel, also open each sheet during dictation/streaming and confirm no automatic restart when returning.

Both APKs also pass Android `apksigner verify`; certificate fingerprints are retained in the evidence bundle. This proves signature integrity, not continuity with a previously installed preview key.

## Narrow lint exceptions

Three source-local exceptions are documented beside the code: the Intent tile overload is used only under an explicit runtime API < 34 check because the PendingIntent overload does not exist there; `PresenceView` deliberately extends the framework ImageView and applies its own tint despite Nano's transitive AppCompat dependency; the Nano manifest's `tools:node="remove"` marker for LiveKit's camera permission is read by lint as a camera permission (`PermissionImpliesUnsupportedChromeOsHardware`), so that one element carries a local ignore. None of these exceptions disables lint globally. Other errors/fatals still block the release.

Screenshot capture drains immediate dialog layout work, remeasures the frame and verifies shared-header close controls before drawing. This catches missing or clipped exits that a painted-pixel threshold alone cannot detect.

## Iteration regression checks

`python tools/check.py` also exercises publication decisions without networking: stale source, wrong provenance, upload mismatch, draft repair, immutable public builds and rerun tags. Evidence rejects unlisted files and symlinks. Native regressions cover keyboard Done validation/correction, duplicate submission and the metadata-only app-info clipboard. The `settings-feedback` render checks that the feedback control remains reachable at the bottom of Settings. On Pixel, confirm Gboard Done and hardware Enter dispatch once, preserve an invalid timer for correction, and copy app info without losing the chat draft.

## Deep audit acceptance

Use the [current register](audits/2026-09-15.md) as the canonical list. New regression cases cover selection prompt privacy/recreation/validation, paused Voice recreation, failed question recovery, explicit chat retry replacement, conflicting archive IDs, calendar limits and reduced motion. Fresh captures add selection/error, failed-voice and narrow large-answer states. Capture remeasures the full tree, dispatches pre-draw, settles rendering and requires close bounds inside the PNG viewport. Inspect the image: bounds alone are insufficient. The release bundle includes `audit.html`, `audit.md` and `reports/lint-inventory.json`.

## Test value and scope

The [efficiency audit](audits/iteration-efficiency.md) records the 156-test baseline, three retired constant/wording checks, three selection checks consolidated into one, strengthened status validation and role-based chat selectors. The resulting suite has 151 cases; count itself is not a gate. All 22 native capture cases remain. `reports/test-cost.json` records measured suite and slow-case times on each full run; initialization is included, and this is advisory rather than a timing threshold.

Documentation-only changes run preflight. Canonical same-repository draft preview PRs rely on the push candidate gate; ready-for-review triggers merge-result Android validation. Other branches/forks keep PR validation. Unknown paths or unavailable diffs choose the full gate. Workflow dispatch forces full validation without publication. Full releases never use a selectively filtered test result. Gradle declares screenshots as test outputs so cached XML cannot silently omit its PNGs.

## Everyday workspace acceptance

`WorkspaceTest` covers transactional migration, revision conflicts, originals, Swedish search/trash, relational backup roundtrips, import rollback/conflicts, cascading deletion, DST recurrence, execution deduplication, selected AI context, URI encoding, sentence segmentation and note recreation. Native fixtures cover Today, Library, an edited note and a narrow dark/large-font workspace. Their data is synthetic. Existing 151 tests remain, with parser fixtures pointed at the new canonical store; no failing assertion should be removed merely because storage moved.

On Pixel: export first; install and verify version; create/dictate/edit a note; rotate/reopen; link it to a project/collection; edit a typed field; export/restore; inspect selected AI context; listen with screen locked and unplug headphones; set/complete/snooze a reminder; allow/revoke a selected calendar; inspect Beeper availability and a reviewed draft. Only send to a recipient you explicitly choose. Test connected AI with your own provider and spending cap, then one harmless routine; test disabling, permission revocation, offline delivery and reboot. No real user data belongs in CI.

## Transcription acceptance

`TranscriptionTest` covers verbatim append and part rollover, continuous platform recognition with restart after each pause, normal silence versus quick repeated failures, a visible pause after six busy/microphone failures with no hidden retries, resume only on request, yielding to in-app dictation, stop-time flushing of the last partial, polish review that leaves the original untouched, chunk budgets, recall scoping and opt-ins, Markdown rendering, Gemini request shape and shortcut/Add-task routing. `ShotTest` renders `transcribe-live` and `transcribe-ready-night`.

On the Pixel, record: engine label shown (Gemini on-device vs Android), Swedish and English accuracy on the same 5-minute passage, a 30–60 minute session with the screen locked (lines keep arriving; notification Pause/Stop work), behaviour during an incoming call, start sounds between segments, battery drop per hour, Polish quality with Nano, recall on a known phrase, Gemini preset with a real key, and the Markdown vault in a local folder opened by Obsidian. JVM tests cannot establish any of these.

## Assistant, providers and agent acceptance

`ProvidersTest` covers:
- streaming with OpenRouter keep-alive comments;
- errors inside a 200 stream, and 402, 403 and 200-with-error bodies;
- servers that ignore `stream`;
- per-provider request options (OpenRouter privacy routing and reasoning, Gemini reasoning, local `max_tokens`);
- images only for models that see them;
- the Gemini key surviving a switch to OpenRouter (legacy installs included);
- Tailscale-only plain HTTP;
- voice using the connected model only after its opt-in;
- model-list parsing;
- the settings presets.

`AssistantTest` covers:
- screen text reading order, with password fields skipped;
- the screen marked as data with the request last;
- the panel sending the screenshot only to a model that sees images, and offering explicit follow-ups;
- the chip leaving the screen out;
- leaving the panel cancelling generation and dropping late results;
- the direct-open explanation;
- the follow-up detector;
- Gemini Live receiving the screen text and exactly one frame;
- *Chat about this* opening aegentica.se with the screen text and a 1280 px JPEG screenshot (not offered before the screen arrives or with the screen left out), and a selection going as text alone, also after an answer.

`AgentTest` covers:
- room tickets and errors;
- tool validation and size limits;
- transcript order with interim text replaced;
- proposals that run only when tapped;
- the verbatim save;
- the service's Stop action.

`agent/tests` (CI job *LiveKit agent tests*) covers the token endpoint's access code, rate limit and ticket claims, tool forwarding and checks, settings validation and both voice modes. `ShotTest` renders `assistant-ready` and `assistant-panel` in light and dark (the floating, opaque sheet placed where Android puts it, over a stand-in app screenshot) and `agent-voice`.

On the Pixel:
- choose Ægentica as digital assistant and invoke it with the corner swipe and power button, over Sheets, Gmail and a banking app (which should arrive empty);
- check that the sheet is opaque in the light and dark themes, rises above the keyboard, stays above the gesture bar and closes on a tap outside;
- with a third-party offline voice app (for example HayaiTTS), confirm the reading-voice picker lists its voices and that a long text reads without pauses between sentences;
- with the phone language set to English (Sweden), tap the microphone in chat and in the assistant sheet: it should switch to an installed English pack and listen, without a "not ready yet" message;
- with Google as Android's default and HayaiTTS installed, confirm Read aloud uses a HayaiTTS voice, the picker lists HayaiTTS first with voice names, and Google's voice is never used unless picked;
- select text in Chrome and Messages: *Ægentica* (in the ⋮ menu) opens the genie sheet over the app; on editable text, *Replace selection* replaces it; no Listen, Summarise or Proofread entries remain;
- select a word on aegentica.se inside Ægentica: *Ægentica* is the first toolbar item (with the rabbit), Google's Translate is second and still opens; select text in the site's message box, ask the genie to rewrite it and tap *Replace selection*: the box shows the new text and the site reacts to it as typing;
- Settings → *Ægentica keys* → **Turn on**, enable it, come back: the keyboard picker opens; choose it. In Messages, Gmail, Chrome and WhatsApp: type Swedish with å ä ö, shift and caps lock, long-press accents, symbols and emoji, hold delete, slide on space, Enter sending in a chat and searching in Chrome; switch to English (US) in Settings → Languages; dictation; the globe back to Gboard. Hold space and say a request: it starts when you let go. Watch the ideas line finish words and offer the next one after a few messages. Tap Æ: the panel keeps the keyboard's height, the agent streams with steps, Insert and Replace work and Undo restores; type and speak to steer; close the panel mid-answer and reopen it; drag up for the full sheet and tap *Replace selection* there: the text lands in the same field. Check a banking app's password and one-time-code fields, a card number and Chrome's incognito: plain keys only, Æ faded;
- share a link from Chrome, a screenshot from Photos and a PDF from Files to *Ask Ægentica*: the genie opens over the app with it; add the Ægentica tile and widget and watch them change while the genie works from the keyboard; long-press the icon for *Ask Ægentica*; write an NFC tag in Settings and tap it with the screen on;
- keyboard feel on the Pixel 10 Pro XL: no dropped letters when typing fast, haptics following Settings → Sound & vibration → keyboard vibration, light and dark themes, TalkBack explore-by-touch announcing keys;
- Nano answering about a screenshot in Swedish;
- OpenRouter with a real key: streaming, the model picker, and a spent credit limit;
- a local Ollama server over Tailscale;
- Agent voice end to end on LiveKit Cloud: Swedish turn-taking, sharing another app's screen, a timer proposal, Stop from the notification.

## Phone agent acceptance

`PhoneAgentTest` covers:
- the shared playbook ([playbook](playbook.md)): unique names, declared required fields, a `why` on every acting tool, and the read and act tier lists;
- OpenRouter's stream: events split anywhere (CRLF and comments), tool calls folded by index across chunks, usage with cost, reasoning details, a call cut off mid-arguments, an error inside a 200 stream;
- the request: the screen fenced as data that cannot close its fence, the screenshot only for a model that sees, tools offered now plus tools used earlier, `tool_choice: none` on the last turn, `data_collection: deny`, Claude's cache field;
- the loop: an answer with its cost; reads running on their own and actions waiting for Allow, with "always for this app" remembered; a refusal reaching the model; steps that always ask never being remembered; a new instruction setting aside a pending Allow; Stop ending the run and the tools; the step limit's last turn without tools; unknown tools, hands that are off, a 402, and no connection;
- the OpenRouter sign-in's S256 challenge (RFC 7636's example) and code exchange.

`AssistantSheetTest` covers the sheet on the agent: the screen and screenshot on the first question only, the streamed answer and its label; Gemini Nano offline, when the connection drops mid-request, and without a key (with the sign-in chip); steering while it works and Stop on leaving; window insets that keep the sheet clear of the navigation bar and keyboard and a scroll area that never shrinks while an answer streams; and `withText` handing an answer back to its caller.

On the Pixel:
- sign in from the sheet's chip: Chrome opens OpenRouter, *Back to Ægentica* returns, and the next invocation answers with the agent;
- ask about Sheets and Gmail with the screen on; check the answer uses the screenshot;
- hold the mic, speak, let go: it sends and reads the answer aloud; talk again while it works: it changes course;
- type a long question, send it while the keyboard is up, and watch a long answer stream: the sheet stays above the gesture bar and never shrinks;
- turn on airplane mode: the sheet says *Offline · Gemini Nano on this phone* and answers.

## Hands and genie acceptance

`HandsTest` covers the snapshot (stable refs across snapshots and `@e` refs, roles, bounds, rows named by their words, hidden elements left out, password values masked and never found), secret fields by flags, input type and Swedish and English words, steps that always ask from a button's words or id, every refusal (missing or long `why`, bad or stale refs, points off screen, swipes off screen, link schemes, typing into a non-field or too much text, Ægentica's own controls), the password hand-over, tiers that the model's words cannot lower (a Send button, *submit*, `tel:` and `sms:`, banking and payment apps), opening apps by name, "always for this app", steps running after the rabbit's flight with the screen change reported, typing, scrolling, Back, Home, opening apps, screenshots (and protected screens), waiting, Stop refusing every step until the next question, the service going away, and touches pausing the hands only when they are not the hands' own.

`RabbitOverlayTest` covers the genie's windows (a stage that never takes a touch, a panel that takes the keyboard only while typing and watches touches elsewhere), the bar, bubble and expanded forms, closing without leftovers, Stop in the bar and in the bubble, take-over and Continue, the password hand-over, where the rabbit lands and its flight there and back, an Allow prompt in the bar and the notification letting the step run, the done notification, and the notification's Stop reaching the agent.

On the Pixel:
- turn on Ægentica hands, including *Allow restricted settings*;
- swipe from a corner over Settings: the genie bar opens listening; ask it to turn on Wi-Fi and watch the rabbit fly to the switch before Allow;
- hold the rabbit, drag it, fold it, unfold it; turn on *Remove animations* and check nothing moves;
- touch the screen while it works: it pauses; Continue hands it back;
- ask it to log in somewhere: it stops at the password field and asks you to take over;
- ask it to send a message: the Send step always asks;
- keep the microphone open and steer it mid-task; talk over its voice;
- lock the phone during a task: the notification shows the step, Stop works, Allow asks to unlock;
- measure battery over an hour with the service on and idle.

## Ægentica home acceptance

`ShellTest` covers the bridge (context kept until the page says hello, a screen with its JPEG screenshot under protocol version 1, a screenshot that is not a JPEG data URL or is too large left out, speech with pause and resume, dictation, sign-in, the voice picker and menu, malformed requests and non-http links ignored, a new page load must say hello again), the screenshot as a data URL (a Pixel-sized screen becomes a JPEG of at most 1280 px; a busy one is compressed harder and made smaller to fit, or dropped), the assistant's hand-over to aegentica.se (held in memory, never in the intent, taken once, dropped after a minute), the localhost sign-in on a real socket (OpenRouter's redirect, the code, the reply page), the Ægentica shell (aegentica.se loads, the user agent names the shell, file access is off, only `aegentica.se` and `www.aegentica.se` stay in the app, a share is consumed once), the microphone rule (aegentica.se after Android's prompt, never the camera, never another site, never Glimt's page), Glimt's shell, and the file picker returning one file, several or none. `ReaderRepairTest` checks that a Swedish reply is read with a Swedish voice, that the reading state reaches the page, and that a pause keeps the reading open while closing the reader ends it. The site's side is tested in `Caceras/aegentica-app` (`scripts/test-phone-app.mjs` and `scripts/test-phone-app-browser.mjs`); Glimt's in `Caceras/awesomeseo-monorepo` (`apps/chatbar/tests/android.test.ts`).

On a Pixel:

- open Ægentica: aegentica.se loads; sign in with the password; the same chats as on the computer appear;
- with Settings → Voice set to *This device*, *Read aloud* a reply: a HayaiTTS voice reads it without pauses, pause and resume work, and a Swedish reply gets a Swedish voice when one is installed;
- tap the microphone: words appear as you speak, offline;
- share a link from Chrome, and text from another app, to Ægentica: each becomes a new chat's draft;
- start live voice: Android asks for the microphone once, then the conversation works;
- **Phone** in the side menu opens the menu; *Browse the web with Glimt* opens Glimt, and Back returns to Ægentica.

## Recorded audio acceptance

`AudioTest` drives `TranscriptionService` in record mode with Robolectric's `MediaRecorder` and a scripted Gemini HTTP seam: parts listed with durations, rollover at 25 minutes, paused time excluded, permanent deletion removing files, no empty note for an empty recording, recovery of unlisted parts after process death (empty files removed, unknown files untouched), Gemini request shape, part offsets, upload deletion on success/error/cancel, cancel on leaving the note with the late result dropped, editor transcription into an empty note, speaker renaming in the working text only, and shared audio import with an untrusted file name. `ShotTest` renders `transcribe-record-ready` and `recording-note`.

On the Pixel: a 60-minute locked-screen recording (two parts, playback across the boundary, battery), force-stopping the app mid-recording and reopening Transcribe, an incoming call while recording, Gemini Transcribe on a real Swedish meeting with 2–4 speakers (labels, time stamps, cost in AI Studio), sharing a Pixel Recorder M4A, and Name speakers.

## Live voice and Insights acceptance

`LiveAndInsightsTest` drives `LiveSession` and `LiveActivity` through a fake transport and fake audio: setup content and tool declarations, no microphone before `setupComplete`, PCM chunk encoding, read-only tool answers that exclude unrelated notes and reject unknown functions, playback and barge-in flush, transcript fragments merged into lines, a saved verbatim note, resumption with the latest handle after `goAway`, stale-socket fencing, a rejected-key message, and ending plus saving when the screen pauses. Insights covers JSON parsing around prose/code fences, review labels (existing vs new), transactional apply with provenance and exact undo. `ShotTest` renders `live-conversation`, `insights-review` and `verbatim-changes`.

On the Pixel: Live with a real key (connect, Swedish question about a known transcript, citation of the note title, interrupting by voice, loudspeaker echo vs headphones, a 12+ minute session crossing a connection reset, airplane-mode failure message), Insights quality with Nano and with Gemini on a Swedish transcript, and the Transcribe tile from the lock screen.

## Selection and keyboard acceptance

`KeyboardTest` covers typing (sentence capitals, caps lock, a whole emoji per delete, the double-space full stop), layouts by language and field (å ä ö, US, number pad), Enter following the field's action or giving a new line; plain keys in password, PIN, number, incognito and hinted card or code fields with nothing read and no genie; the genie panel reading the field and app, streaming without touching the field, Replace and Undo, typed requests that steer, Allow cards from the agent, Insert into an empty field; and the full sheet's Replace landing only in the field it came from and never over changed text. `ShotTest` renders `keyboard-keys` and `keyboard-genie` in light and dark.

`GenieDoorsTest` covers sharing a picture and a text file (both reach the genie's screen context, the picture scaled), the tile, shortcut and widget opening the genie and showing its status in words (step words only with previews on), and NFC requests: signed by this phone they start at once, changed words or foreign tags are shown and start only after *Start*, other links are ignored. `KeyboardTest` also covers the ideas line (next words and completions from the owner's own typing, nothing learned in an email field), the per-app switch and answer links that open only on a tap, and dragging up to continue the same conversation in the sheet (in the app's task, not Ægentica's) with its answer waiting back in the keys.

`SelectionTest` covers the toolbar on Ægentica's own pages: Ægentica in the first (assist) slot, Android's own smart action moved after it and still running with its original item, our own selection entry not listed twice, other apps' entries kept and a lone Paste toolbar left alone; a selection in one of our fields or on the page handed to the genie sheet, replaced only after the sheet answers with Replace (and typed into the page as quoted data), and password fields never handed over; and the manifest's single *Ægentica* entry pointing at the genie sheet.
