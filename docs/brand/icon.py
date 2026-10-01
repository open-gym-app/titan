# Adaptive launcher icon for Titan, drawn on Android's 108-unit grid. Run: python3 icon.py
# Everything stays inside the 66-unit safe circle centred on (54, 54).
from pathlib import Path
HERE = Path(__file__).parent
RES = HERE.parents[1] / "app/src/main/res/drawable"
INK, ORANGE, CREAM = "#1A1A1A", "#FF5A2B", "#FAFAF7"

def mx(x): return 108 - x

def rrect(x0, y0, x1, y1, r):  # rounded rectangle as a path, so it converts 1:1 to VectorDrawable
    return (f"M{x0 + r:g} {y0:g} H{x1 - r:g} Q{x1:g} {y0:g} {x1:g} {y0 + r:g} V{y1 - r:g} "
            f"Q{x1:g} {y1:g} {x1 - r:g} {y1:g} H{x0 + r:g} Q{x0:g} {y1:g} {x0:g} {y1 - r:g} "
            f"V{y0 + r:g} Q{x0:g} {y0:g} {x0 + r:g} {y0:g} Z")

def pair(x0, y0, x1, y1, r):  # a shape and its mirror across x = 54
    return rrect(x0, y0, x1, y1, r) + " " + rrect(mx(x1), y0, mx(x0), y1, r)

# The head of the mascot with the cartoon taken out: a dumbbell, and two hard brow slashes
# for the attitude. No eyes, no smile, no outline.
discs = pair(20, 40, 26, 66, 2)
plates = pair(26, 34, 37, 72, 3)
bar = "M37 42 H71 V64 H37 Z"
brows = f"M40 46.5 L51 52 V57.5 L40 52 Z M{mx(40)} 46.5 L{mx(51)} 52 V57.5 L{mx(40)} 52 Z"
T = "translate(54 54) scale(.86) translate(-54 -53)"

def fg(disc_col=INK):
    return (f'<g transform="{T}"><path d="{discs}" fill="{disc_col}"/>'
            f'<path d="{plates} {bar}" fill="{CREAM}"/><path d="{brows}" fill="{INK}"/></g>')

def mono():  # single colour; the brows are cut out
    return f'<g transform="{T}"><path fill-rule="evenodd" d="{discs} {plates} {bar} {brows}" fill="#000"/></g>'

def svg(inner, bg=None, rx=0):
    b = f'<rect width="108" height="108" rx="{rx}" fill="{bg}"/>' if bg else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">{b}{inner}</svg>\n'

# Favicon and Play Store icon crop to the 72-unit area a launcher mask would show, so the
# head fills them. The store applies its own corner mask, so that one stays square.
def cropped(rx):
    return svg(fg(), ORANGE).replace('viewBox="0 0 108 108"', 'viewBox="18 18 72 72"').replace(
        '<rect width="108" height="108" rx="0"', f'<rect x="18" y="18" width="72" height="72" rx="{rx}"')
out = {
    "titan-icon-foreground.svg": svg(fg()),
    "titan-icon-monochrome.svg": svg(mono()),
    "titan-icon.svg": svg(fg(), ORANGE),
    "titan-icon-dark.svg": svg(fg(ORANGE), INK),
    "titan-favicon.svg": cropped(16),
    "titan-play-icon.svg": cropped(0),
}
for n, c in out.items(): (HERE / "svg" / n).write_text(c)

# ---- Android VectorDrawable output ----
# T = translate(54 54) scale(.86) translate(-54 -53) is a scale about (54, 53) then a 1-unit drop.
GROUP = 'android:pivotX="54" android:pivotY="53" android:scaleX="0.86" android:scaleY="0.86" android:translateY="1"'

def vd(paths, comment):
    body = "\n".join(paths)
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Generated from docs/brand/icon.py; edit the script, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group {GROUP}>
{body}
    </group>
</vector>
'''

def p(d, fill=None, stroke=None, w=None, cap=None, join=None, evenodd=False):
    a = [f'android:pathData="{d}"']
    if fill: a.append(f'android:fillColor="{fill}"')
    if evenodd: a.append('android:fillType="evenOdd"')
    if stroke: a += [f'android:strokeColor="{stroke}"', f'android:strokeWidth="{w}"']
    if cap: a.append(f'android:strokeLineCap="{cap}"')
    if join: a.append(f'android:strokeLineJoin="{join}"')
    return "        <path\n" + "\n".join("            " + x for x in a) + " />"

fg_paths = [p(discs, INK), p(f"{plates} {bar}", CREAM), p(brows, INK)]
mono_paths = [p(f"{discs} {plates} {bar} {brows}", "#FF000000", evenodd=True)]

(RES / "ic_launcher_foreground.xml").write_text(vd(fg_paths, "Launcher icon foreground: the Titan head."))
(RES / "ic_launcher_monochrome.xml").write_text(vd(mono_paths, "Themed-icon layer (Android 13+): one colour, the launcher tints it."))
