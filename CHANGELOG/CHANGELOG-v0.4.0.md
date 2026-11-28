## v0.4.0 — screenshot-style control surface and custom swipe

### UI
- Replaced the stacked touchpad panel with a transparent screenshot-style control surface: a large rounded touch area surrounded by circular action buttons.
- Width and height are independent settings and can be adjusted up to the device screen bounds.
- Buttons use circular grey controls; the arrow cursor remains a white desktop pointer with a contrasting outline.
- The existing floating ball, theme modes, opacity, position persistence and button customization remain available.

### Gestures
- Hold still on the touchpad until the long-press vibration, move the cursor, then release to execute one custom swipe from the hold point to the release point.
- Short movement without a long press only moves the cursor and does not click.
- A stationary short press still clicks; dwell-click remains independent.

### Verification
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `git diff --check`

### Install
This release uses `versionCode 7` and can be installed over v0.3.1.
