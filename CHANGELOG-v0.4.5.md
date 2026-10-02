## v0.4.5 — redesigned settings page

### Settings UI
- Gradient header with app name, a status chip (green = running, amber = off) and a single primary "Open accessibility settings" button.
- Settings grouped into rounded cards per section, with thin dividers, light grey page background (near-black in dark mode).
- Sliders show the current value in a pill with units (dp, %, ms, ×). Hints sit under the title instead of as separate rows.
- Switch rows: the whole row is tappable, not just the toggle.
- Theme picker is a three-way segmented control.
- Colour swatches have a ring and check mark on the selected colour.
- Button editor is a mini map of the real panel (6×5 grid with move/resize keys); tap any button to change its action. Empty slots show a dashed "+".
- Setup steps, test checklist, GitHub and reset moved into the About card; reset is shown in red.
- Removed the "button corner radius" slider: buttons are circles since v0.4.0 and the slider had no effect.
- Fixed lint error: `windowLightNavigationBar` now marked API 27+.

### Install
`versionCode 12`, same signing key as before. Installs over the previous version.
