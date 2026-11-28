## v0.4.4 — uniform compact grid

### Layout
- Every gap is now the same: button↔button in rows, side columns and corners, and button↔touchpad. Previously the top row was squeezed while the side columns spread out.
- Default gap 3 dp (slider 0–24 dp), outer margin 1 dp.
- Minimum panel width 180→110 dp; default width 168 dp. Upgrading shrinks a saved width to 85% once.
- Height is now "compact grid + extra height". At 0 extra the panel is as short as the grid allows; extra height only makes the touchpad taller and keeps side buttons packed.
- Resize handle: horizontal drag changes width (buttons scale), vertical drag changes extra height.

### Install
`versionCode 11`, same signing key as before. Installs over the previous version.
