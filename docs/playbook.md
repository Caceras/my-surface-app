# The shared playbook

[`app/src/main/assets/playbook.json`](../app/src/main/assets/playbook.json) is one spec for how Ægentica's agents read and act. The phone agent reads it at run time (`Playbook.kt`). It mirrors Glimt (`apps/chatbar/src/lib/tools.ts` in Caceras/awesomeseo-monorepo) and the live browser's `browser__*` tools (`lib/browser/policy.ts` in Caceras/aegentica-app), so the agent learns one vocabulary in the browser, in Glimt and on the phone. It lives here for now; it will be shared across the repositories later.

## What is in it

| Part | What it holds |
|---|---|
| `tiers` | The three risk tiers, in plain words |
| `why` | The one-line reason every acting call carries: 3 to 160 characters, shown before the person allows it |
| `instructions` | The wording the agent gets: `shared` always, `hands` when Ægentica hands is on, `noHands` when it is off |
| `sensitive` | Words on a button or field that make a step always ask (send, call, pay, delete, account), link schemes that always ask (`tel:`, `sms:`, `mailto:`), and banking, payment and store apps where every acting step always asks |
| `secretFields` | Words in a field's label, hint or id that mark a password, card or one-time-code field |
| `tools` | Every tool: name, description, tier, target, label and JSON Schema parameters |

## The tiers

From Glimt's proven model:

- **Read** runs on its own: it changes nothing. Snapshot, find, screenshot, highlight, scroll and wait.
- **Act** waits for the person's **Allow**. The person may tap **Always in <app>** to let ordinary steps run in that app without asking again. Tap, press and hold, type, swipe, Back, Home, Recents, open an app and open a link.
- **Always** asks every time, with its reason, and is never remembered: anything that sends, calls, pays, buys, deletes or changes an account, a `tel:`, `sms:` or `mailto:` link, typing with *submit*, and every acting step in a banking, payment or store app. The phone decides this from what is on the screen, never from the model's words.

Tools are checked against this file before anything is asked or run: an unknown name, a tool that is not offered right now, or a call without its required fields never runs. Model text is never executed as a command.

## Names across platforms

| Phone | Glimt | Live browser |
|---|---|---|
| `phone_snapshot` | `snapshot` | `browser__snapshot` |
| `phone_find` | `find_in_page` | `browser__find` |
| `phone_tap` | `click` | `browser__click` |
| `phone_type` | `type_text` | `browser__fill` |
| `phone_scroll` | `scroll` | `browser__scroll` |
| `phone_screenshot` | `screenshot` | `browser__screenshot` |
| `phone_highlight` | `highlight` | — |
| `phone_wait_for` | — | `browser__wait_for` |
| `phone_open_url` | `navigate` | `browser__navigate` |

Element refs look like `e12`, as in Glimt's snapshot; `@e12`, as agent-browser writes them, is accepted too.

## Changing it

Edit the JSON, then run the Android tests: `PhoneAgentTest` checks that names are unique, every required field is declared, every acting tool requires `why`, and the tier lists are the expected ones. Keep the wording in step with Glimt and the live browser.
