# Ægentica hands and the floating bar

**Ægentica hands** is an opt-in Android accessibility service that lets the phone agent see the screen and tap, type, scroll and open apps for you, only while it works on something you asked. The **floating bar** with the rabbit sits over every app: summon it with the assistant gesture, watch it work, steer it by voice or text, take over at any moment, and get the result where you are.

## Turn it on

1. Settings → **Ægentica hands** (or the sheet's **+** menu) explains what it allows and how to turn it off.
2. **Turn on in Settings** opens Android's Accessibility settings. Choose **Ægentica hands** and switch it on.
3. Because the app is installed from a file, Android may say the setting is **restricted**. Tap **App info** in the same dialog, then **⋮ → Allow restricted settings**, and try again.

Turn it off the same way, at any time. **Stop** ends a task at once.

## What the owner sees

- **The floating bar.** With the hands on, the assistant gesture (corner swipe, holding the power button, or Quick Tap set to the digital assistant) opens a light, floating bar over the app instead of the sheet. It has aegentica.se's composer shape: what Ægentica is doing, the message box, and **+**, the screen chip, the model's logo, the microphone and send. It stays while you keep using the phone.
- **Three forms.** The rabbit alone (a bubble), the bar, and the bar with the conversation above it (the question, the steps, the answer and Allow prompts). Tap the rabbit to fold or unfold; drag it anywhere; hold it to hear what it is doing. Changes between forms are short scale-and-fade transitions.
- **Voice.** The bar opens listening. Tap the microphone to keep it open while it works: what you say steers the run, and talking over its voice stops the voice. Hold it to say one thing.
- **The rabbit at work.** Before each step the rabbit hops to land beside its target (a leap when it is far), in a quarter to two thirds of a second, so you see what will happen and can still stop it. Then it acts the step out: a pounce for a tap or typing, a look for reading, a walk for a scroll, a leap to another screen. A tap ripples and sparkles, and a new screen poofs. Read-only steps ring the element they point at. Between steps it begs while a step waits for you, looks while Ægentica works (turning while it thinks), waves when it is done, stands while the bar is open, and sits, loafs and at last sleeps when left alone; now and then it fidgets. These are Glimt's own sprites and rules, so it looks and moves the same everywhere. With Android's *Remove animations* on, nothing moves.
- **Stop** is on screen whenever the hands work, also when the bar is folded into the rabbit.
- **Take over.** Touch the screen while it works and the hands pause: "You have the screen. Tap Continue when you are done." Continue hands it back and the agent carries on.
- **The notification.** While the agent uses your apps, a notification shows what it is doing now, with Stop, Allow and Don't when a step waits, and a reply field that steers it. On Android 16 it asks to show as a live update in the status bar. Allowing and replying need the phone unlocked. When the run ends, it says so and opens the answer.

## The safety model

Glimt's tiers, from the [shared playbook](playbook.md):

| Tier | Steps | What happens |
|---|---|---|
| Read | snapshot, find, screenshot, highlight, scroll, wait | Runs on its own |
| Act | tap, press and hold, type, swipe, Back, Home, Recents, open an app, open a link | Waits for **Allow**; **Always in <app>** remembers it for that app (Settings → Ægentica hands → Forget these) |
| Always | anything whose button, label or id says send, call, pay, buy, book, delete, remove, sign out, change password, allow; typing with *submit*; `tel:`, `sms:`, `mailto:` links; every acting step in a banking, payment or store app | Asks every time, says why, and is never remembered |

- Every acting call carries a one-line `why` (3 to 160 characters), shown in the prompt and spoken when you hold the rabbit.
- **Hands-free** (off by default) lets ordinary acting steps run without asking; steps that always ask still ask.
- Passwords, card numbers and one-time codes: their values show as `••••` in what the model reads, and the hands **refuse to type** into those fields. The bar asks you to "Take over to type your password, then tap Continue".
- The hands never tap Ægentica's own windows (the bar, its Allow buttons, the sheet), by element or by point.
- Every step is validated on the phone before anything happens: the tool must be in the playbook and offered now, refs must come from the newest snapshot and still exist, points must be on screen, text is at most 2,000 characters, only `https`, `tel:`, `sms:` and `mailto:` links open. Model text is never run as a command.
- Stop, a touch on the screen, or turning the service off stops the hands at once; after Stop every step is refused until the next question.

## What the model reads

`phone_snapshot` lists the window in front, in Glimt's format: one line per element with a stable ref, its role, its name, its value, its state and its bounds in screen pixels:

```
Settings · com.android.settings · screen 1080×2400
e2 field “Search settings” value=“” [tap, type] @[40,200,1040,300]
e3 field “Wi-Fi password” value=“••••” [type, secret] @[40,320,1040,420]
e5 button “Wi-Fi · Off” [tap] @[0,440,880,560]
e6 switch “Wi-Fi” [off, tap] @[900,460,1040,540]
```

Refs stay the same for the same element through a conversation; `@e6` is accepted too. A tappable row is named by the words inside it. `phone_screenshot` hides the bar and the rabbit and sends a JPEG of at most 1568 px.

## Android's limits

- Apps cannot switch Wi-Fi, Bluetooth or mobile data directly. The agent opens Settings or the quick settings panel and taps the switch, visibly.
- Protected windows (banking apps, password screens) give no screenshot, and some show no readable elements.
- Android allows about one accessibility screenshot a second; the hands wait and try once more.
- Games, maps and web pages drawn as images expose little to read; the agent uses the screenshot there.
- An app can ignore an accessibility click; the hands then touch the element's centre.

## Battery and privacy

The service does nothing on its own: it reacts to clicks only to notice that you took over, and reads the screen only when the agent asks during a task. The bar and the rabbit draw only while summoned or working. What the agent sees goes to OpenRouter and the model's provider for your request, as in the sheet ([privacy](privacy.md)). Nothing it sees is stored on the phone.

## Code

| File | What it holds |
|---|---|
| `HandsService.kt` | The accessibility service, and the adapters from Android's nodes, gestures, global actions and screenshots |
| `Hands.kt` | The hands' state (working, paused, stopped), the policy (secrets, steps that always ask, names), refs, the snapshot format and `HandsTools`, the agent's toolbox |
| `RabbitOverlay.kt` | The floating bar: the stage for the rabbit's moves and the bubble, bar and expanded card |
| `AgentNotifications.kt`, `AgentActionReceiver.kt` | The live notification and its Stop, Allow, Don't and reply |
| `HandsSetup.kt` | Turning it on, hands-free, the floating bar switch and remembered apps |
| `RabbitMotion.kt` | The rabbit's poses and motion rules, ported from Glimt (`apps/chatbar/src/lib/alo.ts` in Caceras/awesomeseo-monorepo): where it lands, trips, the pose between steps and fidgets |
| `res/drawable-nodpi/rabbit_*.webp` | Glimt's 13 rabbit sprites, cut from Riki's own drawings (`apps/chatbar/public/alo`) |
