---
title: For app developers
---
# Add direct Porter support

If your Android app already uses Shizuku, you can add direct Porter support while keeping the same Shizuku SDK calls. Your users can then use Porter without installing Porter Compatibility. Contributions and integrations from other app developers are welcome.

The integration has three parts: declare Porter's permission, accept its service connection through a provider, and select one service for each app process. Adding the permission alone is not enough.

## 1. Add the Porter SDK

Add JitPack to your repositories in `settings.gradle.kts`, restricted to the Porter SDK group:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.d4rken-org.porter-api") }
        }
    }
}
```

Then add the client library:

```kotlin
implementation("com.github.d4rken-org.porter-api:client:0.1.0")
```

This includes Porter's adapter and maintained copies of the Shizuku-compatible API, provider, shared code and Binder interfaces. You do not need to copy any Java files. Source and releases are in [Porter API](https://github.com/d4rken-org/porter-api).

Remove your existing `dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider` dependencies, and any source copies of `eu.darken.porter.client`. The SDK preserves `rikka.shizuku.*` classes, so your existing imports and calls still work. Including both SDKs produces conflicting classes.

If another library brings in upstream SDK artifacts, exclude those dependencies from that library and use the Porter SDK instead:

```kotlin
implementation("some.library:using-shizuku:VERSION") {
    exclude(group = "dev.rikka.shizuku")
}
```

The published Gradle metadata declares conflicts with the equivalent upstream modules. Excluding upstream artifacts is still needed when they enter through another dependency. Verify that library works with the Porter SDK; the metadata does not establish compatibility by itself.

Use a fixed release tag such as `0.1.0`, not a moving branch or `-SNAPSHOT` version.

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

Providers initialize before `Application.onCreate()`. Calling the setter there does not select a different service for an already initialized process. For an app that supports only Porter, use the manifest configuration below so the choice applies before provider initialization.

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

Keep both gated providers: the SDK still uses `.shizuku` for internal cross-process Binder lookup. Add this metadata inside your app's `<application>` element:

```xml
<meta-data
    android:name="eu.darken.porter.client.PORTER_ONLY"
    android:value="true" />
```

Use a literal boolean `true`. This selects Porter before provider initialization, ignores any older saved backend choice and never falls back to Shizuku when Porter is missing. Both the metadata and removal of the legacy permission are required for this setup.

Do not offer a service selector in a Porter-only app. `PorterClient.isPorterOnly(context)` exposes this configuration; attempting to save Automatic or Shizuku in this mode throws `IllegalArgumentException`. Show installation guidance for Porter when it is missing.

## Verify your integration

Check these cases before releasing your app:

- Porter installed and running, without Porter Compatibility: approval, a real privileged operation and your user service work.
- Shizuku selected: your existing integration still works.
- Both services running: only the selected service receives requests.
- Selected service stopped or missing: the app explains how to start or install that service and does not silently switch.
- Access denied or revoked: privileged work stops and your UI reflects the missing access.
- Service restart and app restart: the connection recovers without using stale Binder references.
- Backend selection changed: it takes effect only after a full app restart, including any secondary processes.

Link users to the [setup guide](setup.md). It automatically selects English or German from the browser language, with a dropdown to override the choice. The URL is the same in both languages. Keep instructions for your app's settings and the app versions that support Porter in your own documentation.
