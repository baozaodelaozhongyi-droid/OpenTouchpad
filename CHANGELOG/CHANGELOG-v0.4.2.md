## v0.4.2 — compact side layout and refreshed look

### Layout
- Move and resize handles moved into the side columns: left column is move + 2 buttons, right column is 2 buttons + resize. The four corners stay empty.
- Tighter layout: outer margin 4→2 dp, gap between buttons and touchpad 6→4 dp, max button size 72→56 dp.
- Default button spacing 12→6 dp (only for users who never changed it).

### Look
- Buttons and touchpad use a soft vertical gradient with a thin highlight border.
- Round buttons show a ripple when pressed.
- Move/resize handles use vector icons and an accent tint so they stand out from action buttons; a light vibration on press.
- Touchpad corner radius follows the button size.

### Install
`versionCode 9`, same signing key as before. Installs over the previous version.
