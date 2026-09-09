---
title: App compatibility
lang: en
translation_key: compatibility
language_name: English
---
# App compatibility

Porter supports the Shizuku APIs used by compatible apps. Whether you need the optional companion depends on how the app connects.

| What your app supports | What to install |
| --- | --- |
| Porter directly | Porter |
| Shizuku only | Porter and Porter Compatibility; remove Shizuku first |
| Both, with a service selector | Porter, then select Porter in the app |

The companion helps existing Shizuku apps find Porter. Porter still starts the service, shows permission prompts and manages approvals. Keep the companion installed while using apps that need it.

## Switch from Shizuku

For an app with direct Porter support, you can keep Shizuku installed. Start Porter, choose it in the app and approve the new request. If the app says a restart is needed, force-stop it in Android Settings and reopen it.

For an app that only supports Shizuku, the FOSS build includes the matching Porter Compatibility APK:

1. Install and start Porter. Keep Shizuku installed until you have reviewed the replacement.
2. Open **Shizuku compatibility** from the home screen or **Settings**.
3. If Shizuku is installed, choose **Replace**. Confirm **Switch to Porter**. Porter stops Shizuku, replaces its app and carries over eligible access choices automatically. If Porter cannot stop the service, it asks you to stop it in Shizuku and retry.
4. Otherwise, choose **Install automatically**.
5. Return to the client app. Approve access if you did not import an existing decision. If the client still cannot connect, force-stop it in Android Settings and reopen it.

Porter automatically imports decisions that can be checked against installed apps and their current access. Existing Porter decisions take precedence. Shizuku's app settings and pairing configuration are not imported. If the access database cannot be read, you can continue and approve apps again. Porter saves eligible access choices before removing Shizuku; if installation fails, retry or use **Manual**, then **Import saved approvals**.

Integrated replacement is available from the primary Android user. If Shizuku is installed for another user or profile, resolve that installation separately. Porter does not remove another user's apps automatically.

The companion APK remains available as a separate download from the same release. For manual installation, stop and uninstall Shizuku, install Porter Compatibility, and start Porter. Use Porter's integrated replacement to transfer eligible access decisions; opening the replacement dialog alone does not save an import. Device installation restrictions can also apply to the integrated installer; the **Manual** action opens the Android installer.

Once installed, the home screen shows the compatibility app version and how many installed apps connect through it. Tap its card to see details, reinstall the included copy, or uninstall it. Porter tries to uninstall through its service first and opens the Android uninstaller if that fails. Removing it interrupts apps that need compatibility support; direct Porter apps keep working. The compatibility screen checks for changes automatically while open.

## Can Porter and Shizuku run together?

Yes. Porter and Shizuku can both be installed and running. An app that supports choosing between them connects to one service at a time.

**Porter Compatibility cannot be installed alongside Shizuku.** It uses Shizuku's Android app identity to support older apps. Android treats them as competing installations, not as two separate apps. This also applies to forks using that same identity.

To switch back, remove Porter Compatibility and reinstall Shizuku. Restart the client app and approve its access in Shizuku. You can keep standalone Porter installed.

## An app still asks for Shizuku

The name in an older app's settings may continue to say Shizuku even when Porter provides access. That is expected.

The companion covers common Shizuku discovery methods. Apps that depend on specific Shizuku screens, internal components or much older APIs may need an update. If an app cannot connect, include its name and version in a [Porter issue](https://github.com/d4rken-org/porter/issues).
