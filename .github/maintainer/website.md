# Public repository and website

Repository: `d4rken-org/porter`. Suggested GitHub About description:

> Give your Android apps the access they need. An independent continuation of Shizuku, with optional compatibility for existing apps.

Suggested topics: `android`, `shizuku`, `adb`, `root`, `porter`.

The README and `docs/` target app users. The Markdown guide is readable on GitHub and rendered with Jekyll for Pages. Technical records stay in `.github/maintainer/` and are not included in the website. `jekyll-relative-links` converts Markdown links to HTML links in the generated site.

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
5. Confirm `https://d4rken-org.github.io/porter/` and the linked setup, compatibility, troubleshooting and terminal pages work. Set the GitHub About website field to that address.
6. Replace the development/no-public-release notices in the README and guide when production-signed APKs are actually available.

The Android workflow builds development artifacts only. User downloads belong in GitHub Releases after signing and release preparation.

## Later: porter.darken.eu

1. Verify domain ownership with GitHub using its requested DNS TXT record, then add a CNAME for `porter.darken.eu` pointing to `d4rken-org.github.io` (no `/porter` path).
2. Set the repository's Pages custom domain to `porter.darken.eu`, wait for DNS and certificate provisioning, and enable **Enforce HTTPS**.
3. Change `docs/_config.yml` to `url: https://porter.darken.eu` and `baseurl: ""`, then deploy the guide again. This Actions-based publication uses the Pages setting for the custom domain; a source `CNAME` file is not required.
4. Check the guide and GitHub Pages redirect, then update Porter's central help URL and the GitHub About website field to `https://porter.darken.eu`.

Do not configure the custom domain before the maintainer's DNS is ready. Stable page filenames and anchors let old links retain their destinations after the move.
