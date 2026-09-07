# Distribution

Initial distribution is through GitHub Releases. The public repository is `d4rken-org/porter`. Final artwork and production signing remain maintainer decisions. No remote publication is performed by the development build.

Manager and companion APKs must use the same current signing certificate. A key rotation must update both APKs together. Keep the production keystore outside the repository, with secure backups. Supply its path through an ignored `signing.properties`:

```properties
KEYSTORE_FILE=/absolute/path/to/porter.jks
KEYSTORE_PASSWORD=...
KEYSTORE_ALIAS=...
KEYSTORE_ALIAS_PASSWORD=...
```

Release version name and monotonically increasing version code are defined in `version.properties` and checked against `VERSION` by `tools/release/bump.sh`. The server protocol version stays independent of Porter's product version.

The initial review version is `0.1.0-beta0`. Release builds without a signing configuration fail. For local release-mode testing only, `-Pporter.developmentSigning=true` explicitly permits a development certificate. Never publish those APKs.

Use **Release prepare** on `main` to plan a version bump. It defaults to a dry run, waits for the Development build on the selected commit, and checks that main has not moved. For an already reviewed, unpublished version, select `release_current=true` to tag the checked-in version without a bump. With `dry_run=false`, the release GitHub App commits the version files and atomically pushes main and its `v*` tag. **Tagged releases** then builds the manager and companion from that tag, verifies their identities and matching certificates, uploads all downloads to a draft, and publishes it. Beta tags are marked as prereleases. No Google Play upload runs.

For emulator review, dispatch **Tagged releases** on `main` with `dry_run=true`. It builds production-signed APKs as public Actions artifacts retained for 14 days, without creating a tag or GitHub release. The first app release must wait for the maintainer's emulator review.

CI uses the `foss-production` environment secrets `SIGNING_KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. The workflow decodes the keystore into a temporary directory, passes `STORE_PATH` to Gradle, and removes the keystore afterwards. The release App uses `RELEASE_APP_CLIENT_ID` and `RELEASE_APP_PRIVATE_KEY`.

Downloads include `SHA256SUMS`, certificate verification output, and `build-info.json` identifying the source commits and workflow run. This metadata is not a reproducibility claim or a cryptographic attestation.

Write release notes for users: describe the changes they will notice, identify the Porter and optional Porter Compatibility downloads, link the startup guide, and state any tested-version limits or known problems. Include SHA-256 checksums for the APKs and their signing-certificate fingerprint from `tools/verify-apks.py`. Do not attach probe apps or development-signed APKs to public releases.

For future Google Play distribution, provide the existing app-signing key to Play App Signing if updates should remain interchangeable with GitHub builds. The upload key and app-signing key are different roles. A Play build must not update itself through GitHub.

Upstream's README reserves the original Shizuku package and permission identifiers under its stated trademark policy. The optional companion uses those identifiers for interoperability; the standalone app's rename does not remove that issue from the companion. Preserve upstream attribution and this record when preparing distribution.
