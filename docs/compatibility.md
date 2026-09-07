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

For an app that only supports Shizuku:

1. Stop Shizuku and uninstall its manager app. Apps you use with Shizuku can stay installed.
2. Install Porter and the Porter Compatibility APK from the same release.
3. Start Porter.
4. Force-stop the client app in Android Settings, reopen it and enable its Shizuku integration.
5. Approve the access request shown by Porter.

Previous Shizuku approvals are not transferred. You choose which apps may use Porter again.

## Can Porter and Shizuku run together?

Yes. Porter and Shizuku can both be installed and running. An app that supports choosing between them connects to one service at a time.

**Porter Compatibility cannot be installed alongside Shizuku.** It uses Shizuku's Android app identity to support older apps. Android treats them as competing installations, not as two separate apps. This also applies to forks using that same identity.

To switch back, remove Porter Compatibility and reinstall Shizuku. Restart the client app and approve its access in Shizuku. You can keep standalone Porter installed.

## An app still asks for Shizuku

The name in an older app's settings may continue to say Shizuku even when Porter provides access. That is expected.

The companion covers common Shizuku discovery methods. Apps that depend on specific Shizuku screens, internal components or much older APIs may need an update. If an app cannot connect, include its name and version in a [Porter issue](https://github.com/d4rken-org/porter/issues).
