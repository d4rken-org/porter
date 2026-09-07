---
title: Install and start
lang: en
translation_key: setup
language_name: English
---
# Install and start Porter

Download Porter from [GitHub Releases](https://github.com/d4rken-org/porter/releases), then follow the steps below.

## Install

1. Download the Porter APK from the release's **Assets** section. Source-code ZIP and TAR files are not Android apps.
2. Open the APK on your device. If Android asks, allow your browser or file manager to install apps from this source.
3. Open Porter.

For apps that support Porter directly, Porter alone is enough. If an app only supports Shizuku, also install **Porter Compatibility** from the same release. Uninstall Shizuku first: the companion cannot be installed alongside it. Keep the companion installed while using those apps with Porter. See the [compatibility guide](compatibility.md) for more details.

## Choose how to start

| Your device | Startup method |
| --- | --- |
| Android 11 or newer with wireless debugging | [Wireless debugging](#wireless-debugging) |
| Android 7.0 or newer and a computer | [USB debugging](#with-a-computer) |
| Already rooted | [Root](#root) |

## Wireless debugging

You need a Wi-Fi connection and Android 11 or newer. Some device manufacturers restrict wireless debugging; if it is unavailable, use a computer.

1. Enable **Developer options** in Android Settings. Usually this means opening **About phone** and tapping **Build number** seven times. The location varies by device.
2. In Developer options, enable **USB debugging** and **Wireless debugging**. Accept Android's network authorization prompt if it appears.
3. In Porter, tap **Pairing** under **Start via Wireless debugging**. Allow notifications and, if requested, nearby-device or local-network access.
4. Open Android's **Wireless debugging** settings and tap **Pair device with pairing code**. Keep that dialog open.
5. Expand Porter's pairing notification and enter the code shown by Android. Wait for pairing to succeed.
6. Return to Porter and tap **Start** under **Start via Wireless debugging**.
7. Check that Porter says **Porter is running**.

Porter leaves Android's debugging settings enabled when it stops. You can turn them off in Developer options when you no longer need debugging access.

Pairing normally only needs to be done once. Starting the service is a separate step and is needed again after a device restart. If Android forgets the pairing, repeat these steps.

If you enabled **Legacy pairing** in Porter settings, wait for Porter's dialog to discover the pairing service, then enter the code there. If it asks for a port, use the pairing port from Android's pairing-code dialog, not the connection port on the main Wireless debugging screen.

## With a computer

1. Install Google's [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) on your computer.
2. Enable Developer options and **USB debugging** on the Android device.
3. Connect the device with a USB data cable. Unlock it and approve the debugging connection. Only authorize a computer you trust.
4. Open a terminal in the Platform-Tools folder and run `adb devices`. On Windows PowerShell, use `./adb.exe devices`; on macOS or Linux, use `./adb devices` if ADB is not on your PATH.
5. In Porter, find **Start by connecting to a computer** and tap **View command**. Run that exact command on your computer, adjusting the `adb` executable prefix as above if needed.
6. Check that Porter says **Porter is running**. You can disconnect the cable afterward.

If more than one device is connected, insert `-s DEVICE_SERIAL` immediately after `adb` in the displayed command. Use the serial shown by `adb devices` for the device running Porter.

Get a new command from Porter after updating or reinstalling it. The path can change. Do not use a startup command copied from Shizuku or another installation.

## Root

This method is for devices that already have working root access. Installing Porter does not root your device.

1. Open Porter and tap **Start** in its root startup section.
2. Approve Porter's request in your root manager.
3. Check that Porter says **Porter is running** and shows root as the startup mode.

Stop the current Porter service before switching between root and debugging access.

## Allow an app

Open the app you want to use, enable its Porter or Shizuku integration, then approve Porter's access prompt. Only approve apps you trust: they can perform tasks with Porter's debugging or root access.

To remove access, tap **Authorized applications** in Porter and turn off the app's authorization. Porter and Shizuku keep separate approvals.

If the app has a service selector, choose Porter there. Follow the app's own setup instructions. If changing the selection requires restarting the app, use **Force stop** in Android's app settings and reopen it.

## Update Porter

Install the newer APK over the existing app and start Porter again. If you use Porter Compatibility, update it from the same release. Android requires matching signatures for updates; see [installation problems](troubleshooting.md#android-wont-install-an-apk) if it refuses.
