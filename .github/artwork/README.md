# Porter artwork

Icon, mascot and banner artwork by [Max Patchs](https://x.com/maxpatchs).

`icon.svg` is the neutral mascot without arms or legs. `mascot.svg` is the
neutral full-body mascot and `mascot-happy.svg` is the same body with a happy
expression, shown while Porter is running without restricted permissions.
`mascot-unhappy.svg` is the supplied `SVG Normal - Happy - Sad/sad full body.svg`, shown while Porter is
running with restricted permissions. All four preserve the supplied vector
artwork. `background.png` is the solid mint launcher background
(`#dcffee`) used by Porter; `background-compat.png` is the near-black
(`#060d20`) plate that gives the compatibility companion a distinguishable
launcher icon. `banner.png` is the supplied 1026 × 500 banner.

`monochrome.svg` is a derivative of `icon.svg` for themed launcher icons and
notifications. White becomes visible ink; black becomes transparent cutouts.
Keep its geometry in sync when changing the icon. Eyes, collar, hat band,
buttons and the key must remain distinguishable at small sizes.

`icon-compat.svg` is the supplied neutral puzzle mascot without arms or legs
(from the supplied Puzzle artwork: `SVG Normal - Happy - Sad/body no hands.svg`).
The puzzle piece distinguishes Porter Compatibility from Porter's key mascot.
Preserve the supplied vector geometry when updating this source.

`monochrome-compat.svg` derives from that same artwork: the body is white ink;
eyes, collar, hat band and buttons are black cutouts. The puzzle piece is white
with a black outline separating it from the body. Keep the pair in sync.
Apart from the puzzle piece, the mascot body shares its geometry with `icon.svg`
and `monochrome.svg`; a change to the shared body has to be made in both pairs.

Regenerate the checked-in Android and website images from the repository root:

```sh
uv run tools/update-artwork.py
```

The script pins Pillow and CairoSVG. CairoSVG also requires the system Cairo
library (for example, `libcairo2` on Debian/Ubuntu); rendering can vary slightly
between Cairo versions. Review generated images before committing them.

Adaptive layers use a 108dp canvas with the artwork inside the central 66dp
safe circle. The generator also produces legacy launcher icons, notification
artwork, the three 44dp home-screen mascots, and an Android TV banner. The GitHub
README and website share `docs/assets/porter-banner.png` at its original size
and aspect ratio.
