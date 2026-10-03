## v0.5.3 — new cursor, show-touchpad switch, tap ring, scroll fix

### Cursor
- New pointer: solid rounded triangle (warm black by default) with a thick ivory outline and soft shadow; tip stays on the click point. Users still on the old ivory/white default are switched to warm black once; custom colours are kept.

### Settings
- "Show / hide touchpad" is now a switch ("Show touchpad") like the other toggles, and stays in sync when the panel is collapsed from the overlay.
- Changing theme, any colour or a button action no longer jumps back to the top of the page; the scroll position is kept across the rebuild.

### Visual feedback
- Every tap / long press injected at the cursor shows a terracotta ring: taps expand and fade, long presses tighten over the hold time then fade. Drag lock shows the long-press ring at the start point. The ring never receives touches.

### Install
`versionCode 16`, same signing key as before. Installs over the previous version.
