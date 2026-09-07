# Public repository and website

Repository: `d4rken-org/porter`. Suggested GitHub About description:

> A minimal, maintained Shizuku fork that gives Android apps ADB access through the Shizuku APIs, with optional root support.

Headline: **Porter: ADB access for your apps**.

Suggested topics: `android`, `shizuku`, `adb`, `root`, `porter`.

The README and the main `docs/` pages target app users. `docs/developers.md` is the English guide for app developers. The Markdown guide is readable on GitHub and rendered with Jekyll for Pages. Technical records stay in `.github/maintainer/` and are not included in the website. `jekyll-relative-links` converts Markdown links to HTML links in the generated site.

## Preview

```sh
cd docs
bundle install
bundle exec jekyll serve --host 127.0.0.1
```

Open `http://127.0.0.1:4000/porter/`. For a static build, use `bundle exec jekyll build`. Dependencies are recorded in `Gemfile.lock`.

## First publication

Links to the planned repository and Pages site become usable after publication. App help destinations have been prepared for that site; publish the guide before distributing APKs containing those links.

1. Create/publish `d4rken-org/porter` and set its default branch to the branch containing the final Porter source.
2. Enable private vulnerability reporting in GitHub's security settings. Test the route linked in `SECURITY.md`.
3. In **Settings > Pages**, select **GitHub Actions** as the source. The `github-pages` environment should allow deployments only from the default branch.
4. Run **User guide** manually from the default branch. Pull requests build the guide but cannot deploy it. The workflow does not publish on push.
5. Confirm `https://d4rken-org.github.io/porter/` and the linked setup, compatibility, troubleshooting, terminal and developer pages work, including automatic German selection and the language dropdown. Set the GitHub About website field to the site address.
6. Publish the first production-signed release alongside the repository and guide. Public documentation is written for that launch and links directly to the downloads.

The Android workflow builds development artifacts only. User downloads belong in GitHub Releases after signing and release preparation.

## Later: porter.darken.eu

1. Verify domain ownership with GitHub using its requested DNS TXT record, then add a CNAME for `porter.darken.eu` pointing to `d4rken-org.github.io` (no `/porter` path).
2. Set the repository's Pages custom domain to `porter.darken.eu`, wait for DNS and certificate provisioning, and enable **Enforce HTTPS**.
3. Change `docs/_config.yml` to `url: https://porter.darken.eu` and `baseurl: ""`, then deploy the guide again. This Actions-based publication uses the Pages setting for the custom domain; a source `CNAME` file is not required.
4. Check the guide and GitHub Pages redirect, then update Porter's central help URL and the GitHub About website field to `https://porter.darken.eu`.

Do not configure the custom domain before the maintainer's DNS is ready. Stable page filenames and anchors let old links retain their destinations after the move.

## User-guide translations

The website groups its navigation into **For users** and **For developers**. The entire user guide is available in English and German: overview, setup, compatibility, troubleshooting and terminal use. The app integration guide stays in English.

Each guide has one URL in every language. English Markdown lives in `docs/`; German text lives in `docs/_translations/de/`. Jekyll embeds the matching translation in an inert HTML template inside the English page. The collection does not generate separate pages. JavaScript selects the text locally without fetching translations, redirecting or changing the URL. Without JavaScript the English guide remains readable.

The browser's first supported language selects the translation (regional variants such as `de-AT` match German). Unsupported languages fall back to English. The emoji-flag dropdown overrides detection and remembers the choice when local storage is available. **Automatic (browser)** clears that override. No language information is sent to a service. App help links use the same URLs for every locale; the browser controls the website language independently of the app's language.

Translations declare `lang`, `language_name` and a `translation_key` matching their English counterpart (`index`, `setup`, `compatibility`, `troubleshooting` or `terminal`). Keep all heading IDs identical so switching preserves the current topic. Use canonical relative `.html` links in translation Markdown, since these fragments render inside the canonical page. Update both languages whenever user instructions change.

To add a language, add all five translated Markdown files to `docs/_translations/<language>/` and a matching entry in `docs/_data/ui.yml` for navigation, language name and flag. The layout and browser detection discover the available translations automatically. No JavaScript or app URL changes are needed.
