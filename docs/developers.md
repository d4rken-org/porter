---
title: For app developers
---
# Add Porter support

Porter runs a service at the ADB shell or root identity and hands your app a Binder to it. Your app
asks for permission once, the user approves it, and from then on it can forward Binder transactions
or run its own code at that identity.

Contributions and integrations from other app developers are welcome.

## 1. Add the SDK

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

Then add the SDK:

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk:+")
```

`+` takes the newest release. Pinning an exact version from the
[releases page](https://github.com/d4rken-org/porter-api/releases) is recommended, so your build
does not move under you. Source and releases are in
[Porter API](https://github.com/d4rken-org/porter-api).

The SDK needs Android 7.0 (API 24) or newer. It is coroutines and `Flow` throughout; use from Java
is not supported.

There is nothing to add to your manifest. The SDK declares Porter's permission
(`eu.darken.porter.permission.API`), the package visibility entry that Android 11 and newer require,
and the provider that receives the connection. Manifest merging adds them to your app. If you
inspect the merged manifest you will see a provider named `eu.darken.porter.sdk.PorterApiProvider`
on the `${applicationId}.porter.api` authority; that is the SDK's. Its `android:permission` is one
the shell holds and normal apps do not, so only your own app and the server can reach it.

### The optional artifacts

`sdk-extras` carries what the SDK itself leaves out: `PorterSystemServices`, which looks up a system
service Binder inside your own process without a round trip to Porter, and typed system property
getters (`getSystemPropertyInt`, `getSystemPropertyLong`, `getSystemPropertyBoolean`) as extension
functions on a connection. It depends on `sdk` and brings it transitively, so add this instead of
both:

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk-extras:+")
```

`shizuku-compat` is what lets the SDK take a Binder from an original Shizuku server. It ships
`moe.shizuku.api.BinderContainer`, which is the class `dev.rikka.shizuku:provider` also ships, and
two copies of one class name do not dex. The two artifacts are mutually exclusive, including when
one of them arrives transitively through another library, so an app that already uses the upstream
provider must not add `shizuku-compat`.

Adding it does not by itself connect your app to Shizuku. The SDK declares nothing at Shizuku's
authority, so declare that block yourself. A Shizuku server refuses an app that requests neither the
permission nor the meta-data:

```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

<application>
    <meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />
    <provider
        android:name="eu.darken.porter.sdk.PorterShizukuApiProvider"
        android:authorities="${applicationId}.shizuku"
        android:exported="true"
        android:multiprocess="false"
        android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
</application>
```

Declaring that provider without the container class on the classpath throws in `attachInfo`, which
Android calls while it installs the provider. That crashes the app on launch, whether or not Porter
or Shizuku is installed.

### Which backend the SDK talks to

The choice between Porter and Shizuku is made by what is installed, not by what is running: an
installed Porter always wins, so a Porter that is installed but stopped blocks a running Shizuku, and
a process that holds a live connection never switches backend until that connection dies. Tell users
with both installed to start Porter.

What the SDK actually probes for is `moe.shizuku.api.BinderContainer` on the classpath, which is
what `shizuku-compat` puts there, and what `dev.rikka.shizuku:provider` would put there too. Without
that class the SDK can unwrap no Shizuku Binder, so a Shizuku-only device reads `NOT_INSTALLED`
rather than promising a connection it cannot take.

### What a version promises

SDK release numbers are independent of Porter's own app version. While the SDK is `0.x`:

- A minor release can add API and can change behaviour this guide documents. Read the release notes
  before bumping, and use `+` only if you are willing to meet that at build time.
- The Binder protocol and the provider authority stay compatible across `0.x`. An app built against
  an earlier `0.x` keeps working with a newer Porter.
- A newer SDK does not require a newer Porter unless a release note says so.
- Source compatibility only. The public value types (`UserServiceArgs`, `PermissionState.Denied`,
  `PorterServerInfo`) are Kotlin data classes, and a field added to one changes its constructor and
  `copy` signatures, so a library compiled against an earlier `0.x` has to be recompiled against the
  new SDK rather than only run against it.

The SDK and the Porter service confirm this when they connect. Each side names the protocol version
it speaks and the oldest one it still accepts; a newer peer is never a problem on its own. When the
two do not overlap, no connection is published and `Porter.availability(context)` answers
`INCOMPATIBLE`; `Porter.incompatibility` then says whether the user has to update Porter
(`serverTooOld`) or your app needs a newer SDK (`clientTooOld`).

Fixes reach apps only through a new SDK release and a dependency bump in your build. There is no
runtime update path for the library.

## 2. Wait for the connection

Porter delivers a Binder to your app once its service is running, typically as your app comes to the
foreground, and delivers a new one whenever the user restarts Porter while your app is alive. You do
not control when that happens, so observe the connection rather than polling for it.

`Porter.connection` is a `StateFlow<PorterConnection?>`: null until a connection exists, the
connection while one does, and null again when it dies. Everything you do with Porter goes through
the `PorterConnection` it holds.

```kotlin
class MyActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Porter.connection.collect { connection ->
                    if (connection == null) showDisconnected() else onPorterReady(connection)
                }
            }
        }
    }
}
```

A `StateFlow` replays its current value, so a collector that starts while a connection is already
held sees it at once, which is the common case when your activity is recreated.

The flow moves straight from one connection to the next only when the replacement attaches while the
old server is still alive. A Porter that stops before the new one starts, which is what a restart
usually looks like, publishes null in between, so handle the null rather than assuming a handover.

`Porter.connection.value` answers whether a connection is held right now, and `connection.isAlive`
whether its Binder still answers. Use them for a one-off check, not as a substitute for collecting.

A `PorterConnection` stays bound to the server it was attached to. Hold the one the flow gave you
for the work at hand, and take the next one from the flow after a restart rather than reusing it.

## 3. Ask for permission

A connection is not access. Ask before doing privileged work. The user sees a dialog, so the request
suspends until they answer.

```kotlin
suspend fun onPorterReady(connection: PorterConnection) {
    var state = connection.checkPermission()
    if (state is PermissionState.Denied) {
        if (state.permanentlyDenied) {
            // The user denied it and asked not to be asked again. Explain why you need it
            // and point them at Porter's own screen.
            explainWhyWeNeedIt()
            return
        }
        state = try {
            connection.requestPermission()
        } catch (e: PorterConnectionLostException) {
            return // Porter restarted while the dialog was up; the next connection arrives on the flow
        }
    }
    if (state is PermissionState.Granted) doPrivilegedWork(connection)
}
```

`PorterConnectionLostException` is an `IllegalStateException`. Uncaught inside the collector above it
takes down the coroutine that was watching `Porter.connection`, so the replacement connection is
never handled.

`connection.permission` is a `StateFlow<PermissionState>` holding the latest state the server
reported, so a screen can react to a grant or a revocation without asking again.
`checkPermission()` does not always reach the server: a held grant is answered from that state and
only a denial is asked about again, so a revocation reaches you when the server pushes it, not when
you call. On the Shizuku backend nothing pushes it at all, and a grant there can stay published
until the Binder dies.

The server enforces the permission on every call it serves, so handle a refused call rather than
trusting a recent check.

## 4. Do privileged work

Two routes, with very different requirements.

### Your own service, at Porter's identity

Define an AIDL interface, implement it, and let Porter run it in a process with shell or root
identity. This needs no hidden API and no platform stubs.

```aidl
// IMyService.aidl
interface IMyService {
    void destroy() = 16777114; // Porter sends this to stop the service
    String readFile(String path);
}
```

```kotlin
class MyService : IMyService.Stub() {
    override fun destroy() = exitProcess(0)
    override fun readFile(path: String): String = File(path).readText()
}
```

```kotlin
val args = UserServiceArgs(
    componentName = ComponentName(this, MyService::class.java),
    processNameSuffix = "my-service",
    tag = "my-service",
    version = 1,
    daemon = false,
)

connection.userService(args).collect { binder ->
    val service = IMyService.Stub.asInterface(binder)
    service.readFile("/proc/net/tcp") // readable as shell, not from your app's own process
}
```

`userService(args)` is a cold `Flow<IBinder>`: collecting it binds the service and starts it if
needed, and the service's Binder is emitted once Porter reports it connected. The flow completes
when Porter reports the service died, and also when the connection it was collected on is replaced
or dies, whether or not the service is still running. Collect it again on the connection the flow
hands you next. Cancelling the collection releases your binding; when the last collector of that
service is gone, Porter is asked to drop the binding.
`userService(args, start = false)` only binds an instance that is already running, and completes
without emitting when there is none. `peekUserService(args)` reports a running instance's version
without binding.

Dropping a binding does not stop the process, and neither does `stopUserService(args)` on its own:
all it does is send the `destroy` transaction above. Porter has no other way to stop the process, so
a service that leaves the method unimplemented keeps running.

Bump `version` whenever the service code changes, so Porter replaces a running instance instead of
reusing a stale one. Porter identifies a service by its `tag`, or by the class name when no tag is
set, so set a stable tag if your service class is obfuscated.

What the service can reach depends on how Porter was started. `connection.uid` is `2000` for ADB and
`0` for root, and shell holds far less than root: it cannot read another app's data files, and its
Android permissions are those of the
[Shell package](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/packages/Shell/AndroidManifest.xml),
which vary by Android version.

A user service is per Android user. A work profile's copy of your app gets its own service
process, started with that profile's uid, and never shares one with the personal profile's copy.

The service process is not a valid Android application process: a `Context` obtained there cannot
register receivers or reach a content resolver.

### Forwarding calls to a system service

`connection.wrap(binder)` wraps a system service's Binder so every transaction on it is re-issued
by Porter at its own identity. `PorterSystemServices` from `sdk-extras` does the lookup:

```kotlin
val binder = PorterSystemServices.getSystemService("package") ?: return
val pm = IPackageManager.Stub.asInterface(connection.wrap(binder))
pm.getInstalledPackages(0, 0)
```

Porter does not grant your app privileges; it re-issues the transaction you construct. Constructing
it means speaking the system service's AIDL, and that is not in the public SDK. Interfaces like
`android.content.pm.IPackageManager` are platform-internal, so you need compile-time stubs and, on
current Android versions, a way past the non-SDK interface restrictions.
[HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin) and
[AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) cover those two.

If what you need is file, process or filesystem access rather than a specific system service, prefer
the user service above. It needs none of that.

## 5. Tell the user why nothing happened

`Porter.availability(context)` distinguishes the cases behind a connection that never arrives:

```kotlin
when (Porter.availability(this)) {
    PorterAvailability.CONNECTED -> Unit // a connection is held and answers
    PorterAvailability.INSTALLED_NOT_CONNECTED -> promptUser("Open Porter and start the service")
    PorterAvailability.NOT_INSTALLED -> promptUser("Install Porter")
    PorterAvailability.INSTALLED_UNRECOGNIZED -> promptUser("An unrecognized app owns that permission")
    PorterAvailability.INCOMPATIBLE -> if (Porter.incompatibility?.serverTooOld == true) {
        promptUser("Update Porter")
    } else {
        promptUser("This app needs an update to work with this Porter")
    }
}
```

This reports whether a manager is installed, not whether its service is running, so
`INSTALLED_NOT_CONNECTED` is the normal state before the user starts Porter.
`INSTALLED_UNRECOGNIZED` means the selected backend's permission belongs to a package this SDK does
not recognize as its manager; say so rather than naming or launching that package. `INCOMPATIBLE` means a service is running and
answered, and the two sides share no protocol version.

## Apps with several processes

The provider that receives the connection is not multiprocess, so one process gets the Binder and
the others ask it for the connection.

Call this as early as possible, in the companion object's initializer of your `Application` class,
before any provider runs:

```kotlin
companion object {
    init {
        PorterApiProvider.enableMultiProcessSupport(currentProcessName == BuildConfig.APPLICATION_ID)
    }
}
```

Then in a process that is not the provider process:

```kotlin
PorterApiProvider.requestBinderForNonProviderProcess(context)
```

That reads the connection through the SDK's own provider. It does not accept a Binder from a
broadcast, so another app cannot supply one.

A lookup that finds nothing is not retried. The provider process announces a connection only when it
accepts a new one, so it stays quiet while it already holds a live one, and a secondary process whose
lookup came back empty is not told again until the current connection dies and is replaced. Call
`requestBinderForNonProviderProcess()` again when that process next needs privileged access, rather
than waiting for a notification that may not arrive.

## Before you ship

- Porter installed and running: approval, a real privileged operation and your user service all work.
- Porter installed but stopped: your app says so and offers to open Porter, rather than waiting
  silently.
- Porter not installed: your app offers to install it.
- Access denied, and denied with "don't ask again": privileged work stops and your UI reflects it.
- Access revoked while running: the next privileged call is refused and your UI recovers.
- Porter restarted while your app is alive: `Porter.connection` moves to the new connection and your
  collector picks it up, without reusing the old `PorterConnection`.
- Your activity recreated: a new collector of `Porter.connection` sees the held connection at once.
- Secondary processes: each one obtains the connection, and a process that started before Porter did
  can still get it.
- Your user service: replaced when you bump its version, and gone after `stopUserService`.

Link users to the [setup guide](/setup). It selects English or German from the browser language,
with a dropdown to override. The URL is the same in both languages. Keep instructions for your app's
own settings, and which of your app versions support Porter, in your own documentation.

For the full Kotlin surface, and the upstream Shizuku-API changelog if you are migrating from that
SDK, see the [API reference](https://github.com/d4rken-org/porter-api/blob/main/docs/api-reference.md).
