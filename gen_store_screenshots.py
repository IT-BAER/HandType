"""Build designed Play Store phone screenshots from raw device captures.

Input:  artifacts/store/v3/raw/<locale>/<screen>.png   (1080x2340 portrait captures)
        artifacts/store/v3/notes/<locale>/<paper>.png  (full-size renders pulled from the app's history)
        app/src/androidTest/assets/practice_sheet_scanner_output.png (a real filled practice sheet)
Output: artifacts/store/v3/<locale>/<n>-<slot>.png       (1440x2560, 9:16, 24-bit PNG)

Play rules applied: no device imagery (screens are shown as plain rounded cards), taglines stay
inside the top CAPTION_MAX of the image (Google allows at most 20%), no ranking/price/CTA wording.
The accent line of each caption is set in the app's own Ink Script glyphs.
"""
import sys
import zlib
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parent
V3 = REPO / "artifacts" / "store" / "v3"
GLYPHS = REPO / "app" / "src" / "main" / "assets" / "templates" / "classic_script" / "glyphs"
SHEET = REPO / "app" / "src" / "androidTest" / "assets" / "practice_sheet_scanner_output.png"
FONTS = "C:/Windows/Fonts/"

W, H = 1440, 2560
CAPTION_MAX = 0.19
STATUS_BAR, NAV_BAR = 100, 135  # px cropped from 2340-tall captures

GREEN_DEEP = (31, 52, 43)
GREEN = (46, 107, 79)
GOLD = (228, 183, 90)
CREAM = (250, 244, 232)
INK = (30, 34, 32)
MUTED_DARK = (92, 86, 76)
MUTED_LIGHT = (214, 205, 188)

# slot: background top/bottom, light text?, composition
SLOTS = [
    ("hero", (38, 64, 52), (22, 38, 31), True),
    ("own_hand", (226, 234, 223), (205, 219, 203), False),
    ("papers", (238, 226, 205), (222, 203, 172), False),
    ("styles", (247, 240, 228), (234, 222, 200), False),
    ("export", (245, 228, 226), (232, 205, 202), False),
    ("history", (248, 240, 208), (236, 222, 170), False),
    ("dark", (40, 36, 31), (18, 16, 14), True),
    ("private", (38, 64, 52), (22, 38, 31), True),
]

# slot -> (serif line, handwritten accent line [Ink Script charset: A-Z a-z 0-9 . , ! ? ' -], subline)
CAPTIONS = {
    "en-US": {
        "hero": ("Type it. Get it", "handwritten", "Realistic handwriting from any text"),
        "own_hand": ("Write in", "your own hand", "Scan one practice sheet  \u00b7  Premium"),
        "papers": ("11 papers", "to write on", "Ruled, graph, kraft, parchment and more"),
        "styles": ("Choose your", "handwriting", "Ink Script, Flowing Cursive or your own"),
        "export": ("Save, share", "or print", "PNG, transparent PNG and A4 PDF"),
        "history": ("Every note", "kept for you", "A private history on your phone"),
        "dark": ("Looks good", "day and night", "Light and dark theme"),
        "private": ("Private", "by design", "Works offline  \u00b7  No account  \u00b7  No ads  \u00b7  10 languages"),
    },
    "de-DE": {
        "hero": ("Tippen. Und schon", "handgeschrieben", "Realistische Handschrift aus jedem Text"),
        "own_hand": ("Schreib in deiner", "eigenen Schrift", "Ein \u00dcbungsblatt scannen  \u00b7  Premium"),
        "papers": ("11 Papiere", "zum Schreiben", "Liniert, kariert, Kraftpapier, Pergament und mehr"),
        "styles": ("W\u00e4hle deine", "Handschrift", "Tintenschrift, Kursivschrift oder deine eigene"),
        "export": ("Speichern, teilen", "oder drucken", "PNG, transparentes PNG und A4-PDF"),
        "history": ("Jede Notiz", "gut aufgehoben", "Privater Verlauf auf deinem Handy"),
        "dark": ("Sieht gut aus", "Tag und Nacht", "Helles und dunkles Design"),
        "private": ("Privat", "von Grund auf", "Offline  \u00b7  Kein Konto  \u00b7  Keine Werbung  \u00b7  10 Sprachen"),
    },
}


def serif(size):
    f = ImageFont.truetype(FONTS + "SitkaVF.ttf", size)
    f.set_variation_by_name("Display Semibold")
    return f


def sans(size):
    return ImageFont.truetype(FONTS + "segoeui.ttf", size)


def background(top, bottom):
    grad = Image.linear_gradient("L").resize((W, H))
    img = Image.composite(Image.new("RGB", (W, H), bottom), Image.new("RGB", (W, H), top), grad)
    # soft light from the upper centre, then a faint paper grain
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).ellipse([-W // 3, -H // 4, W + W // 3, H // 2], fill=60)
    img = Image.composite(Image.new("RGB", (W, H), tuple(min(255, c + 18) for c in top)), img,
                          glow.filter(ImageFilter.GaussianBlur(220)))
    grain = Image.effect_noise((W // 2, H // 2), 14).resize((W, H)).point(lambda v: 128 + (v - 128) // 6)
    return ImageChops.overlay(img, Image.merge("RGB", [grain] * 3))


def handwriting(text, height, color):
    """Set text in the bundled Ink Script glyphs (199 px tall, baseline-aligned canvases)."""
    seed = zlib.crc32(text.encode())
    tiles, space = [], 46
    for i, ch in enumerate(text):
        if ch == " ":
            tiles.append(None)
            continue
        v = (seed + i * 5) % 8
        g = Image.open(GLYPHS / f"u{ord(ch):04X}_v{v}.png").convert("RGBA")
        alpha = g.getchannel("A")
        x0, _, x1, _ = alpha.getbbox()
        tiles.append(alpha.crop((x0, 0, x1, g.height)))
    width = sum(space if t is None else t.width - 6 for t in tiles) + 12
    mask = Image.new("L", (width, 199), 0)
    x = 0
    for t in tiles:
        if t is None:
            x += space
            continue
        mask = ImageChops.lighter(mask, _place(t, x, mask.size))
        x += t.width - 6
    mask = mask.filter(ImageFilter.MaxFilter(3))
    scale = height / 199
    mask = mask.resize((round(width * scale), height), Image.LANCZOS)
    out = Image.new("RGBA", mask.size, color + (0,))
    out.putalpha(mask)
    return out


def _place(tile, x, size):
    layer = Image.new("L", size, 0)
    layer.paste(tile, (x, 0))
    return layer


def fit(draw, text, make, size, max_w):
    while draw.textlength(text, font=make(size)) > max_w and size > 24:
        size -= 2
    return make(size)


def caption(img, locale, slot, light):
    line1, accent, sub = CAPTIONS[locale][slot]
    d = ImageDraw.Draw(img)
    max_w = W - 2 * 96
    head = (CREAM if light else INK)
    f1 = fit(d, line1, serif, 112, max_w)
    d.text((W // 2, 104), line1, font=f1, fill=head, anchor="ma")
    hw = handwriting(accent, 168, GOLD if light else GREEN)
    if hw.width > max_w:
        hw = hw.resize((max_w, round(hw.height * max_w / hw.width)), Image.LANCZOS)
    img.paste(hw, ((W - hw.width) // 2, 222), hw)
    fs = fit(d, sub, sans, 46, max_w)
    d.text((W // 2, 408), sub, font=fs, fill=MUTED_LIGHT if light else MUTED_DARK, anchor="ma")
    bottom = 408 + fs.size + 14
    assert bottom <= H * CAPTION_MAX, f"{locale}/{slot}: caption ends at {bottom}px"
    return bottom


def rounded(im, radius, outline=None, width=3):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=radius, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im.convert("RGB"), (0, 0), mask)
    if outline:
        ImageDraw.Draw(out).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=radius,
                                              outline=outline, width=width)
    return out


def screen(locale, name, width, max_h=None, dark=False):
    im = Image.open(V3 / "raw" / locale / f"{name}.png").convert("RGB")
    im = im.crop((0, STATUS_BAR, im.width, im.height - NAV_BAR))
    im = im.resize((width, round(width * im.height / im.width)), Image.LANCZOS)
    if max_h and im.height > max_h:
        im = im.crop((0, 0, width, max_h))
    edge = (70, 66, 60, 255) if dark else (255, 255, 255, 140)
    return rounded(im, width // 16, outline=edge, width=3)


def paper(path, width, crop_h=None):
    im = Image.open(path).convert("RGB")
    im = im.resize((width, round(width * im.height / im.width)), Image.LANCZOS)
    if crop_h and im.height > crop_h:
        im = im.crop((0, 0, width, crop_h))
    return rounded(im, 14)


def drop(img, tile, xy, angle=0, strength=90, blur=34, offset=(14, 30)):
    if angle:
        tile = tile.rotate(angle, resample=Image.BICUBIC, expand=True)
    shadow = Image.new("L", (W, H), 0)
    shadow.paste(tile.getchannel("A").point(lambda v: v * strength // 255), (xy[0] + offset[0], xy[1] + offset[1]))
    img.paste(Image.new("RGB", (W, H), (20, 14, 6)), (0, 0), shadow.filter(ImageFilter.GaussianBlur(blur)))
    img.paste(tile, xy, tile)


def compose(img, locale, slot, top):
    y = top + 70
    notes = V3 / "notes" / locale
    if slot == "hero":
        back = screen(locale, "editor", 860, max_h=1500)
        front = screen(locale, "result", 900)
        drop(img, back, (40, y + 20), angle=4, strength=120)
        drop(img, front, (W - front.width - 30, y + 210), angle=-3, strength=140)
    elif slot == "own_hand":
        sheet = Image.open(SHEET).convert("RGB")
        sheet = sheet.crop((0, 0, sheet.width, round(sheet.width * 0.78)))
        sheet = rounded(sheet.resize((760, round(760 * sheet.height / sheet.width)), Image.LANCZOS), 10)
        drop(img, sheet, (36, y + 30), angle=6)
        front = screen(locale, "result_own", 900)
        drop(img, front, (W - front.width - 40, y + 300), angle=-2, strength=120)
    elif slot == "papers":
        for name, x, dy, ang in (("kraft", 880, 30, -7), ("graph", 900, 560, 5), ("rose", 870, 1090, -4)):
            drop(img, paper(notes / f"{name}.png", 520, crop_h=600), (x, y + dy), angle=ang)
        front = screen(locale, "papers", 860)
        drop(img, front, (40, y + 40), angle=2, strength=130)
    elif slot == "styles":
        front = screen(locale, "home", 1000)
        drop(img, front, ((W - front.width) // 2, y + 20))
    elif slot == "export":
        drop(img, paper(notes / "hero.png", 600, crop_h=980), (30, y + 260), angle=-6)
        front = screen(locale, "export", 900)
        drop(img, front, (W - front.width - 40, y + 20), strength=130)
    elif slot == "history":
        front = screen(locale, "history", 1000)
        drop(img, front, ((W - front.width) // 2, y + 20))
    elif slot == "dark":
        back = screen(locale, "home_dark", 860, max_h=1500, dark=True)
        front = screen(locale, "history_dark", 900, dark=True)
        drop(img, back, (40, y + 20), angle=4, strength=200)
        drop(img, front, (W - front.width - 30, y + 210), angle=-3, strength=220)
    elif slot == "private":
        back = screen(locale, "settings", 820, max_h=1500)
        front = screen(locale, "capture", 900)
        drop(img, back, (40, y + 20), angle=4, strength=120)
        drop(img, front, (W - front.width - 30, y + 210), angle=-3, strength=140)


def render(locale, n, slot, top_color, bottom_color, light):
    img = background(top_color, bottom_color)
    compose(img, locale, slot, round(H * CAPTION_MAX))
    caption(img, locale, slot, light)
    out = V3 / locale
    out.mkdir(parents=True, exist_ok=True)
    dst = out / f"{n}-{slot}.png"
    img.convert("RGB").save(dst, optimize=True)
    return dst


def main(locales):
    done = 0
    for locale in locales:
        for n, (slot, top_color, bottom_color, light) in enumerate(SLOTS, start=1):
            print(render(locale, n, slot, top_color, bottom_color, light).relative_to(REPO))
            done += 1
    print(f"wrote {done} screenshots")


if __name__ == "__main__":
    main(sys.argv[1:] or list(CAPTIONS))
