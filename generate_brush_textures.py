#!/usr/bin/env python3
"""
Generates the default WetInkNext brush texture library into
app/src/main/assets/brush.

Run:  python3 generate_brush_textures.py
Requires: Pillow, numpy. Deterministic (fixed seed).

Two families, matching how the dab shader consumes them:

  SHAPE TIPS  - RGBA, alpha channel is the coverage mask. Sampled in
                dab-local coordinates (uShapeTex), optionally as
                luminance when rgbToAlpha is set. White shape on a
                transparent background with soft edges.
  GRAIN TEXTS - grayscale L, luminance-modulated via applyTextureLevels
                (contrast/depth). Mid-gray base so the levels mapping
                has room both ways.
"""
import math
import os

import numpy as np
from PIL import Image, ImageFilter, ImageDraw

SEED = 42
OUT_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "app", "src", "main", "assets", "brush",
)


def sha():
    np.random.seed(SEED)


def save_rgba(img, name):
    os.makedirs(OUT_DIR, exist_ok=True)
    img.save(os.path.join(OUT_DIR, name + ".png"))


def save_l(img, name):
    os.makedirs(OUT_DIR, exist_ok=True)
    img.convert("L").save(os.path.join(OUT_DIR, name + ".png"))


def smooth_noise(size, cell, amplitude=90.0, base=128.0, blur=1.5):
    """Cheap value noise: random low-res grid resized up and blurred."""
    cells = max(2, size // cell)
    grid = np.random.rand(cells, cells) * amplitude + (base - amplitude / 2)
    img = Image.fromarray(grid.astype("uint8")).resize(
        (size, size), Image.Resampling.BICUBIC
    )
    if blur > 0:
        img = img.filter(ImageFilter.GaussianBlur(blur))
    return img


def fine_noise(size, cell=1, amplitude=120.0, base=128.0):
    return tone_noise(size, cell, amplitude, base, blur=0.0)


# ---------------------------------------------------------------- grains ----

def gen_noise_fine():
    arr = np.random.rand(256, 256) * 255
    save_l(Image.fromarray(arr.astype("uint8")), "noise_fine")


def gen_noise_coarse():
    save_l(smooth_noise(512, 128, amplitude=100.0, blur=2.5), "noise_coarse")


def gen_paper_cold_press():
    # Rough watercolour paper: slow tonal bed plus pronounced cellulose grain.
    bed = smooth_noise(512, 96, amplitude=70.0, blur=4.0)
    grain = np.random.rand(512, 512) * 46 - 23
    specks = np.random.rand(512, 512)
    bed = bed + grain
    bed[specks < 0.008] -= 70 + np.random.rand(int((specks < 0.008).sum())) * 40
    save_l(Image.fromarray(np.clip(bed, 0, 255).astype("uint8")), "paper_cold_press")


def gen_paper_hot_press():
    bed = smooth_noise(512, 64, amplitude=42.0, base=172.0, blur=2.2)
    fine = np.random.rand(512, 512) * 20.0 - 10.0
    save_l(Image.fromarray(np.clip(bed + fine, 0, 255).astype("uint8")), "paper_hot_press")


def gen_canvas_linen():
    # Plain-woven canvas: horizontal + vertical thread rows in L, subtle.
    size = 512
    x = np.linspace(0, np.pi * 18.0, size)
    weave_x = (np.sin(x) ** 3) * 16.0
    weave = weave_x[None, :] + weave_x[:, None]
    base = 128.0 + 40.0 + weave
    noise = np.random.rand(size, size) * 18.0 - 9.0
    # Thread shadows: dark thin lines every 8 px.
    rows = np.zeros(size)
    rows[::8] = -22.0
    base = base + rows[:, None] + rows[None, :]
    save_l(Image.fromarray(np.clip(base + noise, 0, 255).astype("uint8")), "canvas_linen")


def gen_pencil_6b_grain():
    # Dark grit with a directional streak, like soft graphite.
    size = 256
    arr = np.random.rand(256, 256) * 255.0
    streaks = np.random.rand(256, 1) < 0.12
    arr = arr + streaks * np.random.rand(256, 1) * 120.0
    arr = np.clip(arr * 0.45, 0, 255)
    img = Image.fromarray(arr.astype("uint8")).filter(
        ImageFilter.GaussianBlur(0.7)
    )
    img = img.filter(ImageFilter.RankFilter(3, 6))
    save_l(img, "pencil_6b_grain")


def gen_grain_test():
    # Diagnostic left-to-right ramp, validates levels mapping instantly.
    size = 512
    ramp = np.tile(np.linspace(0, 255, size, dtype=np.uint8), (size, 1))
    save_l(Image.fromarray(ramp), "grain_test")


def gen_watercolor_blotch():
    # Granulating wash blot: random blots blurred, contrast stretched around
    # mid-gray so the shader's levels mapping sees detail in both directions.
    size = 512
    base = np.zeros((size, size))
    for _ in range(28):
        x, y = np.random.randint(0, size, 2)
        r = np.random.randint(28, 80)
        base[y - r:y + r, x - r:x + r] += np.random.rand() * 70.0
    bed = Image.fromarray(base.astype("uint8")).filter(ImageFilter.GaussianBlur(26))
    detail = np.random.rand(size, size) * 60.0 - 30.0
    arr = np.clip(np.array(bed) * 0.9 + detail, 0, 255)
    arr = (arr - arr.mean()) * 1.8 + 128.0
    arr = np.clip(arr, 0, 255)
    img = Image.fromarray(arr.astype("uint8")).filter(
        ImageFilter.GaussianBlur(0.5)
    )
    save_l(img, "watercolor_blotch")


def gen_halftone_dots():
    # Light paper with small dark dots: flock/graphic-novel texture.
    size = 512
    period = 14
    xx, yy = np.mgrid[0:size, 0:size]
    dd = (xx % period) - period / 2.0, (yy % period) - period / 2.0
    d = np.sqrt(dd[0] ** 2 + dd[1] ** 2)
    dots = np.where(d < period * 0.17, 42.0, 238.0)
    arr = np.clip(dots + np.random.rand(size, size) * 14 - 7, 0, 255).astype("uint8")
    save_l(Image.fromarray(arr), "halftone_dots")


# ------------------------------------------------------------- shape tips ---

def tip_blank(size):
    return Image.new("RGBA", (size, size), (255, 255, 255, 0))


def smooth01(t):
    """Hermite smoothstep clamped to 0..1."""
    t = min(1.0, max(0.0, t))
    return t * t * (3.0 - 2.0 * t)


def gen_round_soft():
    """Soft airbrush-style ball: white core with a smooth alpha falloff."""
    size = 256
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx = cy = size / 2.0
    r = 118.0
    for y in range(size):
        for x in range(size):
            d = math.sqrt((x - cx) ** 2 + (y - cy) ** 2) / r
            if d > 1.15:
                continue
            a = max(0.0, 1.0 - d) ** 2.1
            a *= 0.94 + 0.06 * np.random.rand()
            draw.point((x, y), fill=(255, 255, 255, int(a * 255)))
    save_rgba(img, "round_soft")


def gen_charcoal_stick(size=128):
    """Rough toothy oval: soft elliptical silhouette with grit and a jagged rim."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    rx, ry = 58, 42
    for y in range(size):
        for x in range(size):
            nx = (x - cx) / rx
            ny = (y - cy) / ry
            d = nx * nx + ny * ny
            if d > 1.05:
                continue
            core = max(0.0, 1.0 - d) ** 0.75
            rim = 0.5 + 0.5 * np.sin(36.0 * np.arctan2(ny, nx) + 5.0 * np.sin(8.0 * np.arctan2(ny, nx)))
            a = core * (0.45 + 0.55 * rim) * (0.35 + 0.65 * np.random.rand())
            draw.point((x, y), fill=(255, 255, 255, int(min(1.0, a) * 255)))
    img = img.filter(ImageFilter.GaussianBlur(0.7))
    save_rgba(img, "charcoal_stick")


def gen_dry_brush(size=256):
    """Sparse bristle tufts: thin streaky hairlines with breaks."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    for _ in range(30):
        x0 = np.random.uniform(50, size - 50)
        y0 = np.random.uniform(50, size - 50)
        angle = np.random.uniform(0, 2 * np.pi)
        for _ in range(np.random.randint(4, 9)):
            spread = np.random.uniform(-0.7, 0.7)
            length = np.random.randint(20, 56)
            alpha = np.random.randint(130, 235)
            for t in range(length):
                px = x0 + np.cos(angle + spread) * t * 1.0 + np.random.randn() * 0.8
                py = y0 + np.sin(angle + spread) * t * 1.0 + np.random.randn() * 0.8
                if 0 <= px < size and 0 <= py < size:
                    draw.point(
                        (int(px), int(py)),
                        fill=(255, 255, 255, int(alpha * max(0.3, 1.0 - t / length))),
                    )
    img = img.filter(ImageFilter.GaussianBlur(0.5))
    save_rgba(img, "dry_brush")


def gen_filbert(size=256):
    """Wide soft oval tip with rounded ends (vertical orientation)."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    rx, ry = 64, 112
    for y in range(size):
        yy = (y - cy) / ry
        if abs(yy) > 1.0:
            continue
        half = rx * np.sqrt(max(0.0, 1.0 - yy * yy))
        for x in range(size):
            xx = (x - cx) / max(half, 1e-6)
            a = max(0.0, 1.0 - xx * xx) ** 2.0
            if a > 0.004:
                draw.point((x, y), fill=(255, 255, 255, int(a * 240)))
    save_rgba(img.filter(ImageFilter.GaussianBlur(1.1)), "filbert")


def gen_flat_brush(size=256):
    """Wide flat tongue tip with feathered edges."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    rx, ry = 84, 44
    for y in range(size):
        for x in range(size):
            edge = max(abs((x - cx) / rx), abs((y - cy) / ry))
            if edge > 1.02:
                continue
            a = max(0.0, 1.0 - edge) ** 1.2
            if a > 0.02:
                draw.point((x, y), fill=(255, 255, 255, int(a * 255)))
    img = img.filter(ImageFilter.GaussianBlur(1.0))
    save_rgba(img, "flat_brush")


def gen_splatter(size=512):
    """Ink splatter: irregular central blot plus a field of droplets."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    for _ in range(46):
        r = np.random.randint(8, 26)
        a = np.random.randint(40, 170)
        x = cx + np.random.randn() * 16
        y = cy + np.random.randn() * 16
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, a))
    for _ in range(150):
        r = np.random.choice([1, 1, 2, 3, 4])
        x = np.random.randint(20, size - 20)
        y = np.random.randint(20, size - 20)
        a = np.random.randint(90, 235)
        dx = np.random.randint(0, 6)
        draw.ellipse((x - r, y - r, x + r, y + r + dx), fill=(255, 255, 255, a))
    img = img.filter(ImageFilter.GaussianBlur(0.5))
    save_rgba(img, "splatter")


def gen_sponge(size=256):
    """Porous sponge tip: irregular mass with punched holes."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    for _ in range(90):
        x = cx + np.random.randn() * 62
        y = cy + np.random.randn() * 62
        r = np.random.randint(12, 30)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 130))
    for _ in range(130):
        x = cx + np.random.randn() * 70
        y = cy + np.random.randn() * 70
        r = np.random.randint(1, 5)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 0))
    img = img.filter(ImageFilter.GaussianBlur(1.4))
    save_rgba(img, "sponge")


def gen_leaf(size=256):
    """Leaf tip: tapered ellipse with a central vein."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    length, width = 112, 58
    steps = 160
    pts = []
    for i in range(steps + 1):
        t = 2.0 * i / steps - 1.0  # -1..1, vertical axis
        half = max(0.02, 1.0 - t * t) ** 0.72 * width
        pts.append((cx + half, cy - t * length))
    draw.polygon(pts, fill=(255, 255, 255, 235))
    draw.line((cx, cy - length, cx, cy + length * 0.96), fill=(255, 255, 255, 255), width=2)
    for v in (-0.5, -0.25, 0.25, 0.5):
        draw.line((cx, cy - length * 0.9, cx + 16 * v, cy + length * 0.9), fill=(255, 255, 255, 200))
    img = img.filter(ImageFilter.GaussianBlur(0.7))
    save_rgba(img, "leaf")


def gen_fiber(size=256):
    """Fine wavy fibres, few of them, thin."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    for _ in range(26):
        x = np.random.uniform(20, size - 20)
        y0 = np.random.uniform(10, size / 2 - 30)
        theta = np.random.uniform(-0.9, 0.9)
        length = np.random.randint(100, 220)
        phase = np.random.uniform(0, 6)
        for t in np.arange(0.0, length, 1.0):
            px = x + np.cos(theta) * t + np.sin(t * 0.22 + phase) * 16.0
            py = y0 + np.sin(theta) * t * 0.2 + np.random.randn() * 1.2
            if 0 <= px < size and 0 <= py < size:
                draw.point((int(px), int(py)), fill=(255, 255, 255, 220))
    img = img.filter(ImageFilter.GaussianBlur(0.4))
    save_rgba(img, "fiber")


def gen_crack(size=512):
    """Cracked-surface tip: opaque mask with thin transparent cracks."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    draw.rectangle((0, 0, size, size), fill=(255, 255, 255, 255))
    seg_len = 2.0
    branches = [(size / 2, size / 2, np.random.uniform(0, 2 * np.pi))]
    for depth in range(16):
        new = []
        for (x, y, theta) in branches:
            length = np.random.randint(12, 48)
            for _ in range(length):
                x += np.cos(theta) * seg_len + np.random.randn() * 0.6
                y += np.sin(theta) * seg_len + np.random.randn() * 0.6
                if not (0 <= x < size and 0 <= y < size):
                    break
                r = np.random.uniform(0.6, 1.8)
                draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 0))
                if np.random.rand() < 0.07:
                    new.append((x, y, theta + np.random.uniform(-1.3, 1.3)))
        if not new and depth < 8:
            new.append(
                (
                    size / 2 + np.random.randint(-70, 70),
                    size / 2 + np.random.randint(-70, 70),
                    np.random.uniform(0, 2 * np.pi),
                )
            )
        branches = new[:9]
    img = img.filter(ImageFilter.GaussianBlur(0.5))
    save_rgba(img, "crack")


def gen_marker_chisel(size=256):
    """Chisel marker tip: slanted parallelogram, hard edges, fine seam line."""
    angle = -0.55  # radians; chisel tilt
    cos_a, sin_a = math.cos(angle), math.sin(angle)
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    ru, rv = 96.0, 26.0
    for y in range(size):
        for x in range(size):
            dx, dy = x - cx, y - cy
            u = dx * cos_a - dy * sin_a
            v = dx * sin_a + dy * cos_a
            eu = abs(u) / ru
            ev = abs(v) / rv
            if eu > 1.02 or ev > 1.02:
                continue
            a = max(0.0, 1.0 - eu ** 1.6) * max(0.0, 1.0 - ev ** 1.6)
            if a <= 0.004:
                continue
            a = a ** 0.9
            draw.point((x, y), fill=(255, 255, 255, int(a * 255)))
    # Soft seam across the middle: the two chisel facets meet, but the
    # alpha stays nonzero so stamps never show a hard notch.
    draw.line(
        (
            cx + math.sin(angle) * ru,
            cy + math.cos(angle) * ru,
            cx - math.sin(angle) * ru * 0.7,
            cy - math.cos(angle) * ru * 0.7,
        ),
        fill=(255, 255, 255, 130),
        width=3,
    )
    save_rgba(img.filter(ImageFilter.GaussianBlur(0.4)), "marker_chisel")


def gen_corner_chisel(size=256):
    """Corner chisel (уголок): L-shaped nib for sharp corner strokes."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    cx, cy = size / 2, size / 2
    arm = 96
    for y in range(size):
        for x in range(size):
            dx = (x - cx) / arm
            dy = (y - cy) / arm
            horizontal = max(0.0, 1.0 - abs(dy)) * smooth01((dx + 0.9) / 0.8)
            vertical = max(0.0, 1.0 - abs(dx)) * smooth01((0.8 - dy) / 0.8)
            core = max(horizontal, vertical)
            if core <= 0.004:
                continue
            a = core ** 1.2
            # The meeting point stays sharp; arms taper slightly outward.
            a *= 1.0 - 0.35 * max(0.0, 1.0 - abs(dx - dy) * 1.6)
            draw.point((x, y), fill=(255, 255, 255, int(a * 255)))
    save_rgba(img.filter(ImageFilter.GaussianBlur(0.3)), "corner_chisel")


def gen_aerosol(size=256):
    """Spray can: speckle cloud with radial falloff plus a few larger drops."""
    cx = cy = size / 2
    r = 112.0
    x = np.arange(size)[None, :] - cx
    y = np.arange(size)[:, None] - cy
    d = np.sqrt(x ** 2 + y ** 2) / r
    core = np.clip(1.0 - d, 0.0, 1.0) ** 1.6
    speckle = np.random.rand(size, size)
    alpha = np.clip(core * (0.48 + 0.52 * speckle), 0.0, 1.0)
    for _ in range(90):
        px = np.random.randint(0, size)
        py = np.random.randint(0, size)
        pr = np.random.uniform(2.0, 7.0)
        yy, xx = np.mgrid[0:size, 0:size]
        dist = np.sqrt((xx - px) ** 2 + (yy - py) ** 2)
        alpha = np.maximum(alpha, np.where(dist < pr, 0.85, 0.0))
    rgba = np.dstack(
        [
            np.full((size, size), 255, dtype=np.uint8),
            np.full((size, size), 255, dtype=np.uint8),
            np.full((size, size), 255, dtype=np.uint8),
            (alpha * 255).astype(np.uint8),
        ]
    )
    save_rgba(Image.fromarray(rgba, "RGBA"), "aerosol")


def gen_fan_brush(size=256):
    """Fan (veernaya) brush: bristles radiating from a base point like a fan."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    bx, by = size / 2, size - 18      # bristle base point
    spread = 2.35                     # total fan angle in radians
    start = -spread / 2
    bristle_count = 140
    for i in range(bristle_count):
        ang = start + (i / (bristle_count - 1)) * spread
        length = np.random.randint(75, 190)
        curve = np.random.uniform(-0.9, 0.9)
        taper = np.random.randint(150, 230)
        phase = np.random.uniform(0, 2 * np.pi)
        pts = []
        for s in range(0, length, 2):
            bend = np.sin(s * 0.02 + phase) * curve
            px = bx + np.sin(ang) * s + np.cos(ang) * bend
            py = by - np.cos(ang) * s + np.sin(ang) * bend
            if 0 <= px < size and 0 <= py < size:
                pts.append((int(px), int(py)))
        # One contiguous polyline per bristle: no dotted segments.
        if len(pts) >= 2:
            draw.line(pts, fill=(255, 255, 255, taper), width=1)
    save_rgba(img.filter(ImageFilter.GaussianBlur(0.4)), "fan_brush")


def gen_stencil_splatter(size=512):
    """Hard-edged stencil splatter: crisp droplets plus donut-shaped rings."""
    img = tip_blank(size)
    draw = ImageDraw.Draw(img)
    for _ in range(70):
        r = np.random.choice([2, 3, 4, 5, 8])
        x = np.random.randint(24, size - 24)
        y = np.random.randint(24, size - 24)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 255))
    for _ in range(14):
        r = np.random.randint(5, 15)
        x = np.random.randint(24, size - 24)
        y = np.random.randint(24, size - 24)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 150))
    for _ in range(9):
        r = np.random.randint(4, 9)
        x = np.random.randint(24, size - 24)
        y = np.random.randint(24, size - 24)
        draw.ellipse(
            (x - r, y - r, x + r, y + r),
            fill=(255, 255, 255, 0),
            outline=(255, 255, 255, 235),
            width=2,
        )
    save_rgba(img.filter(ImageFilter.GaussianBlur(0.15)), "stencil_splatter")


def main():
    sha()
    texts = [
        "noise_fine", "noise_coarse", "paper_cold_press", "paper_hot_press",
        "canvas_linen", "pencil_6b_grain", "grain_test", "watercolor_blotch",
        "halftone_dots", "round_soft", "charcoal_stick", "dry_brush",
        "filbert", "flat_brush", "splatter", "sponge", "leaf", "fiber",
        "crack",
        "marker_chisel", "corner_chisel", "aerosol", "fan_brush",
        "stencil_splatter",
    ]
    for name in texts:
        generator = globals()["gen_" + name]
        generator()
        print("generated", name)


if __name__ == "__main__":
    main()