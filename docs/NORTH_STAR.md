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
