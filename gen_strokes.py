#!/usr/bin/env python3
"""
Generate character outline data from Ink Free font using fontTools.

Extracts the actual bezier control points from each glyph, storing them
as compact JSON. At runtime, the Android app perturbs these control points
to create unique letterforms for every character instance.

Key advantage over skeletonization: preserves exact character shapes
with proper bezier curves for smooth, high-quality rendering.
"""

import json
import os
import sys

from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont

# ─── Configuration ───────────────────────────────────────────────
FONT_PATH = r"C:\Windows\Fonts\Inkfree.ttf"
OUTPUT_DIR = os.path.join("app", "src", "main", "assets", "strokes")

# Characters to generate outlines for
CHARS = (
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    "0123456789.,!?;:'\"-()@#&+=$"
)


def extract_glyph_outlines(font_path: str, chars: str):
    """
    Extract bezier outline data for each character from the font.
    Returns a dict mapping char → { contours, metrics }.
    Each contour is a list of path commands: [type, ...points]
    """
    font = TTFont(font_path)
    cmap = font.getBestCmap()
    glyf = font.get("glyf")
    hmtx = font.get("hmtx")
    head = font.get("head")
    upem = head.unitsPerEm if head else 1000
    glyphset = font.getGlyphSet()

    all_data = {}

    for ch in chars:
        code = ord(ch)
        glyph_name = cmap.get(code)
        if not glyph_name:
            print(f"  Skipping '{ch}' — not in font cmap")
            continue

        # Record the glyph drawing commands
        pen = RecordingPen()
        try:
            glyphset[glyph_name].draw(pen)
        except Exception as e:
            print(f"  Error drawing '{ch}': {e}")
            continue

        # Get advance width
        advance = hmtx[glyph_name][0] if hmtx and glyph_name in hmtx.metrics else upem

        # Convert drawing commands to our format
        contours = []
        current_contour = []

        for op, args in pen.value:
            if op == "moveTo":
                if current_contour:
                    contours.append(current_contour)
                current_contour = [["M", args[0][0] / upem, args[0][1] / upem]]
            elif op == "lineTo":
                current_contour.append(["L", args[0][0] / upem, args[0][1] / upem])
            elif op == "qCurveTo":
                # Quadratic bezier — store all control + end points
                cmd = ["Q"]
                for pt in args:
                    cmd.extend([pt[0] / upem, pt[1] / upem])
                current_contour.append(cmd)
            elif op == "curveTo":
                # Cubic bezier
                cmd = ["C"]
                for pt in args:
                    cmd.extend([pt[0] / upem, pt[1] / upem])
                current_contour.append(cmd)
            elif op == "closePath" or op == "endPath":
                if current_contour:
                    current_contour.append(["Z"])
                    contours.append(current_contour)
                    current_contour = []

        if current_contour:
            contours.append(current_contour)

        char_key = ch if ch not in ('"', "\\") else f"U+{ord(ch):04X}"
        all_data[char_key] = {
            "advance": round(advance / upem, 4),
            "contours": contours,
        }
        total_pts = sum(len(c) for c in contours)
        print(f"  '{ch}' → {len(contours)} contours, {total_pts} segments, advance={advance/upem:.3f}")

    font.close()
    return all_data


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print(f"Extracting glyph outlines from: {FONT_PATH}")
    data = extract_glyph_outlines(FONT_PATH, CHARS)

    output_file = os.path.join(OUTPUT_DIR, "stroke_data.json")
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))

    file_size = os.path.getsize(output_file) / 1024
    print(f"\n✓ Generated outline data for {len(data)} characters")
    print(f"  {output_file} ({file_size:.1f} KB)")


if __name__ == "__main__":
    main()
