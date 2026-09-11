#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["Pillow==12.3.0", "CairoSVG==2.9.1"]
# ///
"""Generate app and website assets: uv run tools/update-artwork.py."""
from io import BytesIO
from pathlib import Path
from math import hypot

import cairosvg
from PIL import Image, ImageChops, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.github/artwork'
RESAMPLE = Image.Resampling.LANCZOS


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def silhouette(name):
    png = cairosvg.svg2png(url=str(SOURCE / f'{name}.svg'), output_width=1024)
    image = Image.open(BytesIO(png)).convert('RGBA')
    bounds = image.getchannel('A').point(lambda alpha: 255 if alpha > 8 else 0).getbbox()
    if not bounds:
        raise ValueError(f'{name}.svg has no visible artwork')
    return image.crop(bounds)


def body_axis(layer):
    # The mascot's #4ce088 body, picked up loosely because the artwork is anti-aliased.
    pixels = layer.load()
    columns = [x for x in range(layer.width) for y in range(layer.height)
               if (lambda r, g, b, a: a > 8 and g > 150 and r < 140 and b < 170)(*pixels[x, y])]
    if not columns:
        raise ValueError('adaptive_layer(): the colour artwork has no green body pixels to align on')
    return (min(columns) + max(columns)) / 2


def safe_radius(layer):
    alpha = layer.getchannel('A').load()
    return max(hypot(x - 216, y - 216) for y in range(layer.height)
               for x in range(layer.width) if alpha[x, y] > 8)


def adaptive_layer(image, shift=None):
    # Keep the visible artwork inside Android's central 66dp safe circle on a 108dp canvas.
    alpha = image.getchannel('A')
    cx, cy = image.width / 2, image.height / 2
    radius = max(hypot(x - cx, y - cy) for y in range(image.height)
                 for x in range(image.width) if alpha.getpixel((x, y)) > 8)
    scale = min(240 / max(image.size), 130 / radius)
    # The body-axis shift moves the artwork off the centre the radius was measured about, so the
    # scale has to be solved against the radius the placed canvas actually has: shrink, re-place,
    # re-measure until it fits.
    for _ in range(16):
        resized = image.resize((round(image.width * scale), round(image.height * scale)), RESAMPLE)

        def place(dx, art=resized):
            canvas = Image.new('RGBA', (432, 432))
            canvas.alpha_composite(art, ((432 - art.width) // 2 + dx,
                                         (432 - art.height) // 2))
            return canvas

        # Centre the body, not the bounding box: the puzzle piece and the key both overhang
        # further left than anything overhangs right, so balancing the box leaves the figure
        # sitting right of centre.
        placement = round(216 - body_axis(place(0))) if shift is None else shift
        canvas = place(placement)
        reach = safe_radius(canvas)
        if reach <= 130:
            break
        scale *= 130 / reach
    if shift is None:
        placed = body_axis(canvas)
        if abs(placed - 216) > 1:
            raise ValueError(f'adaptive_layer(): body axis landed at x={placed}, not 216')
    if reach > 130:
        raise ValueError(f'adaptive_layer(): artwork reaches {reach:.1f}px, outside the 130px '
                         'safe circle')
    return canvas, placement


def plate(name):
    return Image.open(SOURCE / f'{name}.png').convert('RGB').resize((432, 432), RESAMPLE)


def tinted(name):
    source = silhouette(name)
    # Android tints the alpha mask for themed icons and notifications.
    image = Image.new('RGBA', source.size, 'white')
    image.putalpha(ImageChops.multiply(source.convert('L'), source.getchannel('A')))
    return image


background = plate('background')
background_compat = plate('background-compat')

# The companion carries a puzzle piece where Porter carries the key, so each module scales and
# centres its own artwork.
for module, launcher, plate_image, icon, mono in [
        ('manager', 'ic_launcher', background, 'icon', 'monochrome'),
        ('compat', 'ic_porter', background_compat, 'icon-compat', 'monochrome-compat')]:
    res = ROOT / module / 'src/main/res'
    foreground, body_shift = adaptive_layer(silhouette(icon))
    monochrome = tinted(mono)
    mono_layer, _ = adaptive_layer(monochrome, body_shift)
    composite = plate_image.convert('RGBA')
    composite.alpha_composite(foreground)
    legacy = composite.crop((72, 72, 360, 360)).resize((192, 192), RESAMPLE)
    if module == 'manager':
        # The website reuses Porter's own icon, never the companion's.
        manager_legacy = legacy
        manager_monochrome = monochrome
    for name, artwork in [(launcher, legacy), ('porter_foreground', foreground),
                          ('porter_background', plate_image), ('porter_monochrome', mono_layer)]:
        save(artwork, res / 'drawable-xxxhdpi' / f'{name}.png')
    adaptive = res / 'drawable-anydpi-v26' / f'{launcher}.xml'
    adaptive.parent.mkdir(parents=True, exist_ok=True)
    adaptive.write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/porter_background" />
    <foreground android:drawable="@drawable/porter_foreground" />
    <monochrome android:drawable="@drawable/porter_monochrome" />
</adaptive-icon>
''')

notification = Image.new('RGBA', (96, 96))
small = ImageOps.contain(manager_monochrome, (88, 88), RESAMPLE)
notification.alpha_composite(small, ((96 - small.width) // 2, (96 - small.height) // 2))
save(notification, ROOT / 'manager/src/main/res/drawable-xxxhdpi/ic_system_icon.png')

def mascot(name):
    canvas = Image.new('RGBA', (176, 176))
    body = ImageOps.contain(silhouette(name), canvas.size, RESAMPLE)
    canvas.alpha_composite(body, ((176 - body.width) // 2, (176 - body.height) // 2))
    return canvas


save(mascot('mascot'), ROOT / 'manager/src/main/res/drawable-xxxhdpi/porter_mascot.png')
save(mascot('mascot-happy'), ROOT / 'manager/src/main/res/drawable-xxxhdpi/porter_mascot_happy.png')
save(mascot('mascot-unhappy'), ROOT / 'manager/src/main/res/drawable-xxxhdpi/porter_mascot_unhappy.png')

banner = Image.open(SOURCE / 'banner.png').convert('RGB')
def padded_banner(size):
    canvas = Image.new('RGB', size, banner.getpixel((0, 0)))
    art = ImageOps.contain(banner, size, RESAMPLE)
    canvas.paste(art, ((size[0] - art.width) // 2, (size[1] - art.height) // 2))
    return canvas

save(padded_banner((320, 180)), ROOT / 'manager/src/main/res/drawable-xhdpi/porter_banner.png')
assets = ROOT / 'docs/assets'
save(banner, assets / 'porter-banner.png')
save(manager_legacy, assets / 'porter-icon.png')
save(manager_legacy.resize((32, 32), RESAMPLE), assets / 'favicon.png')
print('Updated launcher icons on the mint and companion plates, adaptive, monochrome, '
      'notification, neutral, happy and unhappy mascots, TV and website artwork.')
