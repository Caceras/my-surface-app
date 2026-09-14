# Ægentica AI design system

## Identity

The product name is **Ægentica AI**. The signum is **Æ**, drawn as a custom geometric vector ligature. It does not depend on an embedded font, raster image or external asset. Internal package/class names and the rolling APK URL preserve compatibility with earlier Surface Preview installs and backups.

The launcher has an adaptive foreground/background icon and an Android 13+ monochrome layer for themed icons. Android 12+ launch styling uses the same mark and sky-blue background through the native splash-screen attributes. There is no artificial splash delay or extra splash activity.

## Color

| Token | Light | Dark | Purpose |
|---|---|---|---|
| Background | `#F6FAFD` | `#0C1720` | Cool, quiet canvas |
| Primary text | `#142D3D` | `#ECF7FF` | Body and headings |
| Secondary text | `#526B7B` | `#A6BFCE` | Supporting copy |
| Sky accent | `#70CEFA` | `#70CEFA` | Primary button and brand fill |
| Accent text | `#12618A` | `#91DAFF` | Readable links and text controls |
| On accent | `#072E44` | `#072E44` | Dark text on sky-blue buttons |
| Composer | `#FFFFFF` | `#142430` | Editable surface |
| Presence | `#DDF3FF` | `#173E55` | Æ activity mark background |

Sky blue is a fill/accent, not pale body text on white. Secondary colors preserve readable contrast. Contrast checks are calculated from sRGB token pairs; disabled states, anti-aliasing, system switches and every rendered state still require visual/device review.

Measured token contrast: primary/body 13.59:1 light and 16.66:1 dark; secondary/body 5.34:1 and 9.46:1; accent-text/body 6.45:1 and 11.80:1; dark text on sky accent 8.02:1. These calculations use the checked-in sRGB values.

## Layout and behavior

- Content has a 720 dp maximum reading width in wide windows. The background still fills the app window.
- The chat header adapts at narrow widths and larger font settings. Voice status and content scroll together, keeping the action/navigation area accessible in short windows.
- Framework text remains scalable. Primary targets are at least 48 dp, with 52 dp pill controls. Custom text controls expose button semantics; key titles expose headings.
- Use system back, selection, IME and keyboard conventions. Respect system animation preferences, use brief entry motion and state-based feedback, and avoid idle animation.
- Light/dark widget colors follow app resources. Widgets use compact/full layouts and fixed reachable Type/Talk targets; private answer text is opt-in.

## Validation

See [testing](testing.md) for native compact, landscape, wide, large-font, widget and icon renders. These are core-variant layouts with deterministic data, not real Gemini Nano answers. Physical-device checks remain required for themed icon masks, splash timing, gesture navigation, font/display settings and TalkBack.

## Shared controls

Use `sheetHeader` for Settings, History and Actions. The Done control has explicit wrap-content dimensions beside a weighted title. Use `preferenceSwitch` for native settings/voice toggles; it keeps semantic text colors, 56 dp minimum height and explicit state tints. Use `styleField` for private search/action inputs, with matching outline, 52 dp minimum height, readable hint/text colors and IME learning suppression. These helpers retain Android selection, focus, switch semantics and input behavior.
