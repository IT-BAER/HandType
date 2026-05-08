"""Local renderer for the HandType practice sheet.

Mirrors the layout in app/src/main/java/com/baer/handtype/feature/capture/PracticeSheetGenerator.kt
so you can preview / print the PNG without round-tripping through the device.

Output: artifacts/handtype_practice_sheet.png
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

PAGE_WIDTH = 1240
PAGE_HEIGHT = 1754
OUTER_MARGIN = 60
FIDUCIAL_SIZE = 80
GRID_COLS = 8
GRID_ROWS = 8
LABEL_COLOR = (0xB7, 0xB0, 0xA3)
GRID_COLOR = (0xCC, 0xC4, 0xB4)
GUIDE_COLOR = (0xD8, 0xD1, 0xC3)
GUIDE_SEGMENT_LENGTH = 26
GUIDE_UPPER_RATIO = 0.32
GUIDE_BASELINE_RATIO = 0.78
GUIDE_MIDDLE_RATIO = (GUIDE_UPPER_RATIO + GUIDE_BASELINE_RATIO) / 2
INK_COLOR = (0x1A, 0x14, 0x10)


def cell_characters() -> list[str]:
    chars: list[str] = list("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    chars += list("abcdefghijklmnopqrstuvwxyz")
    chars += list("0123456789")
    chars += [" ", " "]
    return chars


def fiducial_centers():
    half = FIDUCIAL_SIZE / 2
    left = OUTER_MARGIN + half
    top = OUTER_MARGIN + half
    right = PAGE_WIDTH - OUTER_MARGIN - half
    bottom = PAGE_HEIGHT - OUTER_MARGIN - half
    return {
        "tl": (left, top),
        "tr": (right, top),
        "br": (right, bottom),
        "bl": (left, bottom),
    }


def normalized_cells():
    grid_inset = 0.06
    grid_left = grid_inset
    grid_top = grid_inset
    grid_right = 1.0 - grid_inset
    grid_bottom = 1.0 - grid_inset
    cell_w = (grid_right - grid_left) / GRID_COLS
    cell_h = (grid_bottom - grid_top) / GRID_ROWS

    cells = []
    for index, char in enumerate(cell_characters()):
        col = index % GRID_COLS
        row = index // GRID_COLS
        left = grid_left + cell_w * col
        top = grid_top + cell_h * row
        cells.append((char, left, top, left + cell_w, top + cell_h))
    return cells


def load_font(size: int, bold: bool = False):
    candidates = [
        r"C:\Windows\Fonts\arialbd.ttf" if bold else r"C:\Windows\Fonts\arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
        if bold
        else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for path in candidates:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def render() -> Image.Image:
    img = Image.new("RGB", (PAGE_WIDTH, PAGE_HEIGHT), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)

    title_font = load_font(36, bold=True)
    body_font = load_font(22)
    label_font = load_font(28)

    draw.text((OUTER_MARGIN, OUTER_MARGIN - 50), "HandType practice sheet", fill=INK_COLOR, font=title_font)
    draw.text(
        (OUTER_MARGIN, PAGE_HEIGHT - OUTER_MARGIN + 10),
        "Write each character inside its cell. Keep the four black squares visible.",
        fill=INK_COLOR,
        font=body_font,
    )

    centres = fiducial_centers()
    half = FIDUCIAL_SIZE / 2
    for cx, cy in centres.values():
        draw.rectangle((cx - half, cy - half, cx + half, cy + half), fill=(0, 0, 0))

    grid_left_px, grid_top_px = centres["tl"]
    grid_right_px, _ = centres["tr"]
    _, grid_bottom_px = centres["bl"]
    grid_w = grid_right_px - grid_left_px
    grid_h = grid_bottom_px - grid_top_px

    for char, left, top, right, bottom in normalized_cells():
        rect = (
            grid_left_px + left * grid_w,
            grid_top_px + top * grid_h,
            grid_left_px + right * grid_w,
            grid_top_px + bottom * grid_h,
        )
        draw.rectangle(rect, outline=GRID_COLOR, width=2)
        draw_guide_ticks(draw, rect)
        if char != " ":
            draw.text((rect[0] + 8, rect[1] + 4), char, fill=LABEL_COLOR, font=label_font)

    return img


def draw_guide_ticks(draw: ImageDraw.ImageDraw, rect):
    left, top, right, bottom = rect
    height = bottom - top
    upper_guide_y = top + height * GUIDE_UPPER_RATIO
    middle_guide_y = top + height * GUIDE_MIDDLE_RATIO
    baseline_y = top + height * GUIDE_BASELINE_RATIO
    center_x = (left + right) / 2
    half_segment = GUIDE_SEGMENT_LENGTH / 2

    draw.line((center_x - half_segment, upper_guide_y, center_x + half_segment, upper_guide_y), fill=GUIDE_COLOR, width=2)
    draw.line((center_x - half_segment, middle_guide_y, center_x + half_segment, middle_guide_y), fill=GUIDE_COLOR, width=2)
    draw.line((center_x - half_segment, baseline_y, center_x + half_segment, baseline_y), fill=GUIDE_COLOR, width=2)


def main():
    out_dir = Path(__file__).resolve().parent / "artifacts"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "handtype_practice_sheet.png"
    pdf_path = out_dir / "handtype_practice_sheet.pdf"
    img = render()
    img.save(out_path, "PNG", optimize=True, dpi=(300, 300))
    img.save(pdf_path, "PDF", resolution=300.0)
    print(f"Wrote {out_path} ({img.size[0]}x{img.size[1]})")
    print(f"Wrote {pdf_path}")


if __name__ == "__main__":
    main()
