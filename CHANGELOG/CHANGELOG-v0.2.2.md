## v0.2.2 — gesture and panel stability

### Fixes
- Fixed drag gesture continuation coordinates so Android can keep one continuous pointer stream.
- Fixed dwell click when the finger remains completely still after touch-down.
- Prevented `ACTION_CANCEL` from generating an accidental click.
- Cleaned up delayed callbacks and active drag state when the accessibility service stops.
- Resized the touchpad in place instead of rebuilding its overlay during the resize gesture.
- Kept the floating panel inside the screen after moving, rotating, resizing, or restoring its position.
- Preserved button slots when an old or unknown action id is found in saved settings.
- Fixed the initial cursor position and fractional-density resize conversion.

### Install
Enable the accessibility service after installing. On Android 13+, sideloaded packages may require Settings → Apps → OpenTouchpad → ⋮ → Allow restricted settings.
