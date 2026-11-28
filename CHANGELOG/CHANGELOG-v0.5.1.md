## v0.5.1 — unified overlay style and expand/collapse animation

### Overlay
- All panel buttons use one line-icon set (Lucide, 2 px stroke, round caps) instead of mixed Unicode symbols, so every icon has the same weight and optical size.
- Move / resize handles use the same icon set in terracotta.
- Floating ball: terracotta disc with a soft halo and a touchpad line icon; icon colour flips between ivory and warm black for contrast.
- Cursor: rounded arrow with a contrasting outline and soft shadow; the tip stays exactly on the click point. Cursor colours are now warm tones (ivory, warm black, terracotta, coral, sand, olive, mist blue); old colours are mapped to the closest new one.
- "Button icon size" now sets the line-icon size in dp (capped at 56% of the button).

### Animation
- Collapsing shrinks the panel toward the floating ball while it fades, then the ball pops in with a slight overshoot.
- Expanding shrinks the ball away and grows the panel out from the ball's position.
- Only user actions animate (ball tap, minimise button); keyboard auto-hide and rotation switch instantly. Respects the system "animator duration scale" setting.

### Settings
- Button map and action picker use the same line icons; the picker marks the current action in terracotta.

### Licences
- Icons: Lucide v0.460.0, ISC (`licenses/Lucide-ISC.txt`).

### Install
`versionCode 14`, same signing key as before. Installs over the previous version.
