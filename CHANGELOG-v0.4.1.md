## v0.4.1 — layout and overlay fixes

### Fixes
- Move and resize handles now use the same size as the round action buttons and sit exactly in the top-left / bottom-right corners.
- Only the move handle drags the panel. The empty area around the touchpad no longer moves it.
- Cursor taps and swipes now pass through the OpenTouchpad panel, so the cursor can click the app underneath the touchpad and its buttons.
- Button spacing only changes the distance between buttons. Touchpad size and button size are no longer affected.

### New settings
- Floating ball size can go down to 16 dp.
- New "Hide floating ball" switch. When collapsed with the ball hidden, use "Show / hide touchpad" in settings to expand.
- Cursor size can go down to 8 dp.
- New cursor opacity slider (10–100%).

### Verification
- `:app:testDebugUnitTest`
- `:app:assembleDebug`

### Install
`versionCode 8`, same signing key as v0.4.0. Installs over the previous version.
