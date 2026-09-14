# Contributing

Ægentica AI is a native personal-assistant preview. Focus contributions on an observable user problem and keep changes small enough to review.

## Set up

See [README](README.md#build-and-test) and the [pinned versions](docs/versions.md). Use the branch containing the experience you intend to change. The project has no Gradle wrapper; CI installs its pinned Gradle version directly.

```bash
python tools/check.py
gradle testCoreDebugUnitTest lintCoreDebug lintNanoDebug assembleCoreDebug assembleNanoDebug --no-daemon
```

## Boundaries

- `app/src/main` and `app/src/core` use framework APIs only. No AndroidX/Compose/coroutine runtime imports there. Nano-specific SDK dependencies belong in `app/src/nano`; test dependencies are exempt.
- Preserve offline-only recognition/playback behavior and explicit user consent for sharing. Do not add a quiet remote fallback.
- Every asynchronous activity update must belong to the active request and lifecycle. Cancel queued rendering on final/stop/pause/destruction.
- Layout changes need a meaningful regression test. Use native graphics for text wrapping/scroll geometry and inspect fresh screenshots.
- Do not persist failed partial answers as successful exchanges or destroy a newer draft when restoring an interrupted question.
- Never commit signing keys, credentials, personal chat exports, or unredacted user screenshots.

## Review checklist

Describe the problem, resulting behavior, tests run and device-only checks still outstanding. Update the user guide when controls change and the architecture/privacy guide when contracts change. Keep product copy distinct from implementation notes. Avoid tests that only repeat the implementation's constants; exercise the failure being prevented.

Use [testing.md](docs/testing.md) for the hardware checklist. UI test output belongs in `app/build/`; only reviewed, non-private screenshots intended for documentation belong in `docs/images/`.

## Extend

Tasks: add the enum/prompt definition and flavor alias, then test selected-text and chat behavior. Surfaces: reuse existing foreground flows and document permissions, supported Android versions, and launcher constraints. `tools/scaffold.py` is an independent minimal generator; changes there do not automatically update the full assistant.

## Reports

Include installed build number, Android/device details, reproduction steps, and redacted screenshots. Do not upload a private conversation export to a public issue. For security-sensitive concerns, avoid publishing exploit details or private data while seeking a suitable private contact route; this preview does not claim a response SLA.

## Close the loop

Use [the iteration workflow](docs/iteration-workflow.md): reproduce a scoped defect, run `python tools/check.py`, inspect native evidence, verify the exact build-specific APK and document device limits. Successful screenshots do not mean visual approval. Keep behavior, tests and current guides coherent in the same batch.
