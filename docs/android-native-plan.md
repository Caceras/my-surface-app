# Ægentica AI: Android capability plan

September 14, 2026 · Target: Pixel 10 Pro XL · Native framework app · Preview branch.

## Quality bar

The goal is a dependable personal assistant: quick to enter, clear about listening and speaking, useful across Android surfaces, and safe to leave or interrupt. Feature count alone does not establish quality. Each shipped capability needs an entry point, cancellation/error behavior, accessible controls, tests, documentation, and a verified APK. Platform behavior and physical-device performance remain separate acceptance gates.

This plan inventories the relevant Android platform families, not every Android API. It separates work implemented in this pass from device-dependent validation and larger extensions. Nothing marked Later or Conditional is advertised as implemented.

## Delivery sequence

| Phase | Work | Acceptance |
|---|---|---|
| 1 · Identity | Ægentica AI name; Æ vector mark; sky-blue palettes; adaptive/themed icon; Android splash | Brand consistent in launcher, app, voice, widget, tile and current guides; contrast calculations and native renders |
| 2 · Adaptation | Bounded reading width, compact/large-font headers, scrollable voice content, cutout insets, keyboard shortcuts | Controls reachable in compact, landscape and large-type native layout tests; system back remains native |
| 3 · Native access | Searchable history entry, action shortcuts, pin Chat/Voice, responsive widget, tile feedback | Explicit intents reach the intended screen without sending a draft; compact widget retains controls |
| 4 · Useful actions | Timer, alarm, calendar draft, maps search and dialer through Android intents | Input validation, user-driven handoff, unavailable-app recovery; no claim that a returned intent means an action completed |
| 5 · Audio/privacy | Foreground MediaSession stop/pause; unplug handling; private-screen setting; widget preview opt-in; sensitive clipboard | Playback stops and cannot restart itself; all private app windows respect screen protection; no new cloud fallback |
| 6 · Verification | Regression tests, screenshots, capability inventory, README/setup/privacy/architecture refresh | All source checks, tests and APK builds pass; inspect fresh native captures; compare release APK identity/checksum |

## Capability inventory

Status legend: **This pass** = implement and verify in this delivery; **Existing** = retain and check; **Device gate** = implementation cannot substitute for physical verification; **Later** = concrete follow-on; **Conditional** = needs another product mode, owner setup, hardware, or platform eligibility.

| Family | Capability / gap | Decision and rationale | Gate |
|---|---|---|---|
| Identity | Adaptive icon, monochrome themed icon, splash | This pass: one Æ mark; sky blue; system-managed startup | Mask/theme renders; Pixel launcher check |
| Visual design | Light/dark, ripple, haptics, typography | This pass: semantic sky-blue tokens with readable text; consistent button semantics | Native renders and contrast checks |
| Windows | Edge-to-edge, keyboard, cutouts | Existing keyboard animation; this pass includes display-cutout insets | Gesture/three-button navigation and IME on phone |
| Windows | Tablets, foldables, desktop, split screen | This pass: maximum reading width and compact vertical layout; no orientation lock | Landscape/wide/large-font tests; physical resize gate |
| Navigation | Predictive system back | This pass: explicit opt-in; preserve platform navigation without an always-consuming callback | Android system transition/device gate |
| Input | Hardware keyboard | This pass: Ctrl+Enter send, Ctrl+N new, Ctrl+L focus, Ctrl+Shift+V voice; system shortcut help | Key routing and no unintended plain-Enter send |
| Input | Stylus handwriting / rich content | Existing standard EditText may benefit from system support; Later: attachment pipeline before rich content | IME/handwriting hardware; photo privacy |
| Accessibility | Headings, action roles, target sizes, reduced motion | This pass: native button semantics for custom controls and headings; existing 48 dp targets and motion preference | Native layout plus TalkBack/device gate |
| Accessibility | Full TalkBack, Switch Access, contrast, 200% text | Device gate: automated layout and color checks are partial evidence | Manual traversal/announcements; no certification claim |
| Launcher | Static shortcuts / pinning | This pass: Chat, Voice, History, Actions; pin Chat/Voice with platform confirmation | No private chat titles exposed to launcher |
| Discovery | Dynamic conversation shortcuts / AppSearch | Later: explicit opt-in and retention/deletion coupling before indexing private chats | Search latency, redaction and removal tests |
| Google surfaces | App Actions / Google shortcut integration | Conditional: separate integration/library and current Assistant eligibility | Real Google surface validation, not ordinary shortcut assumption |
| Widget | Resize, preview, battery | This pass: compact/full layouts, Æ identity, event-driven updates, no periodic polling | Small/large widget render and launcher resize |
| Widget | Private answer exposure | This pass: last-answer preview off by default; explicit toggle | No private text in default widget |
| Quick Settings | Tile, add request, lock handling | Existing unlock-before-launch; this pass branded label, setup subtitle and add-result feedback | Locked-device and rejected-add checks |
| Assistant role | Gesture / default assistant | Existing ACTION_ASSIST and role request; document configuration | Device gate: button/gesture eligibility varies |
| Assistant service | VoiceInteractionService / hotword | Conditional: separate service/session architecture and assistant eligibility; not permission to bypass foreground AI limits | Hardware/hotword capabilities, battery and privacy review |
| Cross-app text | Sharesheet / PROCESS_TEXT | Existing explicit text intake; this pass retains editable drafts and privacy handling | Source app support and cancelled handoff |
| Cross-app commands | Clock, Calendar, Maps, Dialer | This pass: explicit Android handoffs from Actions | No broad contacts/location/calendar permissions; destination app owns final UI |
| AI tools | Model-selected actions | Later: typed tool schema, validation and confirmation UI before any executable model output | Prompt-injection and misrouting evaluations |
| Audio | On-device recognition and TTS | Existing guided setup, installed voice selection and streaming | Real packs, accents, Swedish/English quality gate |
| Audio | Headset/media controls, unplug | This pass: foreground MediaSession stop/pause and audio-becoming-noisy handling | Bluetooth and wired-device checks |
| Audio | Background playback / notifications | Conditional: separate playback service and consent model; app currently stops when leaving | Foreground-service rules, notification permission, lifecycle tests |
| Notifications | Reminder schedules / actions / direct reply | Later: durable reminders with time zones, boot recovery and notification controls | No request for exact-alarm exemption without a qualifying need |
| Bubbles | Floating conversations | Conditional: eligible conversation notifications and a separate UI mode | Nano needs foreground; bubble presence does not authorize background inference |
| Live surfaces | Live Updates / ongoing activities | Conditional: only a genuinely ongoing supported activity | Do not simulate progress or add permanent clutter |
| Picture-in-picture | Floating voice/media | Not selected: not a useful fit for this text-first foreground assistant | Revisit only with a real video/media use case |
| Privacy | Screenshots / Recents | This pass: optional private-screen flag across activities and dialogs | User understands screenshots/casting are blocked |
| Privacy | Clipboard / backup rules | This pass: sensitive clipboard metadata, explicit backup/transfer exclusions | Clipboard preview behavior and export round trip |
| Security | Keystore / biometric app lock | Later: encrypted store plus recoverable key/backup design; a visual lock alone is not encryption | Device credential, invalidated-key and migration tests |
| Storage | SQLite history and background search | Later: transactional store for larger histories; current bounded JSON retained | Migration rollback, large-history performance, export compatibility |
| Files | Photo Picker / camera / OCR | Later: attachment UI, scoped access, bounded decoding and model input support | Image Prompt API + device quality; no camera permission before feature |
| Permissions | Contacts/calendar/location access | Prefer explicit Android handoffs first | Request direct access only for a defined user-authorized feature |
| Networking | Cloud models / MCP / sync | Conditional: explicit provider credentials, network/privacy mode, tool authorization | Keep on-device mode truthful; no silent fallback |
| Identity | Credential Manager / passkeys | Conditional on introducing accounts; no benefit for current account-free app | Do not add login just to use an API |
| Companion | Wear OS / Android Auto / TV / desktop companion | Separate clients and policies, not manifest switches | Supported interactions, distribution and hardware testing |
| Connectivity | NFC / Nearby / Bluetooth device control | Conditional on a concrete user workflow | Pairing, consent, reconnect and data security |
| Device signals | Sensors / Health Connect / geofencing | Conditional on explicit context-aware features | Minimization, permissions and battery; no speculative monitoring |
| System control | Accessibility automation / notification reading | Not enabled by default; separate opt-in product decision | Scoped disclosure and platform/policy eligibility |
| Performance | Startup, frame timing, battery, StrictMode | Device gate + Later benchmark module; do not claim 120 Hz from UI code | Macrobenchmark/Perfetto/Android Vitals evidence |
| AI quality | Quotas, refusals, context, truthfulness | Existing readiness/cancellation; Later fixed device prompt evaluations | AICore not available in CI; do not equate test fixtures with AI quality |
| Platform | SDK 37 / newer APIs | Later controlled toolchain migration after stable compatibility matrix | Current pinned SDK 36 retained; runtime-version behavior still tested on phone |
| Release | Stable signing and Play internal testing | Conditional on owner-controlled private signing and Play access | Private key continuity; export before debug-key reinstall |
| Operations | Crash/ANR reporting, support, updates | Later opt-in diagnostics without private prompts; Play pre-launch reports | Data policy, redaction, reliable reproduction and release rollback |

## Implementation contracts

1. Preserve package IDs and `surface-chat-v1` backups through the rebrand. Display names can change without creating a new data silo; signing continuity is still required.
2. Native actions are explicit user selections. Model text is never executed, phone calls use the dialer, calendar uses an editable draft, and clocks receive visible UI requests.
3. Shortcuts and widgets use explicit immutable PendingIntents. Private conversations are not silently indexed or published as launcher labels.
4. Widget previews default off. Screen protection is opt-in and affects screenshots as well as Recents. Clipboard metadata is defense in depth, not revocation of clipboard access.
5. Speech stays foreground-only. Stop/pause/unplug disables playback; it must not turn into an unexpected microphone restart.
6. UI widgets retain platform text selection, IME, back behavior and accessibility semantics. Avoid global back interception and decorative indefinite activity.

## Test and deployment plan

Automated: keep existing 118 regressions; add action input/intent tests, shortcut routing, privacy/widget tests, media-stop/unplug tests, keyboard commands, and native compact/landscape/wide/large-font/brand/widget renders. Run source/resource, documentation-link, and checker tests; build core and Nano. Fresh screenshots must be visually inspected; failed jobs block preview publishing.

Physical Pixel: install and verify build; prepare model and voice; exercise all launcher entry points, back gestures, keyboard, media buttons, Bluetooth/unplug, widget resizing, privacy toggle, external apps, rotations and large text. Record Android/AICore versions and evaluate real answer quality/latency. No local emulator or CI fixture can certify these outcomes.

Release: preserve the rolling preview URL, verify the published commit, package, version and SHA-256; publish direct APK instructions and document outstanding device/owner gates. Keep the existing draft PR reviewable.

## Primary references

- [Core app quality](https://developer.android.com/docs/quality-guidelines/core-app-quality)
- [Adaptive quality](https://developer.android.com/guide/topics/large-screens/tier-2-overview)
- [Adaptive and themed icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
- [Widget enhancements](https://developer.android.com/develop/ui/views/appwidgets/enhance)
- [Shortcuts](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts)
- [Keyboard actions](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/commands)
- [Predictive back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
- [Common Android intents](https://developer.android.com/guide/components/intents-common)
- [MediaSession callbacks](https://developer.android.com/media/legacy/audio/mediasession)
- [Sensitive clipboard](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)
- [Conversation bubbles](https://developer.android.com/develop/ui/compose/notifications/bubbles)
- [ML Kit capabilities and foreground limits](https://developers.google.com/ml-kit/genai)
