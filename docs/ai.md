# On-device AI

Everything here runs on the phone. No key, no account, no request leaves the
device. The honest limits are further down — read them before you plan around
this.

## What is actually running

The `nano` flavour uses the **ML Kit Prompt API** (`com.google.mlkit:genai-prompt`),
a general generative client for **Gemini Nano**, hosted by **Android AICore** —
a system service, not a library. Three consequences:

- No model ships inside the APK. The `nano` build is ~20 MB and none of that is
  weights.
- The first call may need a one-time feature download, handled by the system.
  After that it works with the radios off.
- If AICore is missing or the device is unsupported, there is nothing to fall
  back to. The app says so plainly instead of failing at the call site.

```
your prompt + your selection
      │
      ▼
ProcessTextActivity ──► SurfaceBrain ──► Prompt API ──► AICore ──► Gemini Nano
                          (interface)                   (system)   (on device)
```

`SurfaceBrain` is the seam. `app/src/core/` implements it with `String.uppercase()`
and no dependencies; `app/src/nano/` implements it with `GenerativeModelFutures`.
Nothing else in the app changes between the two builds.

## Why not the task-shaped APIs

ML Kit also ships `genai-summarization`, `genai-proofreading` and
`genai-rewriting`. They look like the obvious starting point and they are a dead
end for anything general:

| | Task APIs | Prompt API |
|---|---|---|
| Input | one fixed job each | any prompt |
| Languages | EN, JA, KO (+DE, FR, IT, ES for two of them) | no declared list |
| System instruction | no | yes, where the device supports it |
| Streaming | yes | yes |
| Temperature / topK / seed | no | yes |
| Images | separate artifact | built in |
| Structured output | no | yes |
| Dependencies | one artifact per task | one, total |

They are also on a different release train — beta1 against the Prompt API's
beta4 — and they share `genai-common`, so mixing families lets Gradle resolve a
single `genai-common` that one side was not built against. Pick one. This repo
picked the Prompt API.

## Presets are just prompts

`Prompts.kt` holds a system instruction per task and nothing else. "Summarise"
is not a feature; it is three lines of English. Adding your own:

1. Add a case to the `Task` enum in `SurfaceBrain.kt`.
2. Add its system instruction to `Prompts.kt`.
3. Add an `<activity-alias>` to `app/src/nano/AndroidManifest.xml`.
4. `python tools/verify.py .`

No new classes, no changes to any surface. The alias name is what links the
manifest entry to the enum case.

## Availability

| Requirement | Why |
|---|---|
| A supported Pixel (9 series and newer) | AICore ships the Nano weights |
| **Locked bootloader** | the GenAI APIs refuse to run otherwise |
| A current Android AICore system app | it is updated through Play, not OS releases |
| API 26+ | the SDK minimum; this app sets minSdk 29 |

Check it from the app: **Check / prepare on-device model**, or add the Quick
Settings tile, whose subtitle reports live status and whose tap triggers the
download.

## Languages

The Prompt API declares no language list, so nothing is refused. That is not the
same as being good in every language: Nano is a small model trained mostly on
English, and it will produce fluent Swedish that is subtly wrong — which is
worse than an error.

`Lang.kt` runs a cheap heuristic and attaches one note when the input looks
Nordic. It never blocks the call. If Swedish output quality genuinely matters,
the honest answer is a different model: Gemma 3n or Qwen3 1.7B through
**MediaPipe LLM Inference** or **LiteRT-LM**, bundled or side-loaded. That is a
much larger APK and a much larger job, and it is out of scope here.

## Three traps worth knowing

All three are handled in `app/src/nano/java/.../Brain.kt`:

1. **These return Guava `ListenableFuture`, not a Play Services `Task`.** The
   `.await()` shown in some samples will not compile. Only the
   `listenablefuture` stub ships, so there is no `Futures.addCallback` either —
   the working move is `future.addListener(runnable, executor)` and `get()`
   inside the listener, which needs no extra dependency at all.
2. **`FeatureStatus` is an int constant, not an enum.** `checkStatus()` resolves
   to `ListenableFuture<Integer>`, compared against `FeatureStatus.AVAILABLE`.
3. **System instructions are not supported on every device.** Call
   `isSystemPromptAvailable()` first and fold the instruction into the prompt
   when it comes back false. Skip this and the model appears to ignore its
   instructions for no visible reason.

`DownloadCallback` also fires off the main thread, so anything touching UI has
to be posted back.

## Knobs the app sets, and why

| Setting | Value | Reason |
|---|---|---|
| `temperature` | 0.2 for presets, 0.7 for Ask | presets transform the user's own text and should not invent; Ask is open-ended |
| `maxOutputTokens` | 512 | a dialog, not an essay |
| streaming | on | text lands as it is produced, so there is never a blank spinner |

Also available and unused here: `topK`, `seed`, `candidateCount`,
`enableThinking`, context caching (`Caches`), token counting (`countTokens`,
`getTokenLimit`) and structured output against a schema. `GenerativeModel` — the
coroutine-flavoured interface behind `GenerativeModelFutures` — exposes all of
them.
