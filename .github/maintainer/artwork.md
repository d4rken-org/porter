# Artwork

The current artwork is the maintainer-supplied placeholder set from September 7, 2026. Its source files live in `.github/artwork/`:

- `banner.png`: landscape wordmark for the README, website, social preview and Android TV.
- `foreground.png`: transparent character for the adaptive icon.
- `background.png`: full-bleed adaptive background.
- `monochrome.png`: transparent silhouette for themed icons and notifications.

Replace these four files with the final artwork, preserving their filenames. Then run:

```sh
python3 -m pip install Pillow
python3 tools/update-artwork.py
```

The script generates the tracked resources for both Android apps and `docs/assets/`. Normal app and website builds use those generated files and do not require Pillow. Foreground and monochrome artwork are centered within the adaptive icon's safe area; the legacy icon is composed from the same layers. The banner is fitted without cropping its wordmark.

Before committing replacements, inspect circular and rounded-square launcher masks, the monochrome notification icon, the Android TV banner and the website at mobile width. Build both app modules and the Pages site.

When publishing the repository, upload `docs/assets/porter-banner.png` in GitHub's repository **Settings > General > Social preview**. The README and website already use that image, and Pages exposes it in its Open Graph metadata. GitHub's repository social preview must be set separately; a file in the repository does not configure it automatically.
