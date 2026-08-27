# On-device AI

Everything here runs on the phone. No key, no account, no request leaves the
device. The honest limits are further down — read them before you plan around
this.

## What is actually running

The `nano` flavour calls the **ML Kit GenAI APIs**, which are a thin front door
to **Gemini Nano** hosted by **Android AICore** — a system service, not a
library. Three consequences:

- No model ships inside the APK. The `nano` build is ~10 MB, and most of that is
  the ML Kit client code, not weights.
- The first call may need a one-time feature download, handled by the system.
  After that it works with the radios off.
- If AICore is missing or the device is unsupported, there is nothing to fall
  back to. The app says so plainly instead of failing at the call site.

```
your selection
      │
      ▼
ProcessTextActivity ──► SurfaceBrain ──► ML Kit GenAI ──► AICore ──► Gemini Nano
                          (interface)                     (system)   (on device)
```

`SurfaceBrain` is the seam. `app/src/core/` implements it with `String.uppercase()`
and no dependencies; `app/src/nano/` implements it with the three GenAI clients.
Nothing else in the app changes between the two builds.

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

## Languages — the real constraint

Straight out of the SDK's own constants:

| Task | Languages |
|---|---|
| Summarise | English, Japanese, Korean |
| Proofread | English, Japanese, Korean, German, French, Italian, Spanish |
| Rewrite | English, Japanese, Korean, German, French, Italian, Spanish |

**No Nordic languages.** `Lang.kt` runs a cheap heuristic on the selection and
attaches a warning when the text looks Swedish, rather than replacing your
selection with confident nonsense. It never blocks the call — you may still want
the output.

If you need Swedish on device, these APIs are the wrong tool. The path is a
small open-weights model (Gemma 3n, Qwen3 1.7B) run through **MediaPipe LLM
Inference** or **LiteRT-LM**, bundled or side-loaded. That is a much larger APK,
a much larger job, and out of scope for this template.

## Other limits worth knowing before you design around them

- **Summarise** wants a real paragraph. Under a few hundred characters it
  returns nothing useful; the app turns that into a readable message rather than
  an empty dialog. `setLongInputAutoTruncationEnabled(true)` is on, so long input
  is trimmed instead of rejected.
- **Proofread** is capped near 256 tokens. It is built for a sentence, not an
  essay.
- **Rewrite** offers six styles in the SDK — `PROFESSIONAL`, `FRIENDLY`,
  `SHORTEN`, `ELABORATE`, `REPHRASE`, `EMOJIFY`. This app wires up
  `PROFESSIONAL`; adding the rest is one more alias per style in
  `app/src/nano/AndroidManifest.xml` plus a branch in `engineFor`.
- **Results are suggestions.** Proofread and rewrite return a list sorted by
  descending confidence; the app takes the first.

## Two mistakes the official sample will lead you into

Both cost real time, and both are fixed in `app/src/nano/java/.../Brain.kt`:

1. **These return Guava `ListenableFuture`, not a Play Services `Task`.** The
   documented `.await()` will not compile. The APIs also ship only the
   `listenablefuture` stub, so there is no `Futures.addCallback` either — the
   working move is `future.addListener(runnable, executor)` and then `get()`
   inside the listener, which needs no extra dependency at all.
2. **`FeatureStatus` is an int constant, not an enum.** `checkFeatureStatus()`
   resolves to `ListenableFuture<Integer>`, compared against
   `FeatureStatus.AVAILABLE` and friends.

`DownloadCallback` also fires off the main thread, so anything touching UI has
to be posted back.

## Adding a fourth task

1. Add an `<activity-alias>` to `app/src/nano/AndroidManifest.xml` with a new
   `android:name` and label.
2. Add a matching entry to the `Task` enum in `SurfaceBrain.kt` — the alias name
   is the link between the two.
3. Add a branch to `engineFor()` in the nano `Brain.kt`.
4. `python tools/verify.py .`

No new classes, no changes to any surface.
