# Porter artwork

Icon, mascot and banner artwork by [Max Patchs](https://x.com/maxpatchs).

`icon.svg` is the neutral mascot without arms or legs. `mascot.svg` is the
neutral full-body mascot. Both preserve the supplied vector artwork.
`background.png` is the solid mint launcher background (`#dcffee`).
`banner.png` is the supplied 1024 × 500 banner.

`monochrome.svg` is a derivative of `icon.svg` for themed launcher icons and
notifications. White becomes visible ink; black becomes transparent cutouts.
Keep its geometry in sync when changing the icon. Eyes, collar, hat band,
buttons and the key must remain distinguishable at small sizes.

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
