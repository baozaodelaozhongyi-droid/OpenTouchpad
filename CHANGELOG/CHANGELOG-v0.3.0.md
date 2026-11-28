## v0.3.0 — pointer and floating controls

### Features
- Replaced the dot cursor with a familiar desktop-style arrow pointer whose tip tracks the injected touch position.
- Expanded cursor size range and retained selectable cursor colours.
- Added independent touchpad width and height controls; the resize handle now changes both dimensions in place.
- Kept the touchpad movable across the full screen and restored its position safely after rotation or relaunch.
- Added a draggable floating ball for the minimized state; tap it to restore the touchpad and drag it anywhere on screen.
- Added floating-ball size and opacity controls with persistent settings.
- Refreshed the settings screen with a modern indigo hero card, clearer sections, larger controls, and accessible contrast.

### Verification
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:assembleRelease`
- `git diff --check`

### Install
Enable the accessibility service after installing. On Android 13+, sideloaded packages may require Settings → Apps → OpenTouchpad → ⋮ → Allow restricted settings.
