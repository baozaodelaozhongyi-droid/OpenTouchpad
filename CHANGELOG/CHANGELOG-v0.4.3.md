## v0.4.3 — smaller panel, corner buttons, ball colour

### Layout
- Smaller overall: max button size 56→46 dp, default panel 288×248 dp. Panels that were already resized are scaled to 80% once on upgrade.
- Four new custom buttons in the corners (16 slots total). Long-press any button on the panel, or use the settings list, to change it.
- Default corner buttons: top-left scroll left, top-right scroll right, bottom-left swipe left, bottom-right swipe right.
- Fix: setting a slot to "None" now leaves that spot empty instead of shifting the following buttons.
- Fix: long-press picker changes the pressed slot even if the same action appears twice.

### Floating ball
- New floating ball colour setting (theme default + 9 colours). The icon switches between light and dark for contrast.

### Install
`versionCode 10`, same signing key as before. Installs over the previous version.
