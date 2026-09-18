# Presentation and read-aloud repair

18 September 2026. Scope: the owner's reports of robotic read-aloud, uneven text streaming, literal emphasis markers and excessive promotional UI copy.

## Baseline

PR #7 was merged at `fc7e90cc1ce55b5c6413e725840a948cf1e8fbf8` after the full merge-validation run 133 succeeded. Its source tree is `9e1cdb9b8fa1706285bc2710e3b73846e55e890c`. This pass preserves that workspace implementation, package IDs, backups and privacy defaults.

## Findings and repairs

| Finding | Repair | Acceptance |
|---|---|---|
| Reader selected an arbitrary matching offline voice, unlike foreground speech | Rank installed, language-matching voices by quality; use deterministic locale/latency tie-breaks; check setVoice success | Synthetic voice-selection regression; acoustic quality remains device-dependent |
| Reader flushed the engine for each sentence and rewrote the complete saved text repeatedly | Bounded three-sentence look-ahead using QUEUE_ADD; generation-fenced callbacks; write only changed playback state; prefer word boundaries in long chunks | Queue/pause and segmentation regressions; real gaps and headset routing need hardware |
| Initialization failure could be cleared by Play despite no ready voice | Preserve a visible setup error and do not enter a false playing state | Initialization-failure regression |
| Formatter handled bold but not single-marker emphasis or literal code | Shared bold/italic/nesting/code parser with matching strip behavior | Formatting and incremental-span regressions; this remains a subset, not full CommonMark |
| Every stream timer painted the full TextView again | Latest-value display-frame coalescing, duplicate suppression and a retained editable chat buffer | Existing cancellation/burst tests plus duplicate and buffer regressions; no measured FPS claim |
| Echo checking searched the growing reply once for every 50-character instruction window | Index fragment hashes once and scan the reply; verify actual text on every hash hit | Compare against the original exact substring rule |
| Welcome and settings copied marketing language into task UI | Remove welcome slogans/cards and use direct settings labels; constrain decorative generated headings in the system prompt | Native CI captures; no wording-only tests added |

## Verification boundary

The candidate must pass the repository's full preflight, Android JVM tests, lint, both APK builds, signature verification and release-evidence/publication gates before handoff. The PR records the actual candidate run and result; this document does not pre-claim a passing run. Existing recovery/cancellation/data tests remain required.

The local chat container has no working repository-network access or Android SDK, so Android execution uses the existing CI environment. Generated screenshots are native core fixtures, not real Nano responses. Visual review and physical-device validation must not be inferred merely from a successful build.

Read-aloud remains offline and uses the installed Android TTS engine. Voice quality metadata is a selection signal, not a guarantee of human-like sound. No cloud speech endpoint, billing account, model download or new runtime dependency is introduced. Recognition language is still chosen in voice setup.

Signing remains the existing preview mechanism. A successful signature check does not establish compatibility with the phone's installed key. Export before any required uninstall. Do not store a signing key in the repository, artifacts or caches.

## Phone checks

Update the Nano APK when Android permits. Try an English or Swedish paragraph with the corresponding speech language, listen through several sentences, pause from the lock screen, resume and interrupt with the microphone. Stream a multi-paragraph answer containing bold, italics and a code sample; scroll upward during generation and check that the app does not pull you back down. Start a new conversation to inspect the reduced empty state. Existing notes and conversation exports must remain readable.

See [testing](../testing.md), [delivery](../delivery.md) and [iteration workflow](../iteration-workflow.md). Android documents voice quality, latency and network requirements in its [Voice API](https://developer.android.com/reference/android/speech/tts/Voice).
