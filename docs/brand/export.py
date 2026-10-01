# Renders the PNG exports and favicon.ico from svg/. Run after mascot.py / icon.py.
# Needs headless Chromium (or Chrome) and ImageMagick on PATH.
import re, shutil, subprocess, tempfile
from pathlib import Path

HERE = Path(__file__).parent
SVG, PNG = HERE / "svg", HERE / "png"

# (svg, output height in px, ...); width follows the viewBox.
EXPORTS = {
    "titan-mascot.svg": (256, 512, 1024),
    "titan-mascot-on-dark.svg": (1024,),
    "titan-mascot-no-outline.svg": (512,),
    "titan-mascot-mono-black.svg": (512,),
    "titan-head.svg": (512,),
    "titan-lockup-dark.svg": (256, 512),
    "titan-lockup-light.svg": (256, 512),
    "titan-play-icon.svg": (512,),  # Play Store listing icon
    "titan-favicon.svg": (16, 32, 48, 180, 192, 512),
}

def browser():
    for b in ("chromium", "chromium-browser", "google-chrome", "google-chrome-stable"):
        if shutil.which(b):
            return b
    raise SystemExit("needs Chromium or Chrome on PATH")

def size(src: Path, height: int):
    vb = re.search(r'viewBox="([\d. -]+)"', src.read_text()).group(1).split()
    return round(height * float(vb[2]) / float(vb[3])), height

def main():
    # Every export goes on one page, captured in a single screenshot and cropped apart:
    # one browser launch instead of twenty, which a snap-confined Chromium otherwise hangs on.
    jobs, y, gap = [], 0, 8
    for name, heights in EXPORTS.items():
        for h in heights:
            w, h = size(SVG / name, h)
            jobs.append((SVG / name, PNG / f"{Path(name).stem}-{h}.png", w, h, y))
            y += h + gap
    width = max(j[2] for j in jobs)
    imgs = "".join(f'<img src="{src.resolve().as_uri()}" width="{w}" height="{h}" '
                   f'style="position:absolute;left:0;top:{top}px">' for src, _, w, h, top in jobs)
    PNG.mkdir(exist_ok=True)
    # Not /tmp and not a dotfile: a snap-confined Chromium can reach neither.
    page, sheet = HERE / "export-sheet.html", HERE / "export-sheet.png"
    page.write_text(f'<html><body style="margin:0">{imgs}</body></html>')
    cmd = [browser(), "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
           "--default-background-color=00000000", f"--window-size={width},{y}",
           f"--screenshot={sheet.resolve()}", page.as_uri()]
    for attempt in range(3):  # headless Chromium occasionally hangs on start-up; a retry clears it
        try:
            subprocess.run(cmd, check=True, capture_output=True, timeout=60)
            break
        except subprocess.TimeoutExpired:
            if attempt == 2:
                raise
    for _, out, w, h, top in jobs:
        subprocess.run(["magick", str(sheet), "-crop", f"{w}x{h}+0+{top}", "+repage", str(out)], check=True)
        print(out.relative_to(HERE))
    page.unlink(); sheet.unlink()
    fav = [PNG / f"titan-favicon-{s}.png" for s in (16, 32, 48)]
    subprocess.run(["magick", *map(str, fav), str(HERE / "favicon.ico")], check=True)
    print("favicon.ico")

if __name__ == "__main__":
    main()
