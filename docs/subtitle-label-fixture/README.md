# Synthetic addon subtitle-label reproduction

The before/after images use the installed Desktop 0.1.34-alpha controls HTML,
CSS and JavaScript in a loopback-only Chromium fixture. Both images are visibly
watermarked. There is no patched installed application, native playback or
audio-sync claim. Only synthetic Example Addon option metadata differs:

- Before: both Dutch alternatives carry `Dutch (Example Addon)`.
- After: metadata carries the addon labels `OSv3` and `SubDL`, with pending icons.

The actual patched Kotlin parser is separately compiled and covered by the
included AddonSubtitleLoaderTest; its output label is kept separate from the
normalized language, addon name, selection ID and download URL. The screenshot
fixture checks how those fields render in the existing desktop controls.

No layout or style is changed by the patch, and no real movie, account, private
link, report, API key or provider/model request is included. The loopback fixture
blocks outgoing connections. Full Dev application/native UI acceptance remains
pending before this draft should be marked ready for review.
