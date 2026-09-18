# On-device AI

> **Everyday workspace update:** [Current behavior and limits](everyday-workspace.md) covers Today · AI · Library, SQLite migration, notes/relations/tables, explicit background reading, calendar/Beeper access and optional connected AI/routines. Earlier foreground-only and preferences-only descriptions below apply to the original chat/Voice path unless updated here.

Ægentica AI's Nano flavor uses `com.google.mlkit:genai-prompt:1.0.0-beta4`, pinned in `app/build.gradle.kts`. The core flavor does not contain an AI model.

## Runtime behavior

The provider checks AICore feature status, prepares a downloadable model, and reports unsupported/downloading/error states. Generation checks whether separate system instructions are supported; otherwise the instruction is folded into the prompt. It returns cumulative text to the UI and rejects stale callbacks after cancellation.

Successful output is saved only after the final request succeeds. Echoed instructions and failed streams are not treated as completed answers. `Prompts.kt` supplies the tasks and a bounded subset of conversation history; the on-screen retained history can be longer than the context sent to a model.

The app currently accepts text. Although the underlying Prompt API offers other input/output capabilities, image input and structured tool execution are not implemented here. Summarise, Proofread and Make professional are prompt-defined operations, not separate ML Kit task clients.

## Device and service constraints

Google lists Pixel 10 Pro XL among supported Prompt API devices. Readiness is checked at runtime, and device configuration/model availability still matter. Google's current guidance restricts inference to the top foreground app and describes per-app quota/busy responses. Ægentica AI cancels active requests when leaving rather than attempting background inference. [ML Kit overview and support](https://developers.google.com/ml-kit/genai).

The Prompt API is beta. SDK behavior and compatibility can change; update the dependency deliberately and test both variants. [Prompt API documentation](https://developers.google.com/ml-kit/genai/prompt/android).

## Quality boundaries

On-device output can be wrong, incomplete or repetitive. Neither fluent speech nor fluent text establishes factual accuracy. The app has no live search, retrieval service, citation verification, tool execution, or access to private information beyond the supplied context.

Speech locale controls recognition/playback, not a guarantee of model competence. Evaluate representative English and Swedish tasks on the target Pixel, including short follow-ups, long selections, interruption, refusals and malformed output. The app no longer infers language quality from Nordic characters or appends unsupported training claims. A neutral accuracy reminder appears in Settings; actual provider notes and truncation notices remain visible.

## Changes worth testing

When changing prompts or the SDK, compare output quality on a fixed device prompt set, record the model/device versions, and test readiness failures and cancellation. Build success cannot validate AICore inference on a machine without AICore. Keep any future remote-model option explicit in the product and privacy model; none exists today.

## Android AppFunctions

AppFunctions is a promising future route for OS-level agent interoperability. Google's current documentation calls it experimental and limits the complete pipeline to selected apps/system agents. Exposing functions and being allowed to invoke other apps are different capabilities; cross-package execution requires the relevant permission/eligibility. This preview implements neither provider nor agent integration. A future milestone should first verify eligibility, then add typed schemas, user confirmation and device execution tests. [Official AppFunctions guidance](https://developer.android.com/ai/appfunctions).

## Connected and source-aware AI

The **AI** destination can attach up to five selected live records through `KnowledgeContext`; it requests numbered references and treats source text as untrusted data. Context is bounded, may be excerpted, and is not an automatic search of every saved record. The model cannot invoke tools or determine permissions.

`ConnectedAI` is an optional framework HTTPS Chat Completions client with an explicitly configured model/endpoint and Keystore-encrypted key. It is used only when enabled for the typed/dictated composer or separately approved for a connected routine. No retry/fallback can silently move Nano input to a remote provider. The current connected response is non-streaming; hands-free Voice and selection actions retain Nano. Credentials and third-party behavior require owner/device verification.

`RoutineJobService` durably claims an occurrence before a network request, stores results as linked notes, records completion/failure/interruption and schedules future recurrences. It never executes answer text as an action. Details, scope and spending limits: [everyday guide](everyday-workspace.md#connected-ai-and-routines).
