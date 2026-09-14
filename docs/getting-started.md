# Get started

[Download Nano](https://github.com/Caceras/my-surface-app/releases/download/preview-improve-pixel-assistant/pixel-surface-lab-nano.apk) · [README](../README.md)

## Install

Use the Nano APK on the Pixel. The core APK is a developer demo that uppercases text; it does not contain an AI model. Open **Surface Preview**, not the older Pixel Surface Lab icon. Check Settings for the version and compare it with the release title.

Installation and initial model/language downloads require connectivity. Open Settings and choose **Check / prepare on-device model**. A speech language download does not install the language model, and a model download does not install a text-to-speech voice: they are independent requirements.

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
| New | Saves the current conversation or draft and starts an empty chat |
| Conversations | Searches saved content, resumes a conversation, or deletes saved entries |

Leaving the app stops capture, speech, and active generation. A cancelled or failed partial answer is not saved as a completed exchange. AICore inference is a foreground capability; keeping an assistant session running behind other apps is not supported.

## Voice setup

1. Open **Settings → Set up voice & test playback**.
2. Select **Choose speaking language**. Use System language or one of the offered locales. For regional English problems, try English (United States).
3. Select **Download offline speech**. The app asks Android which matching language packs are supported. “Queued” is not “ready”; wait for confirmation or try again later.
4. Select **Test speaker**. If no installed offline playback voice is available, open **Android settings → Spoken replies** and install one through your TTS engine.
5. Tap **Talk**. Start with one short question, then test **Keep talking**.

Setup itself does not start recording. Actual recognition, voice quality, availability, and download behavior depend on the installed Android services. Changing the speaking language controls speech; it does not guarantee the language model answers accurately in that language.

## Save and restore

The app keeps up to 40 completed exchanges in the current conversation. New stores a recent conversation, subject to archive retention limits. Conversations shows dates and previews; searching also matches saved questions, replies, and drafts.

Use **Settings → Export conversation** to export the current conversation, current draft, and retained archives. Open **Restore conversation** to select the JSON file and confirm restoration. Existing current content is first archived, subject to the same retention limits. Export before important migrations. Read the file after export if it contains data you cannot afford to lose.

Settings such as the speech language and read-aloud preference are not part of this conversation backup. Android backup is disabled. Do not expect an uninstall or a new application ID to migrate app-private data automatically.

## Troubleshooting

| Symptom | Next action |
|---|---|
| Replies are uppercase | Install the **Nano** APK; the core variant is a demo |
| The old UI still appears | Open Surface Preview and verify the version in Settings |
| Package/signature conflict | Export first, then reinstall and restore; see [signing](delivery.md#signing) |
| Model unavailable | Update Android/AICore, check device support, and retry model setup |
| Model busy or quota reached | Wait and retry with a shorter request; repeated taps are not useful |
| Missing offline language | Choose a supported locale and prepare its speech pack |
| Permission denied | Android Settings → Apps → Surface Preview → Permissions → Microphone |
| Text appears but nothing is spoken | Test the speaker, check media volume/output device, and install an offline TTS voice |
| Another app interrupts playback | Return and use Listen when audio is available; Surface does not fight for focus |
| “This backup is too large” | Shorten the draft or remove unwanted saved conversations, then export again |
| Share/selection action absent | The source app must expose plain text and support Android's relevant action |

For a useful bug report, include the build, Android version, speech language, observed behavior, and reproducible steps. Remove private text from screenshots and exports.
