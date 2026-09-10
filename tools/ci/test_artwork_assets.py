import hashlib
from pathlib import Path
import struct
import unittest
import zlib


ROOT = Path(__file__).resolve().parents[2]
MANAGER = ROOT / "manager/src/main/res/drawable-xxxhdpi"
COMPAT = ROOT / "compat/src/main/res/drawable-xxxhdpi"
ASSETS = ROOT / "docs/assets"
MINT = (0xDC, 0xFF, 0xEE)
NEAR_BLACK = (0x06, 0x0D, 0x20)


def chunks(data):
    offset = 8
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        yield data[offset + 4:offset + 8], data[offset + 8:offset + 8 + length]
        offset += length + 12


def header(path):
    """Width, height, bit depth, colour type and interlace method from IHDR."""
    for kind, payload in chunks(path.read_bytes()):
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", payload)
            return width, height, depth, colour, interlace
    raise AssertionError(f"{path} has no IHDR chunk")


def first_pixel(path):
    """The top-left RGB triple, read without reconstructing any PNG filter.

    Every filter type predicts from pixels above and to the left, and both are
    zero at the start of the first scanline, so the first pixel is always
    stored unfiltered.
    """
    depth, colour, interlace = header(path)[2:]
    assert (depth, colour, interlace) == (8, 2, 0), \
        f"{path} is depth {depth}, colour type {colour}, interlace {interlace}, not 8-bit RGB"
    scanlines = zlib.decompress(b"".join(p for k, p in chunks(path.read_bytes()) if k == b"IDAT"))
    return tuple(scanlines[1:4])


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ArtworkAssetsTest(unittest.TestCase):
    files = {
        "manager launcher": MANAGER / "ic_launcher.png",
        "manager background": MANAGER / "porter_background.png",
        "manager foreground": MANAGER / "porter_foreground.png",
        "manager monochrome": MANAGER / "porter_monochrome.png",
        "manager mascot": MANAGER / "porter_mascot.png",
        "manager happy mascot": MANAGER / "porter_mascot_happy.png",
        "compat launcher": COMPAT / "ic_porter.png",
        "compat background": COMPAT / "porter_background.png",
        "compat foreground": COMPAT / "porter_foreground.png",
        "compat monochrome": COMPAT / "porter_monochrome.png",
        "website icon": ASSETS / "porter-icon.png",
        "website favicon": ASSETS / "favicon.png",
    }

    def test_every_generated_asset_is_checked_in(self):
        missing = sorted(name for name, path in self.files.items() if not path.is_file())
        self.assertEqual([], missing)

    def test_companion_launcher_icon_is_distinguishable_from_porters(self):
        self.assertNotEqual(digest(MANAGER / "ic_launcher.png"), digest(COMPAT / "ic_porter.png"))
        self.assertNotEqual(digest(MANAGER / "porter_background.png"), digest(COMPAT / "porter_background.png"))

    def test_modules_carry_their_own_foreground_and_monochrome_layers(self):
        self.assertNotEqual(digest(MANAGER / "porter_foreground.png"), digest(COMPAT / "porter_foreground.png"))
        self.assertNotEqual(digest(MANAGER / "porter_monochrome.png"), digest(COMPAT / "porter_monochrome.png"))

    def test_website_icon_is_porters_and_not_the_companions(self):
        self.assertEqual(digest(MANAGER / "ic_launcher.png"), digest(ASSETS / "porter-icon.png"))

    def test_running_mascot_differs_from_the_neutral_one(self):
        self.assertNotEqual(digest(MANAGER / "porter_mascot.png"), digest(MANAGER / "porter_mascot_happy.png"))

    def test_plates_keep_their_assigned_colours(self):
        self.assertEqual(MINT, first_pixel(MANAGER / "porter_background.png"))
        self.assertEqual(NEAR_BLACK, first_pixel(COMPAT / "porter_background.png"))

    def test_favicon_keeps_its_browser_size(self):
        self.assertEqual((32, 32), header(ASSETS / "favicon.png")[:2])


if __name__ == "__main__":
    unittest.main()
