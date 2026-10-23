# Ægentica AI design system

## Identity

The product name is **Ægentica AI**. The signum is **Æ**, drawn as a custom geometric vector ligature. It does not depend on an embedded font, raster image or external asset. Internal package/class names and the rolling APK URL preserve compatibility with earlier Surface Preview installs and backups.

The launcher has an adaptive foreground/background icon and an Android 13+ monochrome layer for themed icons. Android 12+ launch styling uses the same mark and sky-blue background through the native splash-screen attributes. There is no artificial splash delay or extra splash activity.

## Color

The app uses the same palette as [aegentica.se](https://aegentica.se): white, near-black ink and light grey surfaces, with one dark fill for the primary action. The owner asked for this on 2026-10-23, replacing the sky-blue interface. Sky blue stays on the launcher icon and splash, where the signum lives.

| Token | Light | Dark | Purpose |
|---|---|---|---|
| Background (`chat_bg`) | `#FFFFFF` | `#0A0A0A` | Canvas |
| Primary text | `#0D0D0D` | `#FAFAFA` | Body and headings |
| Secondary text | `#606060` | `#A1A1A1` | Supporting copy, inactive icons |
| Primary fill (`accent`) | `#141414` | `#E5E5E5` | Send/Voice button, primary pill, current tab |
| On primary | `#FAFAFA` | `#171717` | Text and icons on the primary fill |
| Surface (`glass_surface`, `composer_bg`) | `#FFFFFF` | `#171717` | Message box, cards, pills |
| Border (`glass_outline`) | `#E9E9E9` | `#2B2B2B` | Card and pill outlines |
| Muted (`chip_bg`, `bubble_you`) | `#F3F3F3` | `#262626` | Your messages, list buttons |

Measured token contrast: primary/background 19.44:1 light and 18.97:1 dark; secondary/background 6.29:1 and 7.66:1; secondary on muted 5.67:1 light; on-primary/primary 17.65:1 and 14.23:1. These calculations use the checked-in sRGB values; disabled states, anti-aliasing and system switches still need visual review.

## Chat layout

- **Header:** menu icon (saved conversations), the name and model status, then New chat and Settings icons.
- **Empty chat:** the Æ mark and "What should we work on?".
- **Message box:** one rounded card. + opens Actions, the bookmark saves the draft as a note, the source line opens Sources, then the microphone and one round button: Voice when the box is empty, Send with a draft, Stop while answering.
- **Answers:** a quiet icon row under each answer (Listen, Copy, Save, Share).
- **Tabs:** line icon over label, the current tab in full ink, under a hairline.

## Layout and behavior

- Content has a 720 dp maximum reading width in wide windows. The background still fills the app window.
- The chat header adapts at narrow widths and larger font settings. Voice status and content scroll together, keeping the action/navigation area accessible in short windows.
- Framework text remains scalable. Primary targets are at least 48 dp, with 52 dp pill controls. Custom text controls expose button semantics; key titles expose headings.
- Use system back, selection, IME and keyboard conventions. Respect system animation preferences, use brief entry motion and state-based feedback, and avoid idle animation.
- Light/dark widget colors follow app resources. Widgets use compact/full layouts and fixed reachable Type/Talk targets; private answer text is opt-in.

## Validation

See [testing](testing.md) for native compact, landscape, wide, large-font, widget and icon renders. These are core-variant layouts with deterministic data, not real Gemini Nano answers. Physical-device checks remain required for themed icon masks, splash timing, gesture navigation, font/display settings and TalkBack.

## Shared controls

Use `sheetHeader` for Settings, History and Actions. The Done control has explicit wrap-content dimensions beside a weighted title. Use `preferenceSwitch` for native settings/voice toggles; it keeps semantic text colors, 56 dp minimum height and explicit state tints. Use `styleField` for private search/action/selection inputs, with matching outline, 52 dp minimum height, readable hint/text colors and IME learning suppression. These helpers retain Android selection, focus, switch semantics and input behavior.
