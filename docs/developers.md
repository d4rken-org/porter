---
title: For app developers
---
# Add direct Porter support

If your Android app already uses Shizuku, you can add direct Porter support while keeping the same Shizuku SDK calls. Your users can then use Porter without installing Porter Compatibility. Contributions and integrations from other app developers are welcome.

The integration has four parts: declare Porter's permission, accept its service connection through a provider, select one service for each app process, and name the right manager in your setup UI. Adding the permission alone is not enough.

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
implementation("com.github.d4rken-org.porter-api:client:0.2.0")
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

Use a fixed release tag such as `0.2.0`, not a moving branch or `-SNAPSHOT` version.

The SDK needs Android 7.0 (API 24) or newer. Keeping your `rikka.shizuku.*` imports does not keep your old minimum SDK.

### What a version promises

SDK release numbers are independent of the `13.1.5` Shizuku API baseline the compatible modules retain, and of Porter's own app version. While the SDK is `0.x`:

- A minor release can add API and can change adapter behaviour that the integration guide documents. Read the release notes before bumping.
- The Binder protocol, the `rikka.shizuku.*` class and method names, the provider authorities and the stored preference stay compatible across `0.x`. An app built against an earlier `0.x` keeps working with a newer Porter.
- Porter's server supports SDK releases from this repository. A newer SDK does not require a newer Porter unless a release note says so.

Fixes reach apps only through a new SDK release and a dependency bump in your build. There is no runtime update path for the client library.

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

There are two different values, and a settings screen needs both:

```java
import eu.darken.porter.client.PorterClient;

// What the user chose. Can be AUTO. Changes as soon as you save it.
PorterClient.Backend preferred = PorterClient.getPreferredBackend(context);

// What this process actually connects to. Never AUTO. Fixed until the process restarts.
PorterClient.Backend active = PorterClient.getActiveBackend(context);
```

Bind your radio buttons or list to `getPreferredBackend()`. Binding them to `getActiveBackend()` instead is the common mistake: the control can never show Automatic, and the selection will not move when the user taps it, because saving a choice deliberately leaves the running process alone.

Save with:

```java
// Run off the main thread: this writes preferences synchronously.
boolean saved = PorterClient.setBackendForNextProcess(
        context, PorterClient.Backend.PORTER);
```

Use `Backend.AUTO` or `Backend.SHIZUKU` for the other choices. Check `saved` before you move the selection in your UI or tell the user the setting was saved.

A complete selector, in outline:

```java
void onBackendPicked(PorterClient.Backend choice) {
    background(() -> {
        if (!PorterClient.setBackendForNextProcess(context, choice)) {
            showError();
            return;
        }
        onMainThread(() -> {
            // The control follows the preference.
            selected = PorterClient.getPreferredBackend(context);
            // What this process is routed to, which the save did not change.
            activeBackend = PorterClient.getActiveBackend(context);
            // Compare resolved values: picking Automatic can also change the target.
            restartRequired = PorterClient.resolve(context, selected) != activeBackend;
            render();
        });
    });
}
```

Show the two values as separate things. "Selected: Automatic" and "Using: Porter" are both true at once, and a user who sees only one of them cannot tell whether their change took effect.

`getActiveBackend()` is the service this process is routed to, not proof that anything is connected. If you label it as a connection, gate that on `Shizuku.pingBinder()`.

Providers initialize before `Application.onCreate()`. Calling the setter there does not select a different service for an already initialized process. For an app that supports only Porter, use the manifest configuration below so the choice applies before provider initialization.

### Applying a changed selection

The selection is fixed for the life of the process, so a new selection needs a new process. The server tracks Binder delivery per process, so an ordinary process death followed by the user reopening your app is enough to get a connection to the newly selected service. A force stop from Android Settings is a reliable way to reach that state, not a requirement of the protocol.

Before the process ends, stop your own privileged work:

- Unbind user services you own, with `unbindUserService(args, connection, true)`. Passing `false` only drops the connection and leaves the service running. `UserServiceArgs` sets `daemon` to `true` by default, so without this a restart can leave work running under the old service while new work starts under the new one.
- Implement the `destroy` method in your user service and call `System.exit()` from it. This is required in both modes: the server only sends the destroy transaction, it never kills the process. `daemon(false)` controls when that transaction is sent, not whether your process exits.
- Finish or cancel in-flight shell commands and Binder calls.

Then ask the user to reopen the app. If your app runs in several processes, direct them to **Force stop** in Android Settings, which is the only step that reliably ends all of them. Do not disconnect ongoing work or swap the Shizuku singleton to apply the preference to the running process.

## 4. Keep your existing Shizuku calls

Continue using your existing Binder-received and Binder-dead listeners, `Shizuku.checkSelfPermission()`, `Shizuku.requestPermission()`, Binder wrappers and user-service APIs. Request access only after a connection is available, and handle connection loss as you already do for Shizuku.

When Porter is selected, these calls use Porter and its approval prompt. Receiving a Binder does not itself grant access. Avoid checking only the old Shizuku runtime permission or package name to decide whether the user is authorized.

Check the live connection with `Shizuku.pingBinder()` before relying on a permission result. Do not treat a previous approval or a cached SDK result as proof that a new connection is authorized.

A successful ping is not enough on its own. The SDK drops a cached approval as soon as the connection changes, so it will not answer with the previous connection's grant. The other side of that is a gap: a new Binder becomes visible before the server has reported access for it, so during a reconnection `pingBinder()` can succeed while access still reads as not granted. Drive your UI from the Binder-received and Binder-dead listeners, re-check access when a new connection arrives, and let the privileged call itself fail rather than treating a ping or a cached result as a guarantee.

If your app also initializes Sui directly, do not call `Sui.init()` while Porter is selected. The adapter disables the provider's automatic Sui initialization in that case, but cannot guard an explicit call in your own code.

## 5. Identify the manager before you name it

Do not decide what to show the user with a package presence check. Porter Compatibility installs under Shizuku's own app identity, `moe.shizuku.privileged.api`, so `getPackageInfo("moe.shizuku.privileged.api", 0)` succeeds on a device that has no Shizuku at all. Ask the SDK instead:

```java
PorterClient.Manager manager = PorterClient.getManager(context, selectedBackend);
```

It resolves `AUTO` for you and returns one of:

| Result | Meaning | What to tell the user |
| --- | --- | --- |
| `PORTER` | The standalone Porter manager | Open Porter |
| `PORTER_COMPATIBILITY` | Porter Compatibility, holding Shizuku's identity | Select Porter in your app; this device has no Shizuku |
| `SHIZUKU` | Shizuku, or another app using its identity | Open Shizuku |
| `SUI` | Sui, provided by a Magisk module with no manager app | No app to open |
| `NONE` | No package provides this service | Offer to install |
| `UNKNOWN` | The package could not be identified | Do not name or launch it |

`UNKNOWN` covers both an unrecognized package owning the permission and a package this call could not inspect. Treat it the same way in either case: say a manager is present but unrecognized, and offer Porter.

`SUI` is reported only after Sui has initialized in the current process, and Sui does not initialize while Porter is the active backend. So a `NONE` answer about a pending Shizuku selection does not rule out Sui being available after the restart that applies it. Do not tell the user that nothing is installed on the strength of this call alone when they have asked for Shizuku.

`getPorterPackage(context)` and `getShizukuPackage(context)` return the owning package names when you need them. `getPorterPackage()` returns whichever package declares Porter's permission, which is not necessarily Porter, so check `getManager()` before you put that name in front of a user or send them to it.

Both calls need the `<queries>` entries from step 2 on Android 11 and newer, and telling Porter Compatibility from Shizuku compares their signing certificates, so package visibility for both is required.

### A Shizuku selection can have nothing to connect to

If your app declares Porter's permission, Porter's server always routes it to the `.porter` provider, and your `.porter` provider rejects delivery while Shizuku is selected. That is what keeps the two services separate, and it means an explicit Shizuku selection connects only to a real Shizuku or Sui.

So on a device with Porter and Porter Compatibility and no Shizuku, a user who picks Shizuku in your settings gets no connection, while a package check still reports Shizuku as installed. Use `getManager()` for this case: when it returns `PORTER_COMPATIBILITY` for a Shizuku selection, tell the user to select Porter rather than reporting that the Shizuku service is not running.

Porter Compatibility also has no launcher activity, so `getLaunchIntentForPackage("moe.shizuku.privileged.api")` can return `null` where it used to return a Shizuku intent. Handle a null launch intent rather than assuming one exists.

An installed permission owner does not mean the server is running. Also handle a saved explicit selection restored from backup onto a device without that manager: explain which app is missing and let the user install it or change the selection.

## Multiple app processes

Every process using the SDK must agree on the selected service. Both providers declare `android:multiprocess="false"` and belong to the same process, which receives the Binder. Other processes get it from there.

Call `ShizukuProvider.enableMultiProcessSupport()` as early as possible, in a static initializer of your `Application` class, before any provider runs:

```java
static {
    ShizukuProvider.enableMultiProcessSupport(BuildConfig.APPLICATION_ID.equals(currentProcessName));
}
```

Then in a process that is not the provider process, ask for the connection:

```java
ShizukuProvider.requestBinderForNonProviderProcess(context);
```

That call reads the Binder through the `.shizuku` provider, which the adapter keeps open for this internal lookup even when Porter is selected. It does not accept a Binder from a broadcast, so another app cannot supply one.

A lookup that finds nothing is not retried, and the notification does not repeat. The provider process announces a Binder only when it accepts a new one, so it stays quiet while it already holds a live connection. A secondary process whose lookup came back empty will not be told again until the current connection dies and is replaced.

Call `requestBinderForNonProviderProcess()` again in that process when you next need privileged access, rather than waiting for a notification that may not come. Waiting only on the Binder-received listener is not sufficient recovery here.

Sui does not share its connection through the provider at all. If you support Sui in an app with several processes, each process initializes Sui for itself, and only when Porter is not the active backend.

Each process resolves the selected service on its own. Calling `getActiveBackend()` in two processes is not agreement between them, only two reads of the same stored preference. The adapter stores that preference with SharedPreferences, which does not synchronize live across processes, so a process that started before a change keeps its old selection. End every process after changing the choice; do not attempt a running-process handover. Apps with their own preference storage should make a consistent choice before any process initializes its providers.

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
- Selector display: tapping a choice moves the control immediately, Automatic can be selected, and the reported connection keeps naming the service you are still connected to.
- Porter and Porter Compatibility installed, no Shizuku: selecting Shizuku explains that this device has no Shizuku and offers Porter, instead of reporting a stopped Shizuku service.
- Switching away from a backend that ran a user service: the old service is gone, not left running as a daemon.
- Setup links: a null launch intent for a manager package does not crash or dead-end your setup screen.

Link users to the [setup guide](/setup). It automatically selects English or German from the browser language, with a dropdown to override the choice. The URL is the same in both languages. Keep instructions for your app's settings and the app versions that support Porter in your own documentation.
