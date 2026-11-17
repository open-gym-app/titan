# Titan sprite

`core/ui/.../component/titan/TitanSpritePixels.kt` is generated from the Titan Companion design
canvas's sprite artboard (`Titan.dc.html`): one Kotlin `px(...)` per SVG `<rect>`, on the
sprite's 64 × 40 pixel grid, grouped as the drawing groups them.

To regenerate after the art changes, save the artboard's `<svg>` element to a file and run:

```sh
python3 -I tools/titan-sprite/sprite2kt.py sprite.svg > core/ui/src/main/kotlin/com/titan/core/ui/component/titan/TitanSpritePixels.kt
```

The script refuses a rectangle off the 2.5-unit grid, and a group it has no part name for.
The motion is not generated: it lives in `TitanPoses.kt`, translated from the artboard's
keyframes by hand.
