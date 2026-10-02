# openDoJa

`openDoJa` is a desktop-focused clean-room reimplementation of the DoJa 5.1 runtime and related APIs, aimed at running i-appli Java games on modern computers.

<p align="center">
  <img src=".github/showcase.png" alt="showcase" />
</p>

## Requirements
Java 22+

## Build

```bash
mvn -q -DskipTests package
```

## GitHub Actions

- Every push to `master` rebuilds the rolling `nightly` GitHub release and replaces its attached JAR.
- A GitHub release is created when the `pom.xml` version changes.

## Download

- Nightly (most up-to-date) version: https://github.com/GrenderG/openDoJa/releases/download/nightly/opendoja-nightly.jar
- Latest (stable) version: https://github.com/GrenderG/openDoJa/releases/latest

## Run

Open the desktop launcher UI:

```bash
java -jar opendoja-{version}.jar
```

Launch a specific JAM directly through the packaged launcher:

```bash
java -jar opendoja-{version}.jar --run-jam <game.jam>
```

Launch a JAM directly without the launcher UI, with explicit host scale, synth, user ID, and terminal ID:

```bash
java \
  -Dopendoja.hostScale=<x|fullscreen> \
  -Dopendoja.mldSynth=<fuetrek|ma3> \
  -Dopendoja.userId=<uid> \
  -Dopendoja.terminalId=<tid> \
  -jar opendoja-{version}.jar --run-jam <game.jam>
```

Print all available launcher options:

```bash
java -jar opendoja-{version}.jar --help
```

## UI Skins

The window chrome (top status bar, bottom soft-key bar and the strips beside the screen) can be re-skinned.

- Pick a skin from **Settings > UI Skin** in the launcher, or from the right-click menu of the game window
  (the game window switches immediately). The choice is remembered for the next launch.
  `-Dopendoja.uiSkin=<id>` overrides the remembered choice.
- **File > Open Skin Folder** opens `.opendoja/skin/` (next to the jar). Each skin is a sub-folder that holds a
  `skin.ini` and the images it references:

```
.opendoja/skin/<skin-id>/skin.ini
.opendoja/skin/<skin-id>/*.png
```

The built-in skin is a complete example: copy `src/main/resources/opendoja/images/icons/n900i/` (`skin.ini` plus
the PNGs) to `.opendoja/skin/<your-id>/` and edit it. Choosing the current skin again reloads it from disk.

All coordinates are **absolute, unscaled pixels**; the origin of `x`/`y` is the top-left corner of the bar the
element belongs to. Host scaling (x2, x3, fullscreen, ...) uses nearest-neighbor sampling, so author images at 1x.

```ini
[skin]
name = My Skin

[layout]
top_bar_height    = 27
bottom_bar_height = 27
side_width        = 2            ; strips left/right of the game screen
side_color        = #D7D8DB      ; #RRGGBB or #AARRGGBB
top_bg_color      = #D7D8DB
top_bg_image      =              ; optional, drawn over the color at the bar's top-left, original size
bottom_bg_color   = #D7D8DB
bottom_bg_image   =

[icon]                           ; any number of [icon] / [icon.anything] sections, drawn in file order
type   = battery                 ; none | battery | signal_antenna | signal | no_signal | iappli
bar    = top                     ; top (default) | bottom
anchor = right                   ; left (default) | right: with right, x is the gap to the bar's right edge
x      = 2
y      = 1
images = battery_0.png, battery_1.png, battery_2.png, battery_3.png

[softkey.1]                      ; and [softkey.2]
anchor    = left                 ; left | right: which bar edge x is measured from
align     = center               ; left | center | right: how the text sits on that point
x         = 28
y         = 8                    ; top of the text; omit to center vertically
font_size = 12                   ; snapped to the nearest supported size (8,10,12,16,20,24,30)
color     = #FFFFFF
bg_image  = soft_label_bg.png    ; shown only while the label has text
bg_x      = 2                    ; same anchor rule, using the image's left (or right) edge
bg_y      = 0
```

`images` (alias `image`) is a comma-separated list whose order is the state order: battery level 0-3, signal
strength 1-3, and for `iappli` normal / standby / dx / dx-standby. `type = none` has no role, so it is always
shown and can be used any number of times for decoration. Types that openDoJa does not implement yet (for example
multitasking, SD card or unread mail) are skipped with a warning; use `type = none` to show their image statically.
Elements are clipped to their bar. A skin that fails to load falls back to the built-in one
(run with `-Dopendoja.logLevel=warn` to see why). The skin is ignored when the external frame is disabled.

## Reporting Broken Games

If a game does not work, please open a GitHub issue using the broken game report template.

Include:

- a text description of the issue
- the exact game that does not work
- screenshots or videos if they help explain the problem
- logs or stack traces if the issue is a crash

For the bundled local workflow used during development, see `scripts/`.

## Third-party Dependencies
- [jogl](https://github.com/sgothel/jogl)
- [input4j](https://github.com/gurkenlabs/input4j)
