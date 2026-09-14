# Native actions and access

Ægentica AI uses Android's established app handoffs for everyday actions. Open **Actions** below the composer, or use its launcher shortcut. Settings also includes **On your phone · Actions**. None of these actions is inferred or executed automatically from an AI response.

## What each action does

| Action | Your input | Android handoff | Completion boundary |
|---|---|---|---|
| Set a timer | 1–1,440 whole minutes | `AlarmClock.ACTION_SET_TIMER`, seconds and `EXTRA_SKIP_UI=false` | Clock receives the request; its handler may create/start the timer while showing its UI |
| Set an alarm | Native local-time picker | `AlarmClock.ACTION_SET_ALARM`, hour/minute, `EXTRA_SKIP_UI=false` | Clock decides how to display/apply the chosen alarm; no silent-skip request |
| Draft a calendar event | Editable title, optionally taken from the current draft | `ACTION_INSERT` with Calendar's events URI | Review date/time/details and save in Calendar |
| Find a place | Place/address query | `ACTION_VIEW` with an encoded `geo:` query | Maps receives the query and may use the network |
| Open the dialer | A phone number | `ACTION_DIAL` with a `tel:` URI | Review the number and choose whether to call |

The app never reports an action completed merely because Android opened a destination. A missing handler or denied launch produces a visible message. Android/the installed app determines the destination and its final behavior.

Clock uses the normal SET_ALARM permission. Calendar, Maps and Dialer do not require broad calendar, location, contacts or CALL_PHONE access in Ægentica AI. The destination apps retain their own permission and privacy models. There is no executable arbitrary-intent parser and no model-driven phone automation.

## Fast entry

| Surface | Behavior |
|---|---|
| Long-press launcher | Chat, Voice on Android 12+, History and Actions |
| Pin chat / Pin voice | Requests a home-screen shortcut through the launcher; launcher support and confirmation apply |
| History shortcut | Opens saved conversations while preserving the current draft |
| Actions shortcut | Opens the action sheet without sending or replacing the draft |
| Quick Settings | Opens foreground Voice after required unlock; add requests report success/decline/error |
| Widget | Type/Talk controls; full-size preview only with opt-in; compact layout never exposes answer text |

## Keyboard

| Shortcut | Behavior |
|---|---|
| Ctrl+Enter | Send the current draft; repeated commands do not submit during generation |
| Ctrl+N | Save the current conversation/draft and start a new one |
| Ctrl+L | Focus the composer and request the keyboard |
| Ctrl+Shift+M | Open Voice |

Plain Enter remains a new line in the multiline composer. Android's system shortcut helper includes the app commands. Shortcuts act in the chat activity; modal forms retain their normal editing controls.

## Audio

Headset/media Pause or Stop ends active foreground speech. Headphone disconnect also stops playback, with a visible explanation; later generated sentences stay muted. These commands do not automatically reopen the microphone. The MediaSession carries a generic label instead of private answer text. Hardware routing still needs physical Pixel/Bluetooth testing.

## API references

[Android intents](https://developer.android.com/guide/components/intents-common), [shortcuts](https://developer.android.com/develop/ui/compose/system/shortcuts/creating-shortcuts), [keyboard actions](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/commands), [media callbacks](https://developer.android.com/media/legacy/audio/mediasession).

All navigation sheets use the same pause-and-preserve-draft behavior. Action forms share the private editor styling used by History search and bound their field length before validated handoff.
