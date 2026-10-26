"""
Builds filled-in handwriting sheets from handwriting specimen pages, for scan-extraction tests.

A specimen page is the 8-column grid from `specimen_template()`: one blue box per character with a
grey label and a red writing line. A person (or an image model) writes each character on the red
line. This script cuts every character out together with its baseline and places the set on the
current sheet layout from gen_practice_sheet.py, so the same specimen still works after the sheet
changes. The writer's proportions are kept: the set is scaled once so its median x-height fills the
dashed band.

Usage:
    python gen_handwritten_sheets.py --template OUT.png
    python gen_handwritten_sheets.py SPECIMEN.png [SPECIMEN.png ...] --out DIR
"""

import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import gen_practice_sheet as sheet

SPEC_W, SPEC_H = 1240, 1754
SPEC_COLS, SPEC_ROWS = 8, 9
SPEC_MARGIN, SPEC_TOP = 80, 120
SPEC_BASELINE = 0.72
SPEC_CHARS = list("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789ÄÖÜäöüß")
SPEC_BLUE = (120, 170, 230)
SPEC_RED = (235, 120, 120)
X_HEIGHT_CHARS = set("acemnorsuvwxz")
INK_LUMA_MAX = 150
LINE_SNAP = 20


def spec_box(index: int, scale_x: float = 1.0, scale_y: float = 1.0):
    cw = (SPEC_W - 2 * SPEC_MARGIN) / SPEC_COLS
    ch = (SPEC_H - SPEC_TOP - SPEC_MARGIN) / SPEC_ROWS
    col, row = index % SPEC_COLS, index // SPEC_COLS
    x0, y0 = SPEC_MARGIN + col * cw, SPEC_TOP + row * ch
    return x0 * scale_x, y0 * scale_y, (x0 + cw) * scale_x, (y0 + ch) * scale_y


def specimen_template() -> Image.Image:
    img = Image.new("RGB", (SPEC_W, SPEC_H), "white")
    draw = ImageDraw.Draw(img)
    label_font = sheet.load_font(22)
    for i, c in enumerate(SPEC_CHARS):
        x0, y0, x1, y1 = spec_box(i)
        draw.rectangle((x0, y0, x1, y1), outline=SPEC_BLUE, width=3)
        base = y0 + (y1 - y0) * SPEC_BASELINE
        draw.line((x0 + 4, base, x1 - 4, base), fill=SPEC_RED, width=2)
        draw.text((x0 + 6, y0 + 4), c, fill=(185, 185, 185), font=label_font)
    draw.text(
        (SPEC_MARGIN, 50),
        "Handwriting specimen: write each character once, standing on the red line.",
        fill=(60, 60, 60),
        font=sheet.load_font(26),
    )
    return img


def _snap(profile: np.ndarray, nominal: int) -> int:
    lo, hi = max(0, nominal - LINE_SNAP), min(len(profile), nominal + LINE_SNAP + 1)
    return lo + int(np.argmax(profile[lo:hi])) if profile[lo:hi].max() > 0 else nominal


def extract_glyphs(path: Path):
    """Returns {char: (rgba crop, baseline row in the crop)} from a filled specimen page."""
    rgb = np.asarray(Image.open(path).convert("RGB")).astype(int)
    h, w, _ = rgb.shape
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    luma = (r * 299 + g * 587 + b * 114) // 1000
    blue = (b > r + 40) & (b > g + 15) & (luma > 120)
    red = (r > g + 60) & (r > b + 60)
    sx, sy = w / SPEC_W, h / SPEC_H
    glyphs = {}
    for i, c in enumerate(SPEC_CHARS):
        x0, y0, x1, y1 = (int(round(v)) for v in spec_box(i, sx, sy))
        # Snap the nominal box edges onto the printed blue outline.
        band_y = slice(max(0, y0 + 10), min(h, y1 - 10))
        band_x = slice(max(0, x0 + 10), min(w, x1 - 10))
        x0 = _snap(blue[band_y, :].sum(axis=0), x0)
        x1 = _snap(blue[band_y, :].sum(axis=0), x1)
        y0 = _snap(blue[:, band_x].sum(axis=1), y0)
        y1 = _snap(blue[:, band_x].sum(axis=1), y1)
        inset = max(6, int(6 * sx))
        bx0, by0, bx1, by1 = x0 + inset, y0 + inset, x1 - inset, y1 - inset
        red_rows = np.nonzero(red[by0:by1, bx0:bx1].sum(axis=1) > (bx1 - bx0) * 0.3)[0]
        baseline = by0 + (int(np.median(red_rows)) if len(red_rows) else int((by1 - by0) * SPEC_BASELINE))
        ink = (luma[by0:by1, bx0:bx1] < INK_LUMA_MAX) & ~blue[by0:by1, bx0:bx1] & ~red[by0:by1, bx0:bx1]
        # The printed label sits in the top-left corner.
        ink[: int(34 * sy), : int(34 * sx)] = False
        ys, xs = np.nonzero(ink)
        if len(ys) < 15:
            continue
        top, bottom, left, right = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
        crop = rgb[by0 + top:by0 + bottom, bx0 + left:bx0 + right]
        crop_luma = luma[by0 + top:by0 + bottom, bx0 + left:bx0 + right]
        alpha = np.clip((215 - crop_luma) * 255 // 120, 0, 255)
        alpha[~ink[top:bottom, left:right]] = 0
        rgba = np.dstack([crop, alpha]).astype(np.uint8)
        glyphs[c] = (Image.fromarray(rgba, "RGBA"), baseline - (by0 + top))
    return glyphs


def compose_sheet(glyphs) -> Image.Image:
    img = sheet.render().convert("RGBA")
    centres = sheet.fiducial_centers()
    gl, gt = centres["tl"]
    gw = centres["tr"][0] - gl
    gh = centres["bl"][1] - gt
    x_heights = [glyphs[c][1] for c in glyphs if c in X_HEIGHT_CHARS]
    if not x_heights:
        return img.convert("RGB")
    for char, left, top, right, bottom in sheet.normalized_cells():
        if char not in glyphs:
            continue
        cx0, cy0, cx1, cy1 = gl + left * gw, gt + top * gh, gl + right * gw, gt + bottom * gh
        band = (cy1 - cy0) * (sheet.GUIDE_BASELINE_RATIO - sheet.GUIDE_UPPER_RATIO)
        scale = band / float(np.median(x_heights))
        glyph, baseline = glyphs[char]
        base_y = cy0 + (cy1 - cy0) * sheet.GUIDE_BASELINE_RATIO
        # A writer keeps the letter on the line: a wide or tall one is squeezed, a descender runs on into the
        # box below (the scan follows it there).
        scale = min(scale, (cx1 - cx0) * 0.9 / glyph.width, (base_y - cy0 - 3) / max(1, baseline))
        g = glyph.resize((max(1, int(glyph.width * scale)), max(1, int(glyph.height * scale))), Image.LANCZOS)
        x = int((cx0 + cx1 - g.width) / 2)
        y = int(round(base_y - baseline * scale))
        img.alpha_composite(g, (x, min(y, img.height - g.height)))
    return img.convert("RGB")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("specimens", nargs="*", type=Path)
    parser.add_argument("--out", type=Path, default=Path("artifacts/handwritten_sheets"))
    parser.add_argument("--template", type=Path)
    args = parser.parse_args()
    if args.template:
        specimen_template().save(args.template)
        print(f"Wrote {args.template}")
    args.out.mkdir(parents=True, exist_ok=True)
    for spec in args.specimens:
        glyphs = extract_glyphs(spec)
        missing = "".join(c for c in SPEC_CHARS if c not in glyphs)
        out = args.out / f"{spec.stem}_sheet.png"
        compose_sheet(glyphs).save(out)
        print(f"{spec.name}: {len(glyphs)}/{len(SPEC_CHARS)} characters, missing '{missing}' -> {out}")


if __name__ == "__main__":
    main()
