"""Build captioned Play Store phone screenshots from raw device captures.

Input:  artifacts/store/raw/<locale>/<slot>.png  (1080x2340 portrait captures, status + nav bar included)
Output: artifacts/store/v2/<locale>/<n>-<slot>.png  (1080x1920, Play phone screenshot, 9:16)
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parent
RAW = REPO / "artifacts" / "store" / "raw"
OUT = REPO / "artifacts" / "store" / "v2"
FONTS = "C:/Windows/Fonts/"

W, H = 1080, 1920
BG = (248, 241, 228)        # warm_cream paper preset 0xFFF8F1E4
BG_GRAIN = (236, 224, 198)
INK = (30, 34, 32)
MUTED = (96, 92, 84)
GREEN = (46, 107, 79)
FRAME = (214, 204, 186)
STATUS_BAR, NAV_BAR = 100, 135  # px cropped from 2340-tall captures

# slot -> raw capture file(s); "before_after" combines the editor and the result.
SLOTS = [
    ("before_after", ["editor", "result"]),
    ("styles", ["home"]),
    ("papers", ["papers"]),
    ("capture", ["capture"]),
    ("export", ["export"]),
    ("private", ["history"]),
]

CAPTIONS = {
    "en-US": {
        "before_after": ("Type it.", "Get it handwritten.", "Typed text becomes a realistic handwritten note"),
        "styles": ("Handwriting", "styles", "Ink Script, Flowing Cursive, or your own"),
        "papers": ("11 papers", "to write on", "Cream, kraft, ruled, graph and more"),
        "capture": ("Your own", "handwriting", "Scan a practice sheet, write in your hand"),
        "export": ("Save, share", "or print", "PNG, transparent PNG and A4 PDF"),
        "private": ("Offline and", "private", "No account, no ads, no upload"),
    },
    "de-DE": {
        "before_after": ("Tippen.", "Handschrift erhalten.", "Getippter Text wird zur echten Handschrift"),
        "styles": ("Handschrift-", "Stile", "Tintenschrift, Kursivschrift oder deine eigene"),
        "papers": ("11 Papiere", "zur Auswahl", "Creme, Kraft, liniert, kariert und mehr"),
        "capture": ("Deine eigene", "Handschrift", "Übungsblatt scannen, in deiner Schrift schreiben"),
        "export": ("Speichern, teilen", "oder drucken", "PNG, transparentes PNG und A4-PDF"),
        "private": ("Offline und", "privat", "Kein Konto, keine Werbung, kein Upload"),
    },
}


def font(name, size):
    return ImageFont.truetype(FONTS + name, size)


def background():
    img = Image.new("RGB", (W, H), BG)
    grain = Image.effect_noise((W // 2, H // 2), 18).resize((W, H)).convert("L")
    return Image.composite(img, Image.new("RGB", (W, H), BG_GRAIN), grain.point(lambda v: 255 - v // 12))


def phone(path, width):
    """Crop status and nav bar, scale to width, round corners, add a light frame."""
    im = Image.open(path).convert("RGB")
    im = im.crop((0, STATUS_BAR, im.width, im.height - NAV_BAR))
    im = im.resize((width, round(width * im.height / im.width)), Image.LANCZOS)
    r = width // 18
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=r, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    ImageDraw.Draw(out).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=r, outline=FRAME + (255,), width=3)
    return out


def paste_with_shadow(img, tile, xy, angle=0):
    if angle:
        tile = tile.rotate(angle, resample=Image.BICUBIC, expand=True)
    shadow = Image.new("L", (W, H), 0)
    shadow.paste(tile.split()[3].point(lambda v: v // 3), (xy[0] + 10, xy[1] + 18))
    img.paste(Image.new("RGB", (W, H), (60, 40, 10)), (0, 0), shadow.filter(ImageFilter.GaussianBlur(22)))
    img.paste(tile, xy, tile)


def fit_text(d, text, name, size, max_w):
    while d.textlength(text, font=font(name, size)) > max_w and size > 20:
        size -= 2
    return font(name, size)


def render(locale, slot, files, n):
    src = RAW / locale
    paths = [src / f"{f}.png" for f in files]
    missing = [p for p in paths if not p.exists()]
    if missing:
        print(f"skip {locale}/{slot}: missing {', '.join(p.name for p in missing)}")
        return None
    line1, line2, sub = CAPTIONS[locale][slot]
    img = background()
    d = ImageDraw.Draw(img)
    max_w = W - 2 * 72
    f1 = fit_text(d, line1, "seguisb.ttf", 92, max_w)
    f2 = fit_text(d, line2, "seguisb.ttf", 92, max_w)
    d.text((W // 2, 120), line1, font=f1, fill=INK, anchor="mt")
    d.text((W // 2, 228), line2, font=f2, fill=GREEN, anchor="mt")
    d.text((W // 2, 352), sub, font=fit_text(d, sub, "segoeui.ttf", 40, max_w), fill=MUTED, anchor="mt")
    top = 450
    if len(paths) == 2:
        a, b = phone(paths[0], 560), phone(paths[1], 560)
        paste_with_shadow(img, a, (40, top + 40), angle=4)
        paste_with_shadow(img, b, (W - b.width - 40, top + 150), angle=-3)
    else:
        p = phone(paths[0], 760)
        paste_with_shadow(img, p, ((W - p.width) // 2, top))
    out = OUT / locale
    out.mkdir(parents=True, exist_ok=True)
    dst = out / f"{n}-{slot}.png"
    img.save(dst, optimize=True)
    return dst


def main(locales):
    done = 0
    for locale in locales:
        for n, (slot, files) in enumerate(SLOTS, start=1):
            if render(locale, slot, files, n):
                done += 1
    print(f"wrote {done} screenshots")


if __name__ == "__main__":
    main(sys.argv[1:] or list(CAPTIONS))
