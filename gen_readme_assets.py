"""Build docs/images (README banner + screenshots) from the store captures in artifacts/store."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parent
STORE = REPO / "artifacts" / "store"
OUT = REPO / "docs" / "images"
OUT.mkdir(parents=True, exist_ok=True)

S = 2  # render scale; banner is 1024x500 logical, 2048x1000 output
W, H = 1024 * S, 500 * S
FONTS = "C:/Windows/Fonts/"


def f(name, size):
    return ImageFont.truetype(FONTS + name, size * S)


shot = Image.open(STORE / "s1_home.png").convert("RGB")
GREEN = shot.getpixel((300, 1389))
INK = (30, 34, 32)
MUTED = (96, 92, 84)
photo = Image.open(STORE / "feature_1024x500_v2.png").convert("RGB")
BG = photo.getpixel((700, 40))

# Background: warm paper with light grain.
img = Image.new("RGB", (W, H), BG)
grain = Image.effect_noise((W // 2, H // 2), 18).resize((W, H)).convert("L")
img = Image.composite(img, Image.new("RGB", (W, H), (236, 224, 198)), grain.point(lambda v: 255 - v // 12))

# Right side: card + pen photo from the store feature graphic, faded on its left edge.
crop = photo.crop((0, 0, 510, 500)).resize((510 * S, 500 * S), Image.LANCZOS)
mask = Image.new("L", crop.size, 255)
md = ImageDraw.Draw(mask)
fade = 130 * S
for x in range(fade):
    md.line([(x, 0), (x, crop.height)], fill=int(255 * (x / fade) ** 1.6))
img.paste(crop, (W - crop.width, 0), mask)

d = ImageDraw.Draw(img)

# App icon tile.
icon = Image.open(STORE / "icon_512.png").convert("RGBA").resize((84 * S, 84 * S), Image.LANCZOS)
im = Image.new("L", icon.size, 0)
ImageDraw.Draw(im).rounded_rectangle([0, 0, *icon.size], radius=20 * S, fill=255)
shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(shadow).rounded_rectangle([72 * S, 78 * S, 156 * S, 162 * S], radius=20 * S, fill=(60, 40, 10, 70))
img.paste(Image.new("RGB", (W, H), (0, 0, 0)), (0, 0), shadow.filter(ImageFilter.GaussianBlur(8 * S)).split()[3].point(lambda v: v // 3))
img.paste(icon, (72 * S, 72 * S), im)

# Wordmark.
x0 = 180 * S
letters, x = "HANDTYPE", x0
for ch in letters:
    d.text((x, 92 * S), ch, font=f("seguisb.ttf", 24), fill=INK)
    x += d.textlength(ch, font=f("seguisb.ttf", 24)) + 5 * S
d.text((x0, 126 * S), "text to handwriting", font=f("segoeui.ttf", 14), fill=MUTED)

# Headline and subline.
d.text((72 * S, 188 * S), "Typed text,", font=f("segoeui.ttf", 50), fill=INK)
d.text((72 * S, 248 * S), "real handwriting", font=f("segoeui.ttf", 50), fill=GREEN)
d.text((74 * S, 324 * S), "Render notes on your phone. Export PNG or PDF.", font=f("segoeui.ttf", 19), fill=MUTED)
d.text((74 * S, 350 * S), "Your text never leaves the device.", font=f("segoeui.ttf", 19), fill=MUTED)

# Chips: Segoe Fluent Icons glyph + label.
chips = [("\uE73E", "offline"), ("\uE72E", "no tracking"), ("\uE943", "open-source")]
cx, cy, ch_h = 72 * S, 400 * S, 38 * S
for glyph, label in chips:
    tw = d.textlength(label, font=f("segoeui.ttf", 17))
    w = int(tw + 58 * S)
    d.rounded_rectangle([cx, cy, cx + w, cy + ch_h], radius=ch_h // 2, fill=(255, 251, 242), outline=(200, 186, 160), width=S)
    d.text((cx + 16 * S, cy + 11 * S), glyph, font=f("SegoeIcons.ttf", 15), fill=GREEN)
    d.text((cx + 40 * S, cy + 7 * S), label, font=f("segoeui.ttf", 17), fill=INK)
    cx += w + 12 * S

img.save(OUT / "banner.png", optimize=True)
img.resize((1024, 500), Image.LANCZOS).save(STORE / "feature_1024x500_v3.png", optimize=True)

# Screenshots: drop status and nav bar, round corners, half size.
for src, name in [("s1_home.png", "01-home.png"), ("s2_editor.png", "02-editor.png"),
                  ("s3_output.png", "03-output.png"), ("s4_drawer.png", "04-menu.png")]:
    im = Image.open(STORE / src).convert("RGB").crop((0, 100, 1080, 2205))
    im = im.resize((540, round(540 * im.height / im.width)), Image.LANCZOS)
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=28, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), m)
    ImageDraw.Draw(out).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=28, outline=(214, 204, 186, 255), width=2)
    out.save(OUT / name, optimize=True)
print("ok")
