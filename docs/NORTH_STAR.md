# North star

What the owner has asked for, in one place, so no agent or session has to remember it. Read this before planning any Ægentica work, on any platform.

## The outcome

One magical, durable Ægentica genie, reachable from anywhere, in a beautiful, seamless, intuitive UX.

- **One agent, many surfaces.** Memory, history, Inbox, tasks, connectors and background jobs live once, on aegentica.se. Every surface reaches the same agent through one backend (one MCP endpoint plus access tokens) and continues the same conversation.
- **The genie.** The rabbit is the agent made visible. It moves to what it works on, points, edits and browses in front of you, says in a few words what it is doing, and can be steered or stopped at any moment.
- **Seamless motion.** Hero-style transitions carry the genie and its work between apps, windows, tabs and pages, so it feels like one thing travelling with you.

The keyboard is the genie's home inside every app: it changes between keys, the live genie panel and a full-screen workspace, carrying the same durable conversation.

## The surfaces

| Platform      | Surfaces                                                                                                                                                                                                                                                                                                                                                                           |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Web           | aegentica.se; the Ægentica bar on any page or tab (aegentica.se/bar and the browser extension)                                                                                                                                                                                                                                                                                     |
| Desktop (Mac) | Hotkey bar; the desktop genie moving across apps and windows; the computer's own hands                                                                                                                                                                                                                                                                                             |
| Android       | Native assistant (assistant gesture); **select text in any app → the genie sheet pops up over that app**, with the selection as context and a browser inside the sheet; the **Ægentica keyboard** as a Gboard replacement or extension, whose Ægentica button summons the genie with what you type; share sheet, Quick Settings tile, widget, notifications; the phone's own hands |

## Rules that follow from it

- **One design.** Every surface uses aegentica.se's design ([DESIGN.md](https://github.com/Caceras/aegentica-app/blob/main/docs/DESIGN.md)): restrained monochrome, colour only for service logos and semantic states. No Glimt pink, no one-off styles.
- **Build on our own foundations only:** Ægentica (eve), Glimt and the AwesomeSEO chatbar. Don't bring in or compare other products.
- **Screenshots show behaviour, not features.** When the owner shows another app (for example Google's sheet popping up on a text selection), he means "Ægentica should feel like this". He is not asking for that app's feature.
- **Existing work first.** Before building, check what the repos already have (for Android, [docs/surfaces.md](surfaces.md) in this repository) and extend it. Don't present it as new.
- **A question is not a change of plan.** Answer it; change course only when he asks for it.
- **Safety stays.** Anything that sends, pays, deletes or publishes needs his approval. Model text is never typed or run without a tap. Nothing is read in password, card or one-time-code fields.
- **Always ask first** before anything that costs money, deletes data, messages anyone or acts as him.

## Magic, not scripts

Every example the owner gives (the dog-breed spreadsheet, the Gboard screenshots, Google's selection sheet) shows the kind of magic he wants. None of them is a spec. Never build a feature for one example. Build general powers, make them reachable from as many surfaces as possible, and test with many different requests.

**The genie's general powers, the same everywhere:**

- **See:** the screen when invoked, the selection, the field being typed in, the camera, files, links and clipboard.
- **Hands:** the server's real browser, the phone's apps, the computer, every connector, a code sandbox, and image, video and voice tools.
- **Remember:** one memory and one history.
- **Keep going:** durable tasks that survive a closed app or a sleeping phone.

**What every surface does:**

1. **Summon:** call the genie from where you are, with that place's context.
2. **Show:** live work and small insights that appear and fade (steps, partial results, cost, the rabbit at work), with steering by voice or text and take-over at any moment.
3. **Return:** results land where you are, or follow you to the next device.

**Continuity:** start anywhere, continue anywhere: phone, wrist, earbuds, Mac, browser, the home screen in the kitchen.

## Every surface we can reach

| Device | Surfaces |
|---|---|
| Phone (Pixel 10 Pro XL) | Assistant gesture (long-press power) and Quick Tap (double-tap the back, set to "digital assistant"); a floating bubble and transparent bar over any app (Android bubbles/overlay); select text; share sheet (links, pictures, files, screenshots); the keyboard (the genie's home inside every app); notifications with live progress, actions and replies (Android 16 live updates, lock screen); Quick Settings tile; home-screen widgets with live task status; app-icon shortcuts; camera; NFC tags that start a routine |
| Watch (Pixel Watch 4) | Ask from the wrist, a tile and a complication with running tasks, approve or stop a task on the wrist, a tap on the wrist when it's done |
| Earbuds (Pixel Buds Pro 2) | Hands-free voice to the genie and short spoken updates during long jobs |
| Mac (MacBook Pro, iMac) | Global hotkey bar; the desktop genie moving across apps and windows; menu bar; Services menu and share extension on any selection or file; drag anything onto the rabbit; notifications with actions; widgets; Shortcuts actions; the computer's own hands |
| Browser | The Ægentica bar on any page or tab (extension and aegentica.se/bar); aegentica.se |
| Home | The Raspberry Pi 5 with its 7" screen as an always-on Ægentica display (running tasks, today, the idle rabbit, voice); casting results to the TV |
| Where he already talks | Telegram, the Beeper inbox and email: talk to the genie like a contact |

**Order:** the phone's ways in first (gesture, Quick Tap, bubble and bar, share, selection, keyboard, notifications), then the Mac, the watch and earbuds, then the home display.

## On this phone today

- **Summon:** the assistant gesture and Quick Tap open the floating bar over any app when [Ægentica hands](hands.md) is on, otherwise the sheet; the sheet also opens from text selection and from inside the app (`AssistantActivity.withText`).
- **Show:** the rabbit flies to each step, the bar shows what it does, Allow prompts and steps appear and fade, the microphone can stay open to steer, and touching the screen takes over. The notification is a live surface with Stop, Allow and a reply.
- **Keep going:** with the phone [connected to Ægentica](backend.md), long jobs go to the server agent and keep running while the phone sleeps. Ægentica, the sheet and a live notification show their steps, tables, pictures, files and cost, and Ægentica's browser live with take-over; a step that costs money shows its price and is approved by a tap, or by voice after the amount is read back.
- **Remember:** the phone agent uses the same memory, Inbox and tasks as aegentica.se, and phone chats continue in Ægentica's chat history.
- **Return:** the answer lands in the bar, the sheet and the notification, read aloud when the question was spoken; a finished job's notification opens its answer, its chat and its files.
- **Next:** a web view inside the sheet the agent can browse in; widgets and a Quick Settings tile with running jobs; the watch and earbuds.

The same outcome for every platform is in aegentica-app's [docs/NORTH_STAR.md](https://github.com/Caceras/aegentica-app/blob/main/docs/NORTH_STAR.md).
