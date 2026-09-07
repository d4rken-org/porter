# Starting Porter

Install Porter, then open it. Apps with native Porter support can use it alongside Shizuku. Choose the desired service in the client app. For SD Maid SE and Butler, the development integration adds this choice to General settings; applying a changed choice requires Force stop followed by reopening the client.

On Android 11 or newer, enable Developer options and Wireless debugging. Use Porter's Pairing action with the pairing code shown by Android, then Start. Rebooting generally requires starting the service again. Porter uses available ADB transports and does not restart or disable them when it stops.

For startup from a computer, enable USB debugging, authorize that computer, and run the exact ADB command displayed by the installed Porter app. Its native-library path changes when the APK is updated. Do not reuse a command copied from a different manager or an older installation.

On a rooted device, use Porter's root startup action and approve it in your root manager. Stop the active root service before switching to ADB mode.

Approve client apps in Porter's permission prompt. Manage and revoke these approvals from Porter's application-management screen. Porter and Shizuku have independent approvals.

For an app that only supports Shizuku, remove the original Shizuku and install the matching Porter Compatibility APK. Keep that companion installed while using legacy clients. The companion uses the original package and permission, so Android cannot install it alongside the original. Both Porter APKs must have matching signing certificates.

Some inherited help links lead to upstream Shizuku protocol or Android setup references. Use the package names, commands and integration instructions from Porter when applying those references.
