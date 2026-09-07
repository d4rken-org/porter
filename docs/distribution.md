# Distribution

Initial distribution is through GitHub Releases. Repository ownership, final artwork and production signing remain maintainer decisions. No remote publication is performed by the development build.

Manager and companion APKs must use the same current signing certificate. A key rotation must update both APKs together. Keep the production keystore outside the repository, with secure backups. Supply its path through an ignored `signing.properties`:

```properties
KEYSTORE_FILE=/absolute/path/to/porter.jks
KEYSTORE_PASSWORD=...
KEYSTORE_ALIAS=...
KEYSTORE_ALIAS_PASSWORD=...
```

Release version name and monotonically increasing version code are defined in the root build. The server protocol version stays independent of Porter's product version.

Release builds without a signing configuration fail. For local release-mode testing only, `-Pporter.developmentSigning=true` explicitly permits a development certificate. Never publish those APKs.

CI should build the manager, companion and adapter from the same source tag, verify their manifests and certificates, and attach APK checksums and build provenance. Attestation establishes build provenance; it is not a claim of byte-for-byte reproducibility.

For future Google Play distribution, provide the existing app-signing key to Play App Signing if updates should remain interchangeable with GitHub builds. The upload key and app-signing key are different roles. A Play build must not update itself through GitHub.
