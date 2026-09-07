#!/usr/bin/env python3
"""Generate app and website assets from .github/artwork (requires Pillow)."""
from pathlib import Path
from math import hypot

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / '.github/artwork'
RESAMPLE = Image.Resampling.LANCZOS


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def silhouette(name):
    image = Image.open(SOURCE / f'{name}.png').convert('RGBA')
    bounds = image.getchannel('A').point(lambda alpha: 255 if alpha > 8 else 0).getbbox()
    if not bounds:
        raise ValueError(f'{name}.png has no visible artwork')
    return image.crop(bounds)


def adaptive_layer(image):
    # Keep the visible artwork inside Android's central 66dp safe circle on a 108dp canvas.
    alpha = image.getchannel('A')
    cx, cy = image.width / 2, image.height / 2
    radius = max(hypot(x - cx, y - cy) for y in range(image.height)
                 for x in range(image.width) if alpha.getpixel((x, y)) > 8)
    scale = min(240 / max(image.size), 130 / radius)
    resized = image.resize((round(image.width * scale), round(image.height * scale)), RESAMPLE)
    canvas = Image.new('RGBA', (432, 432))
    canvas.alpha_composite(resized, ((432 - resized.width) // 2, (432 - resized.height) // 2))
    return canvas


background = Image.open(SOURCE / 'background.png').convert('RGB').resize((432, 432), RESAMPLE)
foreground = adaptive_layer(silhouette('foreground'))
mono_source = silhouette('monochrome')
# Android tints the alpha mask for themed icons and notifications.
monochrome = Image.new('RGBA', mono_source.size, 'white')
monochrome.putalpha(mono_source.getchannel('A'))
mono_layer = adaptive_layer(monochrome)
composite = background.convert('RGBA')
composite.alpha_composite(foreground)
legacy = composite.crop((72, 72, 360, 360)).resize((192, 192), RESAMPLE)

for module, launcher in [('manager', 'ic_launcher'), ('compat', 'ic_porter')]:
    res = ROOT / module / 'src/main/res'
    for name, artwork in [(launcher, legacy), ('porter_foreground', foreground),
                          ('porter_background', background), ('porter_monochrome', mono_layer)]:
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
small = ImageOps.contain(monochrome, (88, 88), RESAMPLE)
notification.alpha_composite(small, ((96 - small.width) // 2, (96 - small.height) // 2))
save(notification, ROOT / 'manager/src/main/res/drawable-xxxhdpi/ic_system_icon.png')

banner = Image.open(SOURCE / 'banner.png').convert('RGB')
def padded_banner(size):
    canvas = Image.new('RGB', size, '#122126')
    art = ImageOps.contain(banner, size, RESAMPLE)
    canvas.paste(art, ((size[0] - art.width) // 2, (size[1] - art.height) // 2))
    return canvas

save(padded_banner((320, 180)), ROOT / 'manager/src/main/res/drawable-xhdpi/porter_banner.png')
assets = ROOT / 'docs/assets'
save(padded_banner((1280, 640)), assets / 'porter-banner.png')
save(legacy, assets / 'porter-icon.png')
save(legacy.resize((32, 32), RESAMPLE), assets / 'favicon.png')
print('Updated launcher, adaptive, monochrome, notification, TV and website artwork.')
