---
title: Troubleshooting
lang: en
translation_key: troubleshooting
language_name: English
---
# Troubleshooting

## Porter is not running

Starting Porter again after a device restart is normal when using debugging access. Open Porter and use your [startup method](/setup). Pairing with wireless debugging does not itself start the service.

## Automatic start does not work

Start Porter manually once after installation before relying on **Start on boot**. A successful debugging start grants the Android setting permission needed for later automatic starts. If a notification mentions `WRITE_SECURE_SETTINGS`, start Porter again using the computer method, then retry.

Automatic start still depends on Android making debugging access available and allowing Porter to run in the background. If it fails, use a manual startup method.

## Wireless pairing does not finish

- Keep the device connected to Wi-Fi and check that Wireless debugging is enabled.
- Allow Porter notifications so you can enter the pairing code. Also allow nearby-device or local-network access when Android requests it.
- Keep Android's pairing-code dialog open while entering the code in Porter's notification. If the code expires, open a new pairing dialog.
- If entering the pairing code in Porter's in-app dialog, copy the pairing port from the pairing-code dialog, not the connection port on the main Wireless debugging screen.
- If a VPN or local-network restriction blocks discovery, try a network where device communication is allowed.

If a code was rejected, choose **Retry** in the notification. If the notification is missing or still shows an old code, return to **Pairing** in Porter and choose **Restart pairing**. Close Android's old pairing-code dialog and open a new one, then enter the new code. You do not need to clear Porter's cache or app data.

On Android TV, Porter shows a result when pairing succeeds, fails or times out. Choose **Retry** after a failure; enable the pairing accessibility service again if asked. If Porter does not open automatically, open it to see the saved result. Pairing success is followed by a separate **Start** step.

New pairings appear as **Porter** in Android's paired-device list. An existing entry may still say **shizuku** until you forget that entry and pair again. Renaming the display label does not require replacing the saved key, and an existing pairing can keep working. This label is separate from the Shizuku API badges in Porter's app list.

When wireless debugging is unavailable or unreliable on your device, [start from a computer](/setup#with-a-computer).

## The computer cannot find the device

Run `adb devices`. If it says `unauthorized`, unlock the device and approve the debugging prompt. If nothing appears, check USB debugging, try a USB data cable and another USB port, and check whether your computer needs the manufacturer's USB driver.

If the startup command says a file is missing, copy a fresh command from **View command** in the installed Porter app.

## Porter keeps stopping

First check whether the device restarted or Android disabled debugging. Start Porter again if necessary.

If it stops while the device stays on, check the manufacturer's battery and background-app settings for Porter. Allow background operation if those settings are restricting it. Network changes and manufacturer modifications to Android can affect debugging access.

Report recurring stops with the device model, Android version, startup method and what happened just before Porter stopped.

## An app cannot connect

1. Confirm that Porter says **Porter is running**.
2. Check [whether the app needs Porter Compatibility](/compatibility).
3. If the app has a service selector, choose Porter, force-stop the app in Android Settings and reopen it.
4. Enable the integration inside the app and approve the Porter prompt.
5. Check the app under Porter's **Applications** and make sure **Allow app access** is on.

If you use the companion, both Porter APKs must come from the same release source and have matching signing certificates. Removing the companion prevents older clients from using Porter.

## Android won't install an APK

If you are installing **Porter Compatibility**, remove Shizuku first. The companion cannot update a differently signed Shizuku installation, even though Android sees the same app identity.

For Porter itself, an older development build may have a different signature from a public release. Android will not install one over the other. Removing the old installation also removes its app data; record your setup before doing so. Reinstall from the intended source and approve your apps again.

## A TV installation warning cannot be selected with the remote

The Play Protect warning and its **More details** or **Install anyway** controls belong to Android's installer. Porter cannot change their remote-control focus behavior, and the warning alone does not tell us why Google flagged an APK.

Check that the APK came from the [Porter release page](https://github.com/d4rken-org/porter/releases). If you decide to continue after reading the warning, a USB or Bluetooth mouse may let you select its controls. If you already have an authorized ADB connection to the TV, you can also try installing the downloaded APK from that computer:

```sh
adb -s TV_SERIAL install -r /path/to/porter-compat.apk
```

Replace `TV_SERIAL` with the TV's entry from `adb devices` and use the actual downloaded APK path. Android may still block installation or require confirmation. This does not fix Play Protect's controls or guarantee that installation is allowed.

If installation remains blocked, report the exact warning text, TV model, Android version and Porter Compatibility version. Apps with direct Porter support do not need the compatibility APK.

## Access is allowed, but an operation still fails

Debugging access is more limited than root. Android versions and manufacturers impose additional restrictions, and Porter cannot make every app operation available. Check the client app's own requirements too.

On Xiaomi/POCO devices with MIUI, Developer options may have a separate **USB debugging (Security settings)** switch. Enabling ordinary USB debugging alone may leave app-management operations restricted. Enable the additional setting if you want those operations, then restart Porter. The setting's name and availability vary by system version.

Some OPPO/OnePlus systems have a **Permission monitoring** switch in Developer options that restricts debugging access. Turning it off may allow the operation, but changes the manufacturer's protection setting. These manufacturer-specific workarounds come from the [upstream Shizuku guide](https://shizuku.rikka.app/guide/setup/); they have not yet been verified with Porter on physical devices.

## The version shown while running is different

The **Version** entry in **Settings** shows the Porter app version. Tap **Porter is running** to see the installed app version, running Porter service version and compatible Shizuku API version. The API version describes compatibility, not Porter's release number. If Porter asks you to restart the service after an update, stop and start it again.

## Report a problem

Tap the settings icon in Porter, then open **Help & support**. You can contact support by email, visit the [Discord community](https://discord.gg/5hXXgwKNgm), or [open an issue](https://github.com/d4rken-org/porter/issues).

To include a debug log, choose **Record debug log**, reproduce the problem, then **Stop recording**. Select the saved recording in the contact form or share it from **Saved debug logs**. Logs stay on your device until you share them. They may contain app names, device details and actions performed through Porter.

Include:

- Porter version and whether Porter Compatibility is installed.
- Device model and Android version.
- How you started Porter: wireless debugging, computer or root.
- The affected app and its version.
- What you did, what you expected and what happened.

Check screenshots and logs for personal information before attaching them. Do not include wireless pairing codes or private keys.
