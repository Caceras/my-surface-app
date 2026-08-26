#!/usr/bin/env python3
"""Regenerate the README assets.

Assets are generated rather than checked in as opaque blobs so the whole set
can be re-themed from the tokens below. Change PRIMARY/ACCENT, or drop a
wordmark.png into assets/, and re-run; every image stays consistent.

    python tools/make_assets.py

Outputs into assets/:
    banner.png      wide README header
    social.png      GitHub social preview (1280x640)
    surfaces.png    diagram of the five surfaces
    pipeline.gif    animated scaffold -> verify -> CI -> phone walkthrough

The mockups are illustrations, not device screenshots. They are drawn from
shapes here, so they should never be presented as captures from a real phone.
"""

import argparse
import math
import os

from PIL import Image, ImageDraw, ImageFont

# --- Theme tokens ---------------------------------------------------------
# Change these to re-theme every asset consistently.
PRIMARY = "#152B3C"      # deep slate — backgrounds
ACCENT = "#38BDF8"       # sky — primary highlight
TEAL = "#2DD4BF"
SKY = "#7DD3FC"
ORANGE = "#FB923C"
YELLOW = "#FACC15"
BEIGE = "#F5F5F4"        # light canvas
WHITE = "#FFFFFF"
MID_GREY = "#6B7280"

# Optional wordmark. Drop a transparent PNG at this path to brand the assets;
# without it an eyebrow label is drawn instead.
WORDMARK = "wordmark.png"
EYEBROW = "ANDROID SYSTEM SURFACES"

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
FONTS = os.path.join(REPO, "assets", "fonts")
BRAND = os.path.join(REPO, "assets")
OUT = os.path.join(REPO, "assets")

SURFACES = [
    ("Quick Settings tile", "Shade -> Edit tiles", ACCENT),
    ("Home screen widget", "Long-press home -> Widgets", SKY),
    ("App shortcuts", "Long-press the app icon", TEAL),
    ("Share sheet target", "Share anything", ORANGE),
    ("Text selection action", "Select text -> overflow", YELLOW),
]


def font(name, size):
    path = os.path.join(FONTS, name)
    if os.path.exists(path):
        return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def medium(size):
    return font("Poppins-Medium.ttf", size)


def light(size):
    return font("Poppins-Light.ttf", size)


def regular(size):
    return font("Poppins-Regular.ttf", size)


def wordmark(height):
    path = os.path.join(BRAND, WORDMARK)
    if not os.path.exists(path):
        return None
    logo = Image.open(path).convert("RGBA")
    ratio = height / logo.height
    return logo.resize((int(logo.width * ratio), height), Image.LANCZOS)


def wave(draw, width, y, amplitude, period, color, phase=0.0):
    """Decorative wave band, drawn as a filled polygon."""
    points = [
        (x, y + amplitude * math.sin(2 * math.pi * (x / period) + phase))
        for x in range(0, width + 8, 8)
    ]
    points += [(width, y + 400), (0, y + 400)]
    draw.polygon(points, fill=color)


def rounded(draw, box, radius, fill, outline=None, width=2):
    draw.rounded_rectangle(box, radius=radius, fill=fill,
                           outline=outline, width=width)


# --------------------------------------------------------------------------

def build_banner(path, width=1280, height=420):
    img = Image.new("RGB", (width, height), PRIMARY)
    draw = ImageDraw.Draw(img, "RGBA")

    wave(draw, width, height - 150, 26, 620, (4, 149, 153, 90))
    wave(draw, width, height - 92, 20, 460, (0, 192, 255, 70), phase=1.7)

    logo = wordmark(38)
    if logo:
        img.paste(logo, (64, 54), logo)
    else:
        draw.text((64, 60), EYEBROW, font=medium(22), fill=ACCENT)

    draw.text((64, 132), "Pixel Surface Lab", font=medium(78), fill=WHITE)
    draw.text((64, 232),
              "Ship a real Android system surface to your Pixel in minutes.",
              font=light(30), fill=SKY)

    x = 64
    for label, _, colour in SURFACES:
        w = draw.textlength(label, font=regular(19)) + 34
        rounded(draw, (x, 300, x + w, 342), 21, None, outline=colour, width=2)
        draw.text((x + 17, 310), label, font=regular(19), fill=WHITE)
        x += w + 12

    img.save(path)
    return path


def build_social(path, width=1280, height=640):
    img = Image.new("RGB", (width, height), PRIMARY)
    draw = ImageDraw.Draw(img, "RGBA")

    wave(draw, width, height - 210, 34, 700, (4, 149, 153, 80))
    wave(draw, width, height - 140, 24, 520, (0, 192, 255, 65), phase=2.2)

    logo = wordmark(46)
    if logo:
        img.paste(logo, (80, 70), logo)
    else:
        draw.text((80, 78), EYEBROW, font=medium(26), fill=ACCENT)

    draw.text((80, 170), "Pixel", font=medium(112), fill=WHITE)
    draw.text((80, 288), "Surface Lab", font=medium(112), fill=ACCENT)
    draw.text((80, 436),
              "Scaffold, verify and sideload Android system surfaces",
              font=light(32), fill=SKY)
    draw.text((80, 480), "Quick Settings - Widgets - Shortcuts - Share - Text",
              font=regular(24), fill=(255, 255, 255, 170))

    img.save(path)
    return path


def phone_frame(draw, x, y, w, h, fill="#0E2A36"):
    rounded(draw, (x, y, x + w, y + h), 18, fill, outline=(255, 255, 255, 60), width=2)


def build_surfaces(path, width=1280, height=560):
    """Illustrative diagram of where each surface appears. Not a screenshot."""
    img = Image.new("RGB", (width, height), BEIGE)
    draw = ImageDraw.Draw(img, "RGBA")

    draw.text((56, 44), "Five surfaces, one install", font=medium(46), fill=PRIMARY)
    draw.text((58, 106),
              "Illustration of where each registered surface appears on a Pixel.",
              font=light(24), fill=MID_GREY)

    # Derived from the canvas so the row always fits flush, whatever the
    # surface count. Hardcoding the width clipped the final card.
    margin, gap, card_h = 56, 20, 300
    count = len(SURFACES)
    card_w = (width - 2 * margin - gap * (count - 1)) // count
    x = margin
    for label, hint, colour in SURFACES:
        y = 168
        rounded(draw, (x, y, x + card_w, y + card_h), 18, WHITE,
                outline="#E2DACE", width=2)
        draw.rectangle((x, y, x + card_w, y + 6), fill=colour)

        px = x + (card_w - 100) // 2
        phone_frame(draw, px, y + 32, 100, 158)
        # A few abstract rows standing in for system UI.
        for i in range(3):
            rounded(draw, (px + 12, y + 48 + i * 30, px + 88, y + 68 + i * 30),
                    6, (255, 255, 255, 40))
        rounded(draw, (px + 12, y + 48, px + 88, y + 68), 6, colour)

        draw.text((x + 18, y + 208), label, font=medium(21), fill=PRIMARY)
        words, line, lines = hint.split(), "", []
        for word in words:
            trial = (line + " " + word).strip()
            if draw.textlength(trial, font=regular(17)) > card_w - 36:
                lines.append(line)
                line = word
            else:
                line = trial
        lines.append(line)
        for i, ln in enumerate(lines[:2]):
            draw.text((x + 18, y + 240 + i * 22), ln, font=regular(17),
                      fill=MID_GREY)
        x += card_w + gap

    img.save(path)
    return path


def build_pipeline_gif(path, width=900, height=420):
    """Animated walkthrough of the four-step loop."""
    steps = [
        ("1  Scaffold", "python tools/scaffold.py --surface tile",
         "Writes a complete Gradle project", ACCENT),
        ("2  Verify", "python tools/verify.py .",
         "Catches broken refs before CI", TEAL),
        ("3  Push", "git push",
         "GitHub Actions builds the APK", SKY),
        ("4  Install", "Releases -> tap the .apk",
         "The tile appears on your Pixel", YELLOW),
    ]

    frames = []
    for idx, (title, cmd, note, colour) in enumerate(steps):
        img = Image.new("RGB", (width, height), PRIMARY)
        draw = ImageDraw.Draw(img, "RGBA")
        wave(draw, width, height - 96, 18, 480, (4, 149, 153, 80))

        logo = wordmark(26)
        if logo:
            img.paste(logo, (44, 36), logo)
        else:
            draw.text((44, 40), EYEBROW, font=medium(18), fill=ACCENT)

        draw.text((44, 104), title, font=medium(54), fill=WHITE)

        rounded(draw, (44, 190, width - 44, 262), 12, (0, 0, 0, 90),
                outline=colour, width=2)
        draw.text((66, 210), "$ " + cmd, font=regular(26), fill=colour)
        draw.text((46, 288), note, font=light(26), fill=SKY)

        # Progress dots.
        for i in range(len(steps)):
            cx = 46 + i * 30
            fill = colour if i <= idx else (255, 255, 255, 60)
            draw.ellipse((cx, 356, cx + 14, 370), fill=fill)

        frames.append(img)

    frames[0].save(path, save_all=True, append_images=frames[1:],
                   duration=1500, loop=0, optimize=True)
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    made = [
        build_banner(os.path.join(args.out, "banner.png")),
        build_social(os.path.join(args.out, "social.png")),
        build_surfaces(os.path.join(args.out, "surfaces.png")),
        build_pipeline_gif(os.path.join(args.out, "pipeline.gif")),
    ]
    for path in made:
        print(f"  {os.path.relpath(path, REPO)}")


if __name__ == "__main__":
    main()
