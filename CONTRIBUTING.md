# Contributing

## Before you open a PR

```bash
python tools/verify.py .
python tools/test_verify.py
gradle assembleCoreDebug assembleNanoDebug
```

CI runs the first two before it will start a build, so a failure there costs you
a round trip for no reason.

## The one rule

`app/src/main/` and `app/src/core/` use **framework APIs only**. No AndroidX, no
Compose, no Material, no Kotlin coroutines. `verify.py` enforces it.

This is not minimalism for its own sake. Dependency resolution is the single
biggest source of first-run build failures in Android projects, and keeping the
default path free of it is why this template builds on a machine with nothing
installed. Anything that needs a dependency goes in `app/src/nano/`, or in a new
flavour beside it.

## Adding a surface

`tools/scaffold.py` has one builder function per surface and a `BUILDERS` map.
Add a function, register it, extend `SURFACES`, and document the gotcha in
`docs/surfaces.md` — the gotcha is the valuable half.

## Adding an AI task

See [`docs/ai.md`](docs/ai.md). It is one manifest alias, one enum entry and one
branch.

## Style

- Comments explain *why*, especially when the code looks odd. Most of the odd
  code here is working around a documented Android trap; say which one.
- No commented-out code, no `TODO` without a linked issue.
- Keep `verify.py` dependency-free apart from optional PyYAML.
