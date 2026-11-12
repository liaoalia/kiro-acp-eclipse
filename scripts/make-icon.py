"""Draws the view icon as PNG (Eclipse 4.32 cannot use SVG icons).

The shapes are those of media/kiro-color.svg in the reference extension
(viewBox 24x24): a speech bubble with a prompt mark. Needs Pillow.

    python scripts/make-icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "bundles" / "io.github.liaoalia.kiroacp.ui" / "icons"
PURPLE = (0x8A, 0x5C, 0xF6, 255)
WHITE = (255, 255, 255, 255)
SUPERSAMPLE = 8


def draw(size: int) -> Image.Image:
    k = size * SUPERSAMPLE / 24.0
    image = Image.new("RGBA", (size * SUPERSAMPLE, size * SUPERSAMPLE), (0, 0, 0, 0))
    d = ImageDraw.Draw(image)

    def p(x, y):
        return (x * k, y * k)

    # Bubble: rounded rectangle (4,3)-(20,16), radius 2.5, with a tail to (5.5,20).
    d.rounded_rectangle([p(4, 3), p(20, 16)], radius=2.5 * k, fill=PURPLE)
    d.polygon([p(5.5, 15), p(10, 16), p(5.5, 20)], fill=PURPLE)
    # Prompt mark: a chevron and an underscore, stroke 1.8 with round ends.
    width = max(1, round(1.8 * k))
    d.line([p(9, 8), p(12, 10.5), p(9, 13)], fill=WHITE, width=width, joint="curve")
    d.line([p(13, 13), p(16, 13)], fill=WHITE, width=width)
    r = width / 2
    for x, y in [(9, 8), (9, 13), (13, 13), (16, 13)]:
        cx, cy = p(x, y)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=WHITE)
    return image.resize((size, size), Image.LANCZOS)


OUT.mkdir(parents=True, exist_ok=True)
draw(16).save(OUT / "kiro.png")
draw(32).save(OUT / "kiro@2x.png")
print(f"wrote {OUT / 'kiro.png'} and kiro@2x.png")
