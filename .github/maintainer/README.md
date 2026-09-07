# Maintainer notes

Public installation and usage instructions live in [docs](../../docs/index.md). These notes cover source maintenance and release preparation.

## Build

Use Java 21 and the Android SDK, NDK and CMake versions pinned by the Gradle build. Configure `ANDROID_HOME` or `sdk.dir` in an ignored `local.properties`.

```sh
git clone --recurse-submodules https://github.com/d4rken-org/porter.git
cd porter
./gradlew :manager:assembleDebug :compat:assembleDebug
```

For an existing checkout, run `git submodule update --init --recursive`. Debug APKs are written under each module's `build/outputs/apk/debug/` directory. They use development signing and are not public release artifacts.

## Reference

- [Service and compatibility design](design.md)
- [Client integration](client-integration.md)
- [Distribution and signing](distribution.md)
- [Validation record](validation.md)
- [API source dependency](api.md)
- [Website and repository setup](website.md)
- [Artwork and replacement procedure](artwork.md)

The `probe` module supplies test clients; it is not a user app. The `client` module contains the adapter for direct Porter support. Until an artifact is published, consumers that copy its source need to keep their copies synchronized.
