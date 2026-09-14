# Cohesion audit

September 14, 2026 · Follow-up to the [Android capability audit](quality-audit.md).

## Scope and evidence

This pass reviews chat → Settings → History → Voice → native actions, plus the source-to-download workflow. The baseline was captured freshly by rerunning the JVM job in [PR run 34885224671](https://github.com/Caceras/my-surface-app/actions/runs/34885224671), source `bb012365c2e7c761fa3c32c7eaa7f5a28dfe8896`, artifact **10365107276**, created **19:29:57 UTC**. Chat conversation, Settings, History, Voice setup and Actions were opened and inspected. Baseline files are diagnostic references, not the final UI.

Current native screenshots are generated with every build and included beside source/test evidence in the release's **aegentica-evidence.zip**. Open **review.html** after extracting it. This avoids committing a newly stale screenshot gallery for every documentation edit. Final reviewed build and findings are recorded in [PR #7](https://github.com/Caceras/my-surface-app/pull/7). The README's curated branding illustrations retain their original provenance.

## Five flows

| Step | Baseline finding | Change and health | Evidence limit |
|---|---|---|---|
| 1 · Chat → Settings | The settings header/control styling differed; source inspection found generation/dictation could continue behind it | Shared header/switch styling and pause-before-navigation; meaningful cancellation/draft regressions | Real IME timing and TalkBack require Pixel verification |
| 2 · History | Close action missing from the captured layout; footer said History but sheet said Conversations | Shared explicit-width header with History title and reachable Done; search uses common private editor | Large device text/display combinations still need traversal testing |
| 3 · Voice | Native setup controls mostly coherent; state review found Quiet could regain a misleading active label at final output | Shared switch treatment and persistent quiet state through completion; regression protects mute behavior | Physical TTS/headset quality is not a core fixture result |
| 4 · Actions | App handoffs were explicit, but forms used a different editor treatment | Shared field colors/spacing, bounded input and personalized-learning suppression; protected dialogs retained | Destination applications control final completion |
| 5 · Iterate → install | Repeated SDK/build setup and manual evidence/download checks | Consolidated quality job, lint gate, evidence bundle, unique verified APK and durable release history | Visual judgment, signing ownership and physical-device quality remain separate |

Each flow retains native controls, focus behavior and the Æ/sky-blue design. No model output becomes an executable phone command. Modal navigation now consistently cancels generation, microphone capture and speech, preserves the pending question/draft and rejects late results. Returning from a sheet does not restart them automatically.

## Verification and follow-up

Focused regressions cover every navigation sheet, cancelled dictation, retained drafts, late results, quiet voice completion, dark switches and private search. New native renders cover dark Settings and action forms. Shared-header exits are measured after pending dialog layout work settles; a close control must be visibly reachable, not merely present in the view tree. Existing compact, large-font, wide, landscape, widget, voice and conversation checks remain in the suite. Release tests reject missing/skipped/failed JUnit results, lint errors, wrong APK identity/version and tampered files.

The [workflow audit](iteration-workflow.md) gives the implemented loop, remaining automation boundaries and next investments. [Testing](testing.md) defines device acceptance. Automated quality does not establish 120 Hz performance, speech accuracy, full accessibility or production readiness.
