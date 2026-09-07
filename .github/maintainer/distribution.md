# Distribution

Initial distribution is through GitHub Releases. The public repository will be `d4rken-org/porter`. Final artwork and production signing remain maintainer decisions. No remote publication is performed by the development build.

Manager and companion APKs must use the same current signing certificate. A key rotation must update both APKs together. Keep the production keystore outside the repository, with secure backups. Supply its path through an ignored `signing.properties`:

```properties
KEYSTORE_FILE=/absolute/path/to/porter.jks
KEYSTORE_PASSWORD=...
KEYSTORE_ALIAS=...
KEYSTORE_ALIAS_PASSWORD=...
```

Release version name and monotonically increasing version code are defined in the root build. The server protocol version stays independent of Porter's product version.

Release builds without a signing configuration fail. For local release-mode testing only, `-Pporter.developmentSigning=true` explicitly permits a development certificate. Never publish those APKs.

CI should build the manager and companion from the app tag, including the adapter from its pinned API submodule, verify their manifests and certificates, and attach APK checksums and build provenance. Attestation establishes build provenance; it is not a claim of byte-for-byte reproducibility.

Write release notes for users: describe the changes they will notice, identify the Porter and optional Porter Compatibility downloads, link the startup guide, and state any tested-version limits or known problems. Include SHA-256 checksums for the APKs and their signing-certificate fingerprint from `tools/verify-apks.py`. Do not attach probe apps or development-signed APKs to public releases.

For future Google Play distribution, provide the existing app-signing key to Play App Signing if updates should remain interchangeable with GitHub builds. The upload key and app-signing key are different roles. A Play build must not update itself through GitHub.

Upstream's README reserves the original Shizuku package and permission identifiers under its stated trademark policy. The optional companion uses those identifiers for interoperability; the standalone app's rename does not remove that issue from the companion. Preserve upstream attribution and this record when preparing distribution.
