# Get started

[Download Nano](https://github.com/Caceras/my-surface-app/releases/download/preview-improve-pixel-assistant/aegentica-ai-nano.apk) · [README](../README.md)

## Install

Use the Nano APK on the Pixel. The core APK is a developer demo that uppercases text; it does not contain an AI model. Open **Ægentica AI**, not the older Pixel Surface Lab icon. Check Settings for the version and compare it with the release title.

Installation and initial model/language downloads require connectivity. Open Settings and choose **Check / prepare on-device model**. A speech language download does not install the language model, and a model download does not install a text-to-speech voice: they are independent requirements.

For an update, open the new APK and use Android’s Update action when offered; verify the new version and history. Keep the old app installed until you have a safe export if Android rejects it. Each release states its actual signing mode.

Each new handoff can use a build-specific APK link from its release or CI summary. The usual preview link remains a convenience alias. Verify the version in Settings after installing.

## Daily controls

| Control | Behavior |
|---|---|
| Composer → Send | Sends the typed or reviewed draft; becomes Stop response while generating |
| Composer microphone | Dictates into an editable draft; it does not send automatically |
| Voice | Starts a foreground voice session, requesting microphone permission if needed |
| Finish speaking | Asks the recognizer to finish the current utterance |
| Pause while speaking | Lets Android finalize the utterance and sends it in Voice mode |
| Keep talking | Listens again after playback finishes; session-only and disabled when leaving |
| Quiet voice | Mutes the remaining speech while the answer continues as text |
| Stop response | Cancels the answer; restores the pending question as a draft where possible |
| Stop speaking | Stops playback after generation has finished |
| Listen / Copy / Share | Acts on a completed answer |
| Latest reply | Returns to the bottom after reading earlier chat messages or voice text |
| Actions | Opens Clock, Calendar, Maps and Dialer handoffs you choose |
| New | Saves the current conversation or draft and starts an empty chat |
| History | Searches saved content, resumes a conversation, or deletes saved entries |

Opening Settings, History or Actions pauses capture, speech and active generation, preserving your draft or pending question. Returning does not restart them automatically. Leaving the app also stops capture, speech, and active generation. A cancelled or failed partial answer is not saved as a completed exchange. AICore inference is a foreground capability; keeping an assistant session running behind other apps is not supported.

## Voice setup

1. Open **Settings → Set up voice & test playback**.
2. Select **Choose speaking language**. Use System language or one of the offered locales. For regional English problems, try English (United States).
3. Select **Download offline speech**. The app asks Android which matching language packs are supported. “Queued” is not “ready”; wait for confirmation or try again later.
4. Select **Test speaker**. If no installed offline playback voice is available, open **Android settings → Spoken replies** and install one through your TTS engine.
5. Tap **Talk**. Start with one short question, then test **Keep talking**.

Setup itself does not start recording. Actual recognition, voice quality, availability, and download behavior depend on the installed Android services. Changing the speaking language controls speech; it does not guarantee the language model answers accurately in that language.

## Pixel Quick Tap

For quick access, choose **Android Settings → System → Gestures → Quick Tap → Open app → Ægentica AI**. Double-tapping the back of the phone can then open chat. This is a Pixel setting you configure, not a permission the app can silently grant itself. [Pixel gesture guide](https://support.google.com/pixelphone/answer/7443425).

## Android shortcuts

Long-press the Ægentica AI launcher icon for Chat, Voice (Android 12+), History and Actions. Settings can request pinned Chat/Voice shortcuts with your launcher's confirmation. No conversation text or private title is published as a shortcut label.

With a hardware keyboard: **Ctrl+Enter** sends, **Ctrl+N** saves the current chat and starts a new one, **Ctrl+L** focuses the composer, and **Ctrl+Shift+M** opens voice. Plain Enter remains available for a new line. Android's keyboard-shortcut help lists these commands.

Use **Actions** for a timer, alarm, calendar draft, place search or phone number. These open the destination Android app; Ægentica AI does not execute commands found in an AI answer. [Action details](native-actions.md).

## Privacy controls

**Settings → Show last answer on widget** defaults off. Enable it only if you want the home screen to display your answer. Both widget sizes keep Type and Talk; a compact widget omits the answer entirely.

**Private screen** hides app previews and blocks screenshots/screen sharing of the app's windows. This is separate from the widget preference and does not encrypt exported files. Copies use Android's sensitive-clipboard marker to suppress compatible clipboard previews; a keyboard's no-personalized-learning flag is a request, not a universal guarantee.

Headset pause/stop and headphone disconnect stop spoken output. The app never resumes an interrupted answer or microphone automatically in response to these events. Real Bluetooth behavior depends on Android and the output device.

## Save and restore

The app keeps up to 40 completed exchanges in the current conversation. New stores a recent conversation, subject to archive retention limits. History shows dates and previews; searching also matches saved questions, replies, and drafts.

Use **Settings → Export conversation** to export the current conversation, current draft, and retained archives. Open **Restore conversation** to select the JSON file and confirm restoration. Existing current content is first archived, subject to the same retention limits. Export before important migrations. Read the file after export if it contains data you cannot afford to lose.

Settings such as speech language, privacy options and the read-aloud preference are not part of this conversation backup. Android backup is disabled. Do not expect an uninstall or a new application ID to migrate app-private data automatically.

## Troubleshooting

| Symptom | Next action |
|---|---|
| Replies are uppercase | Install the **Nano** APK; the core variant is a demo |
| The old UI still appears | Open Ægentica AI and verify the version in Settings |
| Package/signature conflict | Export first, then reinstall and restore; see [signing](delivery.md#signing) |
| Model unavailable | Update Android/AICore, check device support, and retry model setup |
| Model busy or quota reached | Wait and retry with a shorter request; repeated taps are not useful |
| Missing offline language | Choose a supported locale and prepare its speech pack |
| Permission denied | Android Settings → Apps → Ægentica AI → Permissions → Microphone |
| Text appears but nothing is spoken | Test the speaker, check media volume/output device, and install an offline TTS voice |
| Another app interrupts playback | Return and use Listen when audio is available; Ægentica AI does not fight for focus |
| “This backup is too large” | Shorten the draft or remove unwanted saved conversations, then export again |
| Share/selection action absent | The source app must expose plain text and support Android's relevant action |

For a useful bug report, include the build, Android version, speech language, observed behavior, and reproducible steps. Remove private text from screenshots and exports.

## Reporting a problem

In **Settings → Help & feedback**, tap **Copy app info**. Paste it with the exact steps and message. It includes app version/package, phone model, Android and app language, with no chats, drafts, recordings or device serial. Add selected speech language, AICore version and speaker/headset manually for voice issues. Nothing is sent automatically.

## Recover a request

A failed chat answer offers **Edit question**. If you have already typed a different draft, choose whether to keep it or use the earlier question. A failed Voice question is saved for Type when the draft is empty. Rotation pauses Voice; tap Talk when ready. Selection Ask preserves its prompt and cursor after recreation and keeps empty requests editable.
