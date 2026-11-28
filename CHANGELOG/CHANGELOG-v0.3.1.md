## v0.3.1 — cursor alignment and appearance modes

### Fixes
- Aligned the arrow tip with the actual injected click coordinate and placed the cursor overlay in the same screen coordinate space as the touch gestures.
- Reduced the touchpad's minimum adjustable width from 280dp to 180dp.
- Reflowed action buttons into three columns for compact touchpad widths.

### Appearance
- White mode is now the default.
- Added black mode.
- Added follow-system mode; the settings screen and floating touchpad update together.

### Verification
- `clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
- `git diff --check`

### Install
This release uses `versionCode 6` and can be installed over v0.3.0.
