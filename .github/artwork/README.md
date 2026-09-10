# Porter artwork

Icon, mascot and banner artwork by [Max Patchs](https://x.com/maxpatchs).

`icon.svg` is the neutral mascot without arms or legs. `mascot.svg` is the
neutral full-body mascot and `mascot-happy.svg` is the same body with a happy
expression, shown while Porter is running. All three preserve the supplied
vector artwork. `background.png` is the solid mint launcher background
(`#dcffee`) used by Porter; `background-compat.png` is the near-black
(`#060d20`) plate that gives the compatibility companion a distinguishable
launcher icon. `banner.png` is the supplied 1026 × 500 banner.

`monochrome.svg` is a derivative of `icon.svg` for themed launcher icons and
notifications. White becomes visible ink; black becomes transparent cutouts.
Keep its geometry in sync when changing the icon. Eyes, collar, hat band,
buttons and the key must remain distinguishable at small sizes.

`icon-compat.svg` and `monochrome-compat.svg` are the companion's pair, built
from the same mascot with the key swapped for a luggage tag. The key stands for
access, which is Porter's job; the companion's job is translation, holding the
legacy `moe.shizuku.privileged.api` package so Shizuku-only apps keep working,
and a luggage tag says that. Everything else in the two files matches the
manager's, so a change to the mascot has to be made in all four sources.
`monochrome-compat.svg` follows the same recipe as `monochrome.svg`: the tag is
white ink outlined in black, with a separate black path for its punched hole.

Regenerate the checked-in Android and website images from the repository root:

```sh
uv run tools/update-artwork.py
```

The script pins Pillow and CairoSVG. CairoSVG also requires the system Cairo
library (for example, `libcairo2` on Debian/Ubuntu); rendering can vary slightly
between Cairo versions. Review generated images before committing them.

Adaptive layers use a 108dp canvas with the artwork inside the central 66dp
safe circle. The generator also produces legacy launcher icons, notification
artwork, the 44dp home-screen mascot, and an Android TV banner. The GitHub
README and website share `docs/assets/porter-banner.png` at its original size
and aspect ratio.
