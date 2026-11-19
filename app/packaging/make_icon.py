#!/usr/bin/env python3
"""Draws the application icon and writes every size the app and the installers need.

Outputs (paths relative to app/):
  src/main/resources/ui/icon-<size>.png   window / taskbar icons loaded by JavaFX
  packaging/icon.png                      Linux installers (jpackage --icon)
  packaging/icon.ico                      Windows installers
  packaging/icon.icns                     macOS installers

Usage: python3 app/packaging/make_icon.py   (needs Pillow)
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

APP = Path(__file__).resolve().parent.parent
SIZE = 1024
SCALE = 4  # draw at 4x and shrink, for smooth edges
WINDOW_SIZES = (16, 24, 32, 48, 64, 128, 256)

NAVY = (17, 45, 78)
TEAL = (12, 122, 110)
PAPER = (246, 242, 230)
PAPER_SHADE = (224, 218, 200)
GREEN = (176, 214, 160)
WATER = (122, 183, 222)
ORANGE = (255, 122, 26)
WHITE = (255, 255, 255)


def s(*values):
    """Scale design coordinates (1024 grid) to the drawing canvas."""
    return [v * SCALE for v in values]


def gradient(size, top, bottom):
    column = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / (size - 1)
        column.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return column.resize((size, size))


def draw_icon():
    n = SIZE * SCALE
    canvas = Image.new("RGBA", (n, n), (0, 0, 0, 0))

    # Rounded-square background with a navy-to-teal gradient.
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle(s(40, 40, 984, 984), radius=220 * SCALE, fill=255)
    canvas.paste(gradient(n, NAVY, TEAL), (0, 0), mask)

    # Folded map: three panels whose tops zig-zag like a paper map.
    tops = [(190, 330), (402, 280), (622, 330), (834, 280)]
    height = 470
    shadow = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    outline = [*tops, *[(x, y + height) for x, y in reversed(tops)]]
    ImageDraw.Draw(shadow).polygon([tuple(s(x + 10, y + 22)) for x, y in outline], fill=(0, 0, 0, 90))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14 * SCALE)))

    draw = ImageDraw.Draw(canvas)
    for i in range(3):
        (x1, y1), (x2, y2) = tops[i], tops[i + 1]
        panel = [tuple(s(x1, y1)), tuple(s(x2, y2)), tuple(s(x2, y2 + height)), tuple(s(x1, y1 + height))]
        draw.polygon(panel, fill=PAPER if i != 1 else PAPER_SHADE)

    # Map content, clipped to the paper: a park, a river and the route.
    content = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    c = ImageDraw.Draw(content)
    c.ellipse(s(230, 540, 420, 700), fill=GREEN)
    c.ellipse(s(560, 380, 720, 470), fill=GREEN)
    c.line([tuple(s(*p)) for p in [(190, 470), (300, 430), (420, 470), (560, 600), (700, 640), (834, 610)]],
           fill=WATER, width=26 * SCALE, joint="curve")
    route = [(250, 700), (340, 610), (450, 650), (560, 520), (700, 556)]  # ends at the pin tip
    c.line([tuple(s(*p)) for p in route], fill=ORANGE, width=36 * SCALE, joint="curve")
    for x, y in (route[0], route[-1]):
        c.ellipse(s(x - 18, y - 18, x + 18, y + 18), fill=ORANGE)
    c.ellipse(s(232, 682, 268, 718), fill=WHITE)  # start point
    paper = Image.new("L", (n, n), 0)
    ImageDraw.Draw(paper).polygon([tuple(s(*p)) for p in outline], fill=255)
    canvas.paste(content, (0, 0), Image.composite(content.getchannel("A"), Image.new("L", (n, n), 0), paper))

    # Location pin at the end of the route.
    pin = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    p = ImageDraw.Draw(pin)
    cx, cy, r, tip = 700, 380, 110, 560
    p.polygon([tuple(s(cx - r * 0.82, cy + r * 0.55)), tuple(s(cx + r * 0.82, cy + r * 0.55)), tuple(s(cx, tip))],
              fill=ORANGE)
    p.ellipse(s(cx - r, cy - r, cx + r, cy + r), fill=ORANGE)
    p.ellipse(s(cx - 44, cy - 44, cx + 44, cy + 44), fill=WHITE)
    pin_shadow = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    pin_shadow.paste((0, 0, 0, 110), (0, 0), pin.getchannel("A"))
    pin_shadow = pin_shadow.filter(ImageFilter.GaussianBlur(10 * SCALE))
    canvas.alpha_composite(pin_shadow, (6 * SCALE, 14 * SCALE))
    canvas.alpha_composite(pin)

    return canvas.resize((SIZE, SIZE), Image.LANCZOS)


def main():
    icon = draw_icon()
    resources = APP / "src/main/resources/ui"
    packaging = APP / "packaging"
    resources.mkdir(parents=True, exist_ok=True)
    for size in WINDOW_SIZES:
        icon.resize((size, size), Image.LANCZOS).save(resources / f"icon-{size}.png", optimize=True)
    icon.resize((512, 512), Image.LANCZOS).save(packaging / "icon.png", optimize=True)
    icon.save(packaging / "icon.ico", sizes=[(v, v) for v in (16, 24, 32, 48, 64, 128, 256)])
    icon.save(packaging / "icon.icns")
    print("Icon files written to", resources, "and", packaging)


if __name__ == "__main__":
    main()
