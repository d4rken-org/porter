# Porter

**Give your Android apps the access they need.**

Porter lets supported apps perform tasks that Android normally restricts, such as managing other apps or accessing additional files. You choose which apps get access. Root is optional.

Porter is an independent continuation of [Shizuku](https://github.com/thedjchi/Shizuku), with its own name and app identity.

> Porter is in development. There is no public Porter release yet.

## Get started

1. Install Porter from [GitHub Releases](https://github.com/d4rken-org/porter/releases) when a release is available.
2. [Start Porter](docs/setup.md) using wireless debugging, a computer, or root.
3. Open an app that supports Porter and approve its access request.

Porter requires Android 7.0 or newer. Starting without a computer requires Android 11 or newer with wireless debugging, or an already rooted device. After restarting your device, start Porter again before using apps that rely on it.

## Using apps that support Shizuku

Apps with built-in Porter support only need Porter. For apps that only support Shizuku, install the optional **Porter Compatibility** APK from the same release.

**Porter Compatibility replaces the installed Shizuku app.** Remove Shizuku before installing it. Porter itself can be installed alongside Shizuku, but the compatibility companion cannot.

[Choose the right setup](docs/compatibility.md), including SD Maid SE and Butler.

## Help

Read the [Porter user guide](https://d4rken-org.github.io/porter/) once the site is published, or use the guides in this repository:

- [Installation and startup](docs/setup.md)
- [App compatibility and switching from Shizuku](docs/compatibility.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Terminal apps](docs/terminal.md)
- [Report a problem](https://github.com/d4rken-org/porter/issues)

Include your Porter version, device, Android version and the name of the app you are trying to use. For security issues, see [security reports](SECURITY.md).

## About

Porter builds on [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and the maintenance work by [thedjchi and contributors](https://github.com/thedjchi/Shizuku). It is not affiliated with the original Shizuku maintainers.

Porter's code is available under [Apache 2.0](LICENSE); the bundled Shizuku API uses the MIT license. See [NOTICE](NOTICE) for attribution. Build and integration notes are in the [maintainer documentation](.github/maintainer/README.md).
