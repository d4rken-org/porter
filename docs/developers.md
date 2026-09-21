---
title: For app developers
---
# Add Porter support

Porter gives your app a privileged Binder connection: an interface running with ADB shell or root
identity, which your app can call into for work the app sandbox cannot do. Your app asks Porter for
permission once, the user approves it, and from then on your app can forward Binder transactions or
run its own service at that identity.

Contributions and integrations from other app developers are welcome.

The integration has three parts: add the dependency, collect the connection, and ask for permission
before doing privileged work. The SDK is Kotlin-first: its asynchronous surface is coroutines and Flow. The SDK contributes its own manifest entries, so there is nothing to
declare yourself.

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
implementation("com.github.d4rken-org.porter-api:sdk:0.2.0")
```

Use a fixed release tag, not a moving branch or a `-SNAPSHOT` version. Source and releases are in
[Porter API](https://github.com/d4rken-org/porter-api).

The SDK needs Android 7.0 (API 24) or newer.

**You do not need to touch your manifest.** The SDK declares Porter's permission
(`eu.darken.porter.permission.API`), the package visibility entry that Android 11 and newer
require, and the provider that receives the connection. Manifest merging adds them to your app. If
you inspect the merged manifest you will see a provider named `eu.darken.porter.sdk.PorterApiProvider`
on the `${applicationId}.porter.api` authority; that is the SDK's, and it is protected so only Porter
can deliver through it.

### Which backend the SDK talks to

The SDK also speaks to an original Shizuku server, if your app opts in with the `shizuku-compat`
artifact and the provider block its documentation shows. The choice between the two is made by
what is installed, not by what is running: an installed Porter always wins, so a Porter that is
installed but stopped blocks a running Shizuku, and a process that holds a live connection never
switches backend until that connection dies. Tell users with both installed to start Porter.

### What a version promises

SDK release numbers are independent of Porter's own app version. While the SDK is `0.x`:

- A minor release can add API and can change behaviour this guide documents. Read the release notes
  before bumping.
- The Binder protocol and the provider authority stay compatible across `0.x`. An app built against
  an earlier `0.x` keeps working with a newer Porter.
- A newer SDK does not require a newer Porter unless a release note says so.

The SDK and the Porter service confirm this when they connect. Each side names the protocol
version it speaks and the oldest one it still accepts; a newer peer is never a problem on its own.
When the two do not overlap, no connection is published and `Porter.availability(context)` answers
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
held sees it at once, which is the common case when your activity is recreated. When Porter is
restarted the flow goes straight from the old connection to the new one; it does not pass through
null in between, so a collector never sees a gap between two live servers.

`Porter.connection.value` answers whether a connection is held right now, and
`connection.isAlive` whether its Binder still answers. Use them for a one-off check, not as a
substitute for collecting.

A `PorterConnection` stays bound to the server it was attached to. Hold the one the flow gave you
for the work at hand, and take the next one from the flow after a restart rather than reusing it.

## 3. Ask for permission

A connection is not access. Ask before doing privileged work. The user sees a dialog, so the request
suspends until they answer.

```kotlin
suspend fun onPorterReady(connection: PorterConnection) {
    when (val state = connection.checkPermission()) {
        PermissionState.Granted -> doPrivilegedWork(connection)
        is PermissionState.Denied -> if (state.shouldShowRationale) {
            // The user denied it and asked not to be asked again. Explain why you need it
            // and point them at Porter's own screen.
            explainWhyWeNeedIt()
        } else if (connection.requestPermission() == PermissionState.Granted) {
            doPrivilegedWork(connection)
        }
    }
}
```

`connection.permission` is a `StateFlow<PermissionState>` holding the latest state the server
reported, so a screen can react to a grant or a revocation without asking again.
`checkPermission()` asks the server and updates that flow. `requestPermission()` throws
`PorterConnectionLostException` if Porter is restarted or stops before the user answers; take the
new connection from `Porter.connection` and ask again.

Permission can be revoked while your app runs, and a one-time grant expires. Call
`checkPermission()` before privileged work rather than caching the answer from earlier in the
session.

## 4. Do privileged work

There are two routes, and they have very different requirements.

### Your own service, at Porter's identity

Define an AIDL interface, implement it, and let Porter run it in a process with shell or root
identity. This needs no hidden API and no platform stubs.

```kotlin
class MyService : IMyService.Stub() {
    override fun destroy() = exitProcess(0)
    override fun readRestrictedFile(path: String): String = File(path).readText()
}
```

```kotlin
val args = UserServiceArgs(
    componentName = ComponentName(this, MyService::class.java),
    processNameSuffix = "my-service",
    version = 1,
    daemon = false,
)

connection.userService(args).collect { service ->
    val myService = IMyService.Stub.asInterface(service)
    // ...
}
```

`userService(args)` is a cold `Flow<IBinder>`: collecting it binds the service and starts it if
needed, the service's Binder is emitted once Porter reports it connected, and the flow completes
when the service process dies. Cancelling the collection releases your binding; when the last
collector of that service is gone, Porter is asked to drop the binding without killing the service.
`userService(args, start = false)` only binds an instance that is already running, and completes
without emitting when there is none. `peekUserService(args)` reports a running instance's version
without binding, and `stopUserService(args)` kills it. Implement a `destroy` method that calls
`exitProcess`, or a released service stays alive.

Bump `version` whenever the service code changes, so Porter replaces a running instance instead of
reusing a stale one. Porter identifies a service by its `tag`, or by the class name when no tag is
set, so set a stable tag if your service class is obfuscated.

A user service is per Android user. A work profile's copy of your app gets its own service
process, started with that profile's uid, and never shares one with the personal profile's copy.

### Forwarding calls to a system service

`connection.wrap(binder)` wraps a system service's Binder so every transaction on it is re-issued
by Porter at its own identity:

```kotlin
val pm = IPackageManager.Stub.asInterface(connection.wrap(ServiceManager.getService("package")))
pm.getInstalledPackages(0, 0)
```

Be aware of what this route costs you. Porter does not grant your app privileges; it re-issues the
transaction you construct. Constructing it means holding the system service's Binder and speaking its
AIDL, and neither is in the public SDK. `android.os.ServiceManager` and interfaces like
`android.content.pm.IPackageManager` are platform-internal, so you need compile-time stubs and, on
current Android versions, a way past the non-SDK interface restrictions. That is inherent to calling
platform APIs the SDK does not expose, not something Porter can remove.

If what you need is file, process or filesystem access rather than a specific system service, prefer
the user service above. It needs none of that.

## 5. Tell the user why nothing happened

If no connection arrives, your app should say something more useful than a spinner.
`Porter.availability(context)` distinguishes the cases:

```kotlin
when (Porter.availability(this)) {
    PorterAvailability.CONNECTED -> Unit // a connection is held and answers
    PorterAvailability.INSTALLED_NOT_CONNECTED -> promptUser("Open Porter and start the service")
    PorterAvailability.NOT_INSTALLED -> promptUser("Install Porter")
    PorterAvailability.INSTALLED_UNRECOGNIZED -> promptUser("Another app owns Porter's permission")
    PorterAvailability.INCOMPATIBLE -> if (Porter.incompatibility?.serverTooOld == true) {
        promptUser("Update Porter")
    } else {
        promptUser("This app needs an update to work with this Porter")
    }
}
```

This reports whether a manager is installed, not whether its service is running, so
`INSTALLED_NOT_CONNECTED` is the normal state before the user starts Porter.
`INSTALLED_UNRECOGNIZED` means some package other than Porter declares Porter's permission; say so
rather than naming or launching that package. `INCOMPATIBLE` means a service is running and
answered, and the two sides share no protocol version.

## Multiple app processes

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

## Verify your integration

Check these before releasing:

- Porter installed and running: approval, a real privileged operation and your user service all work.
- Porter installed but stopped: your app says so and offers to open Porter, rather than waiting
  silently.
- Porter not installed: your app offers to install it.
- Access denied, and denied with "don't ask again": privileged work stops and your UI reflects it.
- Access revoked while running: the next privileged call is refused and your UI recovers.
- Porter restarted while your app is alive: `Porter.connection` moves to the new connection and your
  collector picks it up, without reusing the old `PorterConnection`.
- Your app restarted: a new collector of `Porter.connection` sees the existing connection at once.
- Secondary processes: each one obtains the connection, and a process that started before Porter did
  can still get it.
- Your user service: replaced when you bump its version, and gone after `stopUserService`.

Link users to the [setup guide](/setup). It selects English or German from the browser language,
with a dropdown to override. The URL is the same in both languages. Keep instructions for your app's
own settings, and which of your app versions support Porter, in your own documentation.
