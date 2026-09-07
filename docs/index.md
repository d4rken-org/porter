---
title: Porter
lang: en
translation_key: index
language_name: English
---
# ADB access for your apps

Porter is a minimal, maintained fork of [Shizuku](https://github.com/thedjchi/Shizuku) that gives Android apps ADB access through the Shizuku APIs, with optional root support.

You choose which apps are allowed to use it.

## For users

1. Get Porter from [GitHub Releases](https://github.com/d4rken-org/porter/releases).
2. Follow the [installation and startup guide](setup.md).
3. Open your supported app and approve its Porter access request.

Android 7.0 or newer is required. On Android 11 or newer, wireless debugging lets you start Porter without a computer. Older devices need a computer or root.

More help: [app compatibility](compatibility.md), [troubleshooting](troubleshooting.md) and [terminal apps](terminal.md).

## Already using Shizuku?

Porter has its own app identity and can run alongside Shizuku. Apps that only know about Shizuku need the optional Porter Compatibility companion, which replaces the installed Shizuku app.

[Find the right setup for your apps](compatibility.md).

## Why Porter exists

I use Shizuku in my own apps, [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) and [Butler](https://github.com/d4rken-org/butler). I started Porter because I wanted a minimal, stable and maintained Shizuku alternative that gives apps ADB access.

## For developers

Other app developers are welcome to support Porter's permission directly. [Add direct Porter support to your app](developers.md).

## Need help?

Start with [troubleshooting](troubleshooting.md), or [report a problem](https://github.com/d4rken-org/porter/issues).

Porter is an independent continuation of Shizuku, based on work by [RikkaApps](https://github.com/RikkaApps/Shizuku), [thedjchi](https://github.com/thedjchi/Shizuku) and their contributors.
