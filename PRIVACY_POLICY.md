---
title: Privacy policy
permalink: /privacy
---

# Privacy policy

This is the privacy policy for the Android app "Porter" and its optional "Porter Compatibility" companion by Matthias Urhahn (darken).

## Preamble

I do not collect, share or sell personal information.

My underlying privacy principle is the [Golden Rule](https://en.wikipedia.org/wiki/Golden_Rule).

Send a [quick mail](mailto:support@darken.eu) if you have questions.

## Permissions

Details about sensitive permissions can be found below.

In general, Porter only processes data locally, on your device. It contains no analytics or crash reporting service.
If you record a [debug log](#debug-log), the resulting file will contain a detailed log of Porter's actions.

### Network access

Porter starts its service over ADB. The `INTERNET` and local network permissions let Porter connect to your own device's
ADB daemon, either over the device's loopback interface or over wireless debugging on your local network, and find the
wireless debugging port through local network discovery (mDNS). These connections stay on your device or your local
network.

### Accessibility service

On devices where the pairing notification cannot be used, such as Android TV, Porter offers an optional accessibility
service. It only reads the system's wireless debugging pairing screen to pick up the pairing code and disables itself
once the code is found. Porter does not use the AccessibilityService API to collect or send information.

### Install apps

The GitHub version of Porter can install and uninstall its bundled compatibility companion (`REQUEST_INSTALL_PACKAGES`,
`REQUEST_DELETE_PACKAGES`). The system installer performs the installation and asks for your confirmation.

## Apps using Porter

Porter's service remembers which apps requested access, your decision for each of them, and when each app last
connected. This information is stored on your device and not sent anywhere.

## Debug log

The app has a debug log feature that can be used to assist troubleshooting efforts.
This feature creates a log file that contains verbose output of what the app is doing.

It is manually triggered by the user through an option in the app's support screen.
The recorded log file can be shared through compatible apps (e.g. your email app) using the system's share dialog.
As this log file may contain sensitive information (e.g. details about your device or the apps that use Porter) it should only be shared with trusted parties.
