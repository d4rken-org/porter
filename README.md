# Porter

**ADB access for your apps.**

Porter is a minimal, maintained fork of [Shizuku](https://github.com/thedjchi/Shizuku) that gives Android apps ADB access through the Shizuku APIs, with optional root support.

Use it with compatible apps to manage other apps and access additional files. You choose which apps get access.

## Get started

1. Install Porter from [GitHub Releases](https://github.com/d4rken-org/porter/releases).
2. [Start Porter](docs/setup.md) using wireless debugging, a computer, or root.
3. Open an app that supports Porter and approve its access request.

Porter requires Android 7.0 or newer. Starting without a computer requires Android 11 or newer with wireless debugging, or an already rooted device. After restarting your device, start Porter again before using apps that rely on it.

## Using apps that support Shizuku

Apps with built-in Porter support only need Porter. For apps that only support Shizuku, install the optional **Porter Compatibility** APK from the same release.

**Porter Compatibility replaces the installed Shizuku app.** Remove Shizuku before installing it. Porter itself can be installed alongside Shizuku, but the compatibility companion cannot.

[Choose the right setup for your apps](docs/compatibility.md).

## For users

Read the [Porter user guide](https://d4rken-org.github.io/porter/), available in English and German with automatic language selection. You can also read the guides in this repository:

- [Installation and startup](docs/setup.md)
- [App compatibility and switching from Shizuku](docs/compatibility.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Terminal apps](docs/terminal.md)
- [Report a problem](https://github.com/d4rken-org/porter/issues)

Include your Porter version, device, Android version and the name of the app you are trying to use. For security issues, see [security reports](SECURITY.md).

## Why Porter exists

I use Shizuku in my own apps, [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) and [Butler](https://github.com/d4rken-org/butler). I started Porter because I wanted a minimal, stable and maintained Shizuku alternative that gives apps ADB access.

## For developers

Other app developers are welcome to support Porter's permission directly. [Add direct Porter support to your app](docs/developers.md).

## About

Porter builds on [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and the maintenance work by [thedjchi and contributors](https://github.com/thedjchi/Shizuku). It is not affiliated with the original Shizuku maintainers.

Porter's code is available under [Apache 2.0](LICENSE); the bundled Shizuku API uses the MIT license. See [NOTICE](NOTICE) for attribution. Build and integration notes are in the [maintainer documentation](.github/maintainer/README.md).
