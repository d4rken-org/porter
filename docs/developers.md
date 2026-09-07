---
title: For app developers
---
# Add direct Porter support

If your Android app already uses Shizuku, you can add direct Porter support while keeping the same Shizuku SDK calls. Your users can then use Porter without installing Porter Compatibility. Contributions and integrations from other app developers are welcome.

The integration has three parts: declare Porter's permission, accept its service connection through a provider, and select one service for each app process. Adding the permission alone is not enough.

## 1. Include the adapter

Keep these dependencies; they are the versions used with the current adapter:

```kotlin
implementation("dev.rikka.shizuku:api:13.1.5")
implementation("dev.rikka.shizuku:provider:13.1.5")
```

Copy the three Java files from [Porter's client adapter](https://github.com/d4rken-org/porter/tree/HEAD/client/src/main/java/eu/darken/porter/client) into your app's Java source tree, keeping their `eu.darken.porter.client` package:

- `PorterClient.java`
- `PorterProvider.java`
- `SelectedShizukuProvider.java`

Record the Porter commit you copied so you can track adapter updates. Preserve its Apache 2.0 license and attribution. There is no published Porter adapter artifact yet; the source URL becomes available when the repository is published.

Include the adapter in one module only. Copying the same classes into multiple libraries can cause duplicate-class build errors.

Alternatively, include the repository's `client` Android library module in your build. That module already declares the two SDK dependencies and the Porter permission, but you still need to declare the providers below.

## 2. Update your manifest

Replace your app's existing `rikka.shizuku.ShizukuProvider` declaration with the two providers below. Keep your other application entries and existing package-visibility rules. This example supports both Porter and Shizuku:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="eu.darken.porter.permission.API_V23" />
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

    <queries>
        <package android:name="eu.darken.porter" />
        <package android:name="moe.shizuku.privileged.api" />
    </queries>

    <application>
        <!-- Remove an old provider if another library contributes it. -->
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            tools:node="remove" />
        <provider
            android:name="eu.darken.porter.client.PorterProvider"
            android:authorities="${applicationId}.porter"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
        <provider
            android:name="eu.darken.porter.client.SelectedShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:exported="true"
            android:multiprocess="false"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
    </application>
</manifest>
```

Porter delivers its Binder connection to `${applicationId}.porter`. The gated Shizuku provider keeps your existing Shizuku connection path and the SDK's internal cross-process Binder lookup. Keep the `.shizuku` authority even when the selected service is Porter.

`INTERACT_ACROSS_USERS_FULL` protects provider delivery; it is not a permission your client app should request. Inspect the merged manifest to confirm that the original ungated provider is gone and there is only one provider for each authority.

Keep the SDK's `moe.shizuku.client.V3_SUPPORT` metadata in the merged manifest. Historical Shizuku names in SDK metadata, Binder interfaces and callbacks remain part of the compatible protocol and should not be renamed.

The package queries make the known managers visible for installation checks and launch intents on Android 11 and newer. Retain the discovery and visibility handling for any other managers your app already supports; do not add `QUERY_ALL_PACKAGES` solely for Porter.

## 3. Select a service

The adapter defaults to **Automatic**. At the first selection in a process, it prefers an installed Porter; otherwise it selects Shizuku. It does not check which server is running. If Porter is installed but stopped, Automatic will wait for Porter rather than silently connect to Shizuku.

Read the active service with:

```java
import eu.darken.porter.client.PorterClient;

PorterClient.Backend backend = PorterClient.getActiveBackend(context);
```

If your settings offer Automatic, Porter and Shizuku, save the choice with:

```java
// Run off the main thread: this writes preferences synchronously.
boolean saved = PorterClient.setBackendForNextProcess(
        context, PorterClient.Backend.PORTER);
```

Use `Backend.AUTO` or `Backend.SHIZUKU` for the other choices. Check `saved` before telling the user the setting was saved.

The active choice stays fixed for the life of the process. Ask the user to **Force stop** your app in Android Settings and reopen it after changing the setting. An activity recreation is not sufficient. Do not disconnect ongoing work or change the Shizuku singleton to apply the preference immediately.

Providers initialize before `Application.onCreate()`. Calling the setter there does not select a different service for an already initialized process. A custom default must be chosen before provider initialization, for example in your included adapter's preference default, rather than by a late call to the setter.

## 4. Keep your existing Shizuku calls

Continue using your existing Binder-received and Binder-dead listeners, `Shizuku.checkSelfPermission()`, `Shizuku.requestPermission()`, Binder wrappers and user-service APIs. Request access only after a connection is available, and handle connection loss as you already do for Shizuku.

When Porter is selected, these calls use Porter and its approval prompt. Receiving a Binder does not itself grant access. Avoid checking only the old Shizuku runtime permission or package name to decide whether the user is authorized.

Check the live connection with `Shizuku.pingBinder()` before relying on a permission result. Do not treat a previous approval or a cached SDK result as proof that a new connection is authorized.

For manager discovery, `PorterClient.getPorterPackage(context)` returns the package declaring `eu.darken.porter.permission.API_V23`, or `null` if it is unavailable. Use it only for the selected Porter backend. When Shizuku is selected, use your existing Shizuku discovery logic. Name and open the selected manager in your setup UI; do not open a different manager as a fallback.

An installed permission owner does not mean the server is running. Also handle a saved explicit selection restored from backup onto a device without that manager: explain which app is missing and let the user install it or change the selection.

If your app also initializes Sui directly, do not call `Sui.init()` while Porter is selected. The adapter disables the provider's automatic Sui initialization in that case, but cannot guard an explicit call in your own code.

## Multiple app processes

Every process using the SDK must agree on the selected service. Keep the providers in the same process arrangement as your existing Shizuku integration and preserve the SDK's cross-process initialization. The `.shizuku` provider still allows the SDK's internal `getBinder` call when Porter is selected.

The adapter stores preferences with SharedPreferences, which does not provide live cross-process synchronization. Force-stop all app processes after changing the choice; do not attempt a running-process handover. Apps with their own preference storage should make a consistent choice before any process initializes its providers.

## Supporting only Porter

If you intentionally drop Shizuku support, remove its permission contributed by the SDK with this manifest entry:

```xml
<uses-permission
    android:name="moe.shizuku.manager.permission.API_V23"
    tools:node="remove" />
```

Keep both gated providers: the SDK still uses `.shizuku` for internal cross-process Binder lookup. In your copied adapter, make `getPreferredBackend()` return Porter unconditionally and do not expose the other choices:

```java
public static Backend getPreferredBackend(Context context) {
    return Backend.PORTER;
}
```

This selects Porter before provider initialization and ignores any older saved backend choice. Leaving the adapter on Automatic would select Shizuku when Porter is missing, which is inappropriate for a Porter-only app. Show installation guidance for Porter instead.

## Verify your integration

Check these cases before releasing your app:

- Porter installed and running, without Porter Compatibility: approval, a real privileged operation and your user service work.
- Shizuku selected: your existing integration still works.
- Both services running: only the selected service receives requests.
- Selected service stopped or missing: the app explains how to start or install that service and does not silently switch.
- Access denied or revoked: privileged work stops and your UI reflects the missing access.
- Service restart and app restart: the connection recovers without using stale Binder references.
- Backend selection changed: it takes effect only after a full app restart, including any secondary processes.

Link users to the [setup guide](setup.md), also available in [German](de/setup.md). Keep instructions for your app's settings and the app versions that support Porter in your own documentation.
