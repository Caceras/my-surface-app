# Current preview changes

- Android action forms support keyboard **Done** and hardware Enter. Invalid values stay editable; repeated submission cannot open the destination twice after a successful handoff.
- Settings → **Help & feedback → Copy app info** prepares build, package, phone, Android and app-language details for a bug report. It copies locally and excludes chats, drafts, recordings and identifiers such as serial numbers.
- Release publication now finishes active uploads while newer candidates wait. It checks branch HEAD again before promoting the rolling preview and verifies every uploaded asset, including the evidence ZIP.
- Evidence verification rejects extra unlisted files and symbolic links. Publication regressions cover stale builds, failed downloads, draft repair, immutable releases and reruns.

Pixel validation still matters: check Gboard Done, real offline recognition/playback, headset interruption, assistant gestures and a data-preserving update with your signing key. Native core fixtures do not certify these behaviors.
