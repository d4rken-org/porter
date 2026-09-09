---
paths: ["manager/src/screenshotTest/**", "manager/src/debug/**", "fastlane/**"]
---

# Play Store Screenshot Generation

Porter renders its Play Store screenshots with **Jetpack Compose Preview Screenshot Testing**. No
device or emulator is involved: everything goes through layoutlib.

**6 phone shots per locale, across 37 locales.** Phone only. Porter's screens are single-column
lists that do not change shape at a tablet breakpoint, so there is no 7"/10" set.

## Architecture

```
ScreenshotContent.kt      →  Mock UI state + one *Content() per shot (manager/src/debug/)
PlayStoreLocales.kt       →  2 annotation classes, light and dark (manager/src/screenshotTest/)
PlayStoreScreenshots.kt   →  6 @PreviewTest entry points (manager/src/screenshotTest/)
        ↓
fastlane/screenshots/locales.txt  →  the locale source of truth
        ↓
generate_screenshots.sh   →  Batch-renders to work around the layoutlib memory leak
        ↓
copy_screenshots.sh       →  Sorts PNGs into fastlane/metadata/android/{locale}/images/
```

The shots render the **hoisted** screen bodies (`HomeScreenContent`, `SettingsScreenContent`) and
the existing `ApplicationManagementList`, never the activities. `HomeActivity.HomeScreen()` and
`SettingsActivity.SettingsScreen()` read view models and call `startActivity`, which layoutlib
cannot do, which is why those bodies were hoisted in the first place.

## Commands

```bash
# Refresh the committed English set (6 renders, 1 batch) — the usual path
./fastlane/generate_screenshots.sh --english

# Smoke test (6 locales: en-US, de-DE, ja-JP, ar, zh-CN, pt-BR)
./fastlane/generate_screenshots.sh --smoke

# Full generation (37 locales, 37 batches — slow)
./fastlane/generate_screenshots.sh

# Custom batch size (default is 1 locale = 6 renders per batch)
./fastlane/generate_screenshots.sh --batch-size 2

# Sort rendered screenshots into fastlane metadata (--clean replaces the target dirs)
./fastlane/copy_screenshots.sh --clean

# Upload
bundle exec fastlane android screenshots_only   # screenshots and nothing else
bundle exec fastlane android listing_only       # listing texts and nothing else
```

`--smoke` before `--english` is deliberate: it is the only command that exercises the per-batch
annotation rewrite and restore across more than one batch, region-coded resource selection
(`values-pt-rBR`, `values-zh-rCN`) and an RTL locale.

## Device Spec

| Form factor | Device spec           | dp      |
|-------------|-----------------------|---------|
| Phone       | 1440×2560 px, 560 dpi | 411×731 |

Exactly 16:9. Play rejects a screenshot whose long side exceeds twice its short side, and only 9:16
portrait / 16:9 landscape are eligible for the promotional surfaces.

The dpi picks the density bucket as well as the dp size. `porter_mascot.png` exists **only** under
`drawable-xxxhdpi`, which 560 dpi selects; a lower-dpi spec would leave the home top bar without its
icon unless another density is added first.

`DS_PHONE` lives in `ScreenshotContent.kt`, not in `PlayStoreLocales.kt`: the generator rewrites the
annotation file wholesale on every batch, so a constant declared there would not survive.

No `showSystemUi` and no synthetic system bars. layoutlib ignores `showSystemUi` in a screenshot
render, and `PorterScaffold` insets through `WindowInsets.safeDrawing`, which layoutlib reports as
zero, so content starts at the top edge with no overlap artefact.

## Why Dark Shots Need Their Own uiMode

`PlayStoreLocalesDark` differs from `PlayStoreLocales` only in `uiMode`, and that difference is
load-bearing.

`PorterTheme(dark = true)` selects the Material colour scheme and nothing else. `colorResource`
lookups still resolve against the render **configuration**: `ServiceStatusCard` tints the status
icon through `colorResource(R.color.porter_status_running)`, which is `#146C2E` in
`values/porter_status.xml` and `#8DDAA2` in `values-night/porter_status.xml`. Without
`UI_MODE_NIGHT_YES` a dark shot paints the light-surface green on a dark card, a combination the app
never produces, because `ShizukuApplication` flips the resource configuration and the theme together
through `AppCompatDelegate.setDefaultNightMode`.

The light annotation states `UI_MODE_NIGHT_NO` explicitly so the two are symmetric and neither
depends on a renderer default.

`PorterTheme` also has a second reason to exist in overload form: the preference-reading overload
calls `ShizukuSettings.getPreferences()`, a static field that is null until `ShizukuApplication`
initializes it, and layoutlib instantiates no Application. Renders call
`PorterTheme(dark, style, color)` through `PorterPreviewWrapper`, exactly once per shot.

## Shot Order

The number is the fastlane filename prefix, which is the order Play shows them in.

| # | Function            | File                     | Theme | What it shows |
|---|---------------------|--------------------------|-------|---------------|
| 1 | `HomeRunning`       | `1_home_running.png`     | light | Service running via ADB, applications card, installed compatibility card |
| 2 | `Apps`              | `2_apps.png`             | light | Management list across five app states |
| 3 | `HomeCompatibility` | `3_home_compatibility.png` | light | Home with the tertiary-coloured compatibility card |
| 4 | `HomeSetup`         | `4_home_setup.png`       | light | Service stopped: wireless debugging and ADB command cards |
| 5 | `Settings`          | `5_settings.png`         | dark  | Settings |
| 6 | `AppsDark`          | `6_apps_dark.png`        | dark  | Management list in dark theme |

Shot 3 reaches the compatibility story through the existing `CompatibilityCard` on Home rather than
through `CompatibilityActivity`, which is still private and activity-coupled. On the **gplay**
variant that card appears when `running && permitted && appsState.pendingCompanionCount > 0`, so the
shot depicts what the Play build actually renders.

Shot 5 is a crop, deliberately. At 411×731 dp the settings screen shows roughly its first seven
rows, so Startup and Appearance land in full and Tools begins. All four categories exist in the
composable and are covered by `SettingsScreenContentTest`, not by the shot.

**Function names must not contain underscores.** `copy_screenshots.sh` splits the rendered file name
at the first underscore to separate the function name from the locale. Rendered names look like
`HomeRunning_en-US_55de6d76_0.png`.

## Determinism

Renders must produce the same pixels on every machine.

- `lastConnectedAt` is `null` on every mock app. A non-null value is formatted through
  `DateFormat.getDateTimeInstance`, which reads the render JVM's default time zone. The plugin
  spawns that process itself, so there is nowhere to pin the zone from the build script.
- Launcher icons are synthesized in `appIcon()`, hue derived from `packageName.hashCode()`, which is
  contractually stable. `PackageManager` resolves nothing under layoutlib.
- The initial drawn on an icon is uppercased with `Locale.ROOT`, so the render locale cannot change
  which glyph a name produces.

## Locales

`fastlane/screenshots/locales.txt` is the single source of truth:

```
<android resource qualifier> <fastlane metadata directory>
```

Field 1 goes into `@Preview(locale = ...)` and selects the `values-<qualifier>` directory in
`manager/src/main/res`, so region-coded locales carry the Android `-r` form (`pt-rBR`, not
`pt-BR`). Field 2 is the `fastlane/metadata/android/<dir>` the PNGs land in, and is also the preview
`name` embedded in the rendered file name.

37 rows: `en` plus the 36 translated locales in the repository. Every directory name is one Play
accepts, which is why there is no `remove_unsupported_languages.sh` here.

`generate_screenshots.sh` validates the list (two fields per row, no duplicate qualifiers, no
duplicate directories, exactly one `en-US`) **before** it rewrites any source file.

Porter ships translations in-tree, so a localized run genuinely produces different pixels per
locale.

### English-only commit policy

Only the `en-US` set is committed. The other locales are gitignored
(`fastlane/metadata/android/*/images/` with an `en-US` exception) and a full run is meant to be done
right before an upload.

`manager/src/screenshotTest*/reference/` is gitignored too. The plugin writes rendered references
into a **source set** path, which neither the root `build/` rule nor `manager/.gitignore`'s `/build`
covers; a full run would otherwise leave 222 untracked PNGs in the source tree.

## Batch Generation

`generate_screenshots.sh` works around a layoutlib memory leak (rendered images accumulate without
being released) by:

1. Splitting the locales into batches of `--batch-size` (default 1 = 6 renders per batch).
2. Rewriting `PlayStoreLocales.kt` in place with only that batch's locales — **both** annotation
   classes — then running Gradle with `--no-daemon --rerun-tasks`. Without `--rerun-tasks` the
   update task reports up to date after the reference directory has been wiped and renders nothing.
3. Stopping the Gradle daemon between batches to release memory.
4. Restoring the original `PlayStoreLocales.kt` on exit, including on HUP/INT/TERM, through a single
   `mv` so the tracked file is never observed half-written.

The image count is checked after every batch and at the end; a mismatch fails the run.

The annotation file is generated **in place** rather than into a generated source root: the
checked-in `PlayStoreLocales.kt` declares the same annotation classes, so a second generated copy
would be a duplicate declaration.

Both scripts serialize through the same `flock`, on a lock file in `$TMPDIR` scoped to the checkout
(a `cksum` of the project directory, so worktrees do not block each other). The rewritten source,
the reference output directory and `gradlew --stop` are all shared state, and a copy racing a
generation would validate files that change before staging.

### Run manifest

`generate_screenshots.sh` writes `<reference dir>/screenshot-run.manifest` **only on success**,
listing the locales and shots that run produced. `copy_screenshots.sh` refuses to copy without it
and fails if the manifest and the tree disagree. Without it, an interrupted 37-locale run would be
indistinguishable from a complete 6-locale one and the copy would quietly publish a partial set.

## Screenshot Copy

`copy_screenshots.sh` runs in two phases and fails closed:

1. **Parse & validate** every source into an in-memory manifest. Unknown function names, duplicate
   destinations, a PNG whose pixel size is not `1440x2560`, a locale that does not have exactly 6
   shots, and any disagreement with the run manifest are all fatal. On any violation nothing is
   copied. `SCREEN_SIZE_OVERRIDE` lets a single shot render at a size other than its form factor's
   default; it is empty today.
2. **Stage & commit** — the complete replacement directories are built in a temp dir beside
   `fastlane/`, on the same filesystem, so each destination is replaced by a single rename. Every
   completed swap is rolled back if a later one fails. `--clean` replaces the touched locales' image
   directories instead of merging into them.

## Output Locations

- **Gradle reference images**:
  `manager/src/screenshotTestGplayDebug/reference/moe/shizuku/manager/screenshots/PlayStoreScreenshotsKt/*.png`
- **Fastlane metadata**: `fastlane/metadata/android/{locale}/images/phoneScreenshots/`

## Modifying Screenshots

### Adding or removing a screen

Everything below has to stay in sync:

1. `ScreenshotContent.kt`: a `<Name>Content()` composable that applies `PorterPreviewWrapper`
   exactly once, plus its mock state.
2. `ScreenshotContent.kt`: an IDE `@Preview` beside it, so the shot stays previewable in Android
   Studio without running Gradle.
3. `PlayStoreScreenshots.kt`: a `@PreviewTest` function under `@PlayStoreLocales` or
   `@PlayStoreLocalesDark`. **No underscores in the name.**
4. `PlayStoreLocales.kt`: only if a new theme axis is needed — a third annotation class means the
   generator's codegen has to emit it too.
5. `copy_screenshots.sh`: one `SCREEN_MAP` entry, `"phone:<order>_<label>"`.
6. `copy_screenshots.sh`: bump `SCREENS_PER_FORM_FACTOR[phone]`.
7. `generate_screenshots.sh`: bump `RENDERS_PER_LOCALE` to match.
8. `README.md`: the `## Screenshots` row of images.
9. This file: the count at the top and the shot order table.

Removing a screen is the same list in reverse.

### Changing mock data

Edit `ScreenshotContent.kt`. Keep the determinism rules above: nothing random, nothing date- or
time-zone-dependent.

### Adding or removing locales

Edit `fastlane/screenshots/locales.txt`. A new qualifier needs a matching `values-<qualifier>`
directory, otherwise the render silently falls back to English. Do not hand-edit
`PlayStoreLocales.kt` for a run; the generator rewrites it and restores it afterwards.
