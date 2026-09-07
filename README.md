# Porter

Porter lets Android apps use system APIs with ADB or root privileges. It is an independent maintenance fork of [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku), based on stable `v13.7.0-thedjchi` (`608fcf0e49d9`), which derives from [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku).

This is a development baseline. The artwork is temporary. A public release destination and production signing key have not been configured.

## Apps

* **Porter**, `eu.darken.porter`, serves apps supporting its permission and provider endpoint. It can be installed alongside Shizuku.
* **Porter Compatibility**, `moe.shizuku.privileged.api`, is an optional companion for existing Shizuku clients. It requires Porter signed with the same key. Remove the original Shizuku before installing the companion: they use the same package and legacy permission.

Porter has its own server process and authorization database. Client apps choose which backend to use. Both backends can run at once, but one client process connects to one backend. Changing the choice requires restarting that client app.

The companion does not run a privileged server or make authorization decisions. Porter grants access. Keep it installed while using apps that depend on the original permission.

## Development

```sh
git submodule update --init --recursive
./gradlew :manager:assembleDebug :compat:assembleDebug
```

Use Java 21 and the Android SDK/NDK/CMake versions pinned by the build. Set `ANDROID_HOME` or `sdk.dir` in an ignored `local.properties`. The API submodule is pinned to `37ebcd3e45edf1d68975c70dd199350b434161f7`; do not update it implicitly.

APKs are written to `manager/build/outputs/apk/debug/` and `compat/build/outputs/apk/debug/`. Debug builds use development signing. A release requires an ignored `signing.properties`; see [distribution](docs/distribution.md).

See [setup](docs/setup.md), [client integration](docs/client-integration.md), [design](docs/design.md) and [development validation](docs/validation.md). The `probe` module builds Porter-aware and unchanged legacy SDK clients for device checks. It is not distributed as a user app.

## License and attribution

The code is licensed under [Apache 2.0](LICENSE). Original copyrights remain with their authors. Porter modifies application identity, routing, authorization and service lifecycle, and supplies new artwork. See [NOTICE](NOTICE).

The optional compatibility companion deliberately declares the original application ID and API permission for interoperability. Upstream's README reserves those identifiers under its stated trademark policy; renaming the main app does not remove that issue from the companion. Porter is not affiliated with the original Shizuku maintainers.
