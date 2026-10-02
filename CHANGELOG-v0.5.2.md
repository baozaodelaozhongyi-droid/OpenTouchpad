## v0.5.2 — drag lock rework, long-press time, transition fix

### Drag lock
- Old behaviour held an injected finger down between the two presses. Android cancels an injected gesture as soon as a real finger touches the screen, so moving the cursor on the touchpad turned the drag into a plain tap.
- New behaviour: first press marks the start point (terracotta ring on screen, drag-lock button turns terracotta). Move the cursor as usual — the touchpad only moves the cursor while armed (no tap / long press / dwell). Second press injects the whole drag in one go: hold at the start, follow the recorded path to the cursor, release.
- Pressing again without moving cancels. Armed state survives collapsing/expanding the panel.

### Long press at cursor
- New setting "Long press at cursor: hold time", 300–3000 ms (default 800 ms). Drag lock also holds this long at the start before moving, so apps that need a long press to pick up an item work.
- The existing touchpad long-press slider is renamed "Touchpad long-press threshold" to tell them apart.

### Transition
- Disabled the system window enter/exit animation on the panel, ball and cursor windows; it was snapshotting the removed window and fading it on top of our own animation (the flash/ghost).
- The incoming window is transparent before it is added, and the outgoing window commits a fully transparent frame before removal.

### Install
`versionCode 15`, same signing key as before. Installs over the previous version.
