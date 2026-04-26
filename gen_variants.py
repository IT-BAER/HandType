"""Generate 8 organic visual variants per glyph using Ink Free with aggressive
perturbations, elastic warping, stroke-width variation, and ink texture."""
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_PATH = r"C:\Windows\Fonts\Inkfree.ttf"
BASE_SIZE = 140
VARIANTS = 8
SUPPORTED = (
    list(range(0x41, 0x5B))   # A-Z
    + list(range(0x61, 0x7B)) # a-z
    + list(range(0x30, 0x3A)) # 0-9
    + [0x2E, 0x2C, 0x21, 0x3F, 0x27, 0x2D]  # . , ! ? ' -
)

OUT_DIR = Path(r"D:\VSC\HandType\app\src\main\assets\templates\classic_script\glyphs")
OUT_DIR.mkdir(parents=True, exist_ok=True)

# Remove old variant files
for f in OUT_DIR.glob("u*.png"):
    f.unlink()


# ---------------------------------------------------------------------------
# Elastic distortion — simulates natural hand tremor / pen wobble
# ---------------------------------------------------------------------------
def elastic_distort(img: Image.Image, alpha: float = 8.0, sigma: float = 3.0) -> Image.Image:
    """Apply a subtle elastic deformation to *img* (RGBA).

    *alpha* controls distortion strength and *sigma* controls smoothness.
    """
    arr = np.array(img)
    h, w = arr.shape[:2]

    # Random displacement fields smoothed with a Gaussian
    dx = np.random.uniform(-1, 1, (h, w)).astype(np.float32)
    dy = np.random.uniform(-1, 1, (h, w)).astype(np.float32)

    # Smooth using PIL Gaussian blur (avoids scipy dependency)
    dx_img = Image.fromarray(((dx + 1) * 127.5).astype(np.uint8), "L")
    dy_img = Image.fromarray(((dy + 1) * 127.5).astype(np.uint8), "L")
    dx_img = dx_img.filter(ImageFilter.GaussianBlur(radius=sigma))
    dy_img = dy_img.filter(ImageFilter.GaussianBlur(radius=sigma))

    dx = (np.array(dx_img, dtype=np.float32) / 127.5 - 1.0) * alpha
    dy = (np.array(dy_img, dtype=np.float32) / 127.5 - 1.0) * alpha

    x, y = np.meshgrid(np.arange(w), np.arange(h))
    src_x = np.clip(x + dx, 0, w - 1).astype(np.float32)
    src_y = np.clip(y + dy, 0, h - 1).astype(np.float32)

    # Nearest-neighbour remap (fast, avoids scipy map_coordinates)
    out = arr[src_y.astype(int), src_x.astype(int)]
    return Image.fromarray(out, "RGBA")


# ---------------------------------------------------------------------------
# Ink texture — vary stroke opacity for a pen-pressure feel
# ---------------------------------------------------------------------------
def apply_ink_texture(img: Image.Image, min_alpha: float = 0.78) -> Image.Image:
    """Slightly randomise per-pixel alpha on non-transparent pixels."""
    arr = np.array(img)
    mask = arr[:, :, 3] > 0
    # Create a smooth noise field
    noise = np.random.uniform(min_alpha, 1.0, arr.shape[:2]).astype(np.float32)
    noise_img = Image.fromarray((noise * 255).astype(np.uint8), "L")
    noise_img = noise_img.filter(ImageFilter.GaussianBlur(radius=2))
    noise = np.array(noise_img, dtype=np.float32) / 255.0
    arr[:, :, 3] = np.where(mask, np.clip(arr[:, :, 3].astype(np.float32) * noise, 0, 255).astype(np.uint8), 0)
    return Image.fromarray(arr, "RGBA")


# ---------------------------------------------------------------------------
# Stroke thickness variation — dilate or erode the alpha channel slightly
# ---------------------------------------------------------------------------
def vary_stroke_width(img: Image.Image, amount: int) -> Image.Image:
    """Dilate (amount>0) or erode (amount<0) the stroke by *amount* pixels."""
    if amount == 0:
        return img
    alpha = img.getchannel("A")
    if amount > 0:
        alpha = alpha.filter(ImageFilter.MaxFilter(size=3))
    else:
        alpha = alpha.filter(ImageFilter.MinFilter(size=3))
    img = img.copy()
    img.putalpha(alpha)
    return img


# ---------------------------------------------------------------------------
# Main generation loop — baseline-aligned rendering
# ---------------------------------------------------------------------------
font = ImageFont.truetype(FONT_PATH, BASE_SIZE)
base_ascent, base_descent = font.getmetrics()
random.seed(42)
np.random.seed(42)

# Fixed baseline position on the working canvas (350x350)
BIG = 350
FIXED_BASELINE = BIG // 2  # = 175

# Determine the vertical band to crop — covers all characters with margin
# Scan all characters at base size to find the extreme extents
max_above = 0  # pixels above baseline
max_below = 0  # pixels below baseline
tmp_img = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
tmp_draw = ImageDraw.Draw(tmp_img)
for cp in SUPPORTED:
    bbox = tmp_draw.textbbox((0, 0), chr(cp), font=font)
    above = base_ascent + (-bbox[1]) if bbox[1] < 0 else base_ascent - bbox[1]
    below = bbox[3] - base_ascent
    if above > max_above:
        max_above = above
    if below > max_below:
        max_below = below

MARGIN = 18  # extra space for rotation/warping
BAND_TOP = FIXED_BASELINE - max_above - MARGIN
BAND_BOTTOM = FIXED_BASELINE + max_below + MARGIN
CANVAS_HEIGHT = BAND_BOTTOM - BAND_TOP

print(f"Font metrics: ascent={base_ascent}, descent={base_descent}")
print(f"Max extent: above={max_above}, below={max_below}")
print(f"Canvas height: {CANVAS_HEIGHT}px, band [{BAND_TOP}, {BAND_BOTTOM}]")
print()

for codepoint in SUPPORTED:
    ch = chr(codepoint)

    for v in range(VARIANTS):
        # --- Perturbation parameters ---
        if v == 0:
            # Variant 0: clean baseline
            size_offset = 0
            rotation = 0.0
            x_shift = 0
            do_warp = False
            stroke_delta = 0
            do_ink_tex = False
        else:
            size_offset = random.randint(-1, 1)
            rotation = random.uniform(-2.0, 2.0)
            x_shift = random.randint(-1, 1)
            do_warp = random.random() < 0.75  # 75 % chance of elastic distortion
            stroke_delta = 0  # no thickness variation — keep uniform strokes
            do_ink_tex = random.random() < 0.6

        var_font = ImageFont.truetype(FONT_PATH, BASE_SIZE + size_offset)
        var_ascent, _ = var_font.getmetrics()

        # Render on large canvas with baseline at FIXED_BASELINE
        tmp = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
        draw = ImageDraw.Draw(tmp)

        bbox = draw.textbbox((0, 0), ch, font=var_font)
        text_w = bbox[2] - bbox[0]

        # Position: center horizontally, baseline-align vertically
        tx = (BIG - text_w) // 2 - bbox[0]
        ty = FIXED_BASELINE - var_ascent  # baseline at FIXED_BASELINE
        draw.text((tx, ty), ch, fill=(0, 0, 0, 255), font=var_font)

        # Rotation
        if rotation != 0:
            tmp = tmp.rotate(rotation, resample=Image.BICUBIC,
                             center=(BIG // 2, BIG // 2), expand=False)

        # Elastic distortion
        if do_warp:
            strength = random.uniform(5.0, 12.0)
            smoothness = random.uniform(2.5, 4.5)
            tmp = elastic_distort(tmp, alpha=strength, sigma=smoothness)

        # Stroke width variation
        if stroke_delta != 0:
            tmp = vary_stroke_width(tmp, stroke_delta)

        # Ink texture
        if do_ink_tex:
            tmp = apply_ink_texture(tmp, min_alpha=random.uniform(0.92, 0.98))

        # Crop to fixed vertical band (preserves baseline alignment)
        # First crop vertically to the band
        banded = tmp.crop((0, BAND_TOP, BIG, BAND_BOTTOM))

        # Then trim horizontal transparent space
        alpha = banded.getchannel("A")
        alpha_bbox = alpha.getbbox()
        if alpha_bbox is None:
            continue

        trimmed = banded.crop((alpha_bbox[0], 0, alpha_bbox[2], CANVAS_HEIGHT))

        if x_shift > 0:
            shifted = Image.new("RGBA", (trimmed.size[0] + x_shift, CANVAS_HEIGHT), (0, 0, 0, 0))
            shifted.paste(trimmed, (x_shift, 0))
            trimmed = shifted

        filename = f"u{codepoint:04X}_v{v}.png"
        trimmed.save(OUT_DIR / filename, "PNG", optimize=True)

    print(f"  {ch} (U+{codepoint:04X}) — {VARIANTS} variants")

print(f"\nDone! Generated {len(SUPPORTED) * VARIANTS} variant PNGs.")
