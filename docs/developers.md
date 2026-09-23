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

`sdk-extras` carries what the SDK itself leaves out: shell commands (`exec` and `startProcess`, see
section 4), `PorterSystemServices`, which looks up a system service Binder inside your own process
without a round trip to Porter, and typed system property getters (`getSystemPropertyInt`,
`getSystemPropertyLong`, `getSystemPropertyBoolean`) as extension functions on a connection. It
depends on `sdk` and brings it transitively, so add this instead of both:

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

Shizuku+'s Plus flavor declares its own permission, `af.shizuku.plus.permission.API_V23`, instead of
Shizuku's, and counts as Shizuku here.

The SDK considers Shizuku only where it can receive from it: `moe.shizuku.api.BinderContainer` is on
the classpath, and your app declares `PorterShizukuApiProvider` at `${applicationId}.shizuku`.
Without both, a Shizuku-only device reads `NotInstalled` rather than promising a connection it
cannot take.

### Next to the upstream Shizuku client

An app can keep `dev.rikka.shizuku:api` and `:provider` for Shizuku, for example because a library
such as Ackpine is built on them, and use this SDK for Porter alone. Leave out `shizuku-compat` and
keep upstream's `ShizukuProvider` at `${applicationId}.shizuku`. Upstream's client then gets
Shizuku's Binder and this SDK gets Porter's, so a user with both installed can pick either in your
app while both run, with no restart. `Porter.availability(context)` only ever answers about Porter
in this setup; ask upstream's client about Shizuku.

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
`Incompatible`; its `incompatibility` says whether the user has to update Porter (`serverTooOld`)
or your app needs a newer SDK (`clientTooOld`). An original Shizuku server older than protocol 13 is
reported the same way, with its `backend` set to `SHIZUKU`.

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

`Porter.connection.value` answers whether a connection is held right now, and `connection.isAlive()`
whether its Binder still answers. Use them for a one-off check, not as a substitute for collecting.

Every call on a connection that reaches the server suspends and is safe on the main thread; the SDK
moves the Binder call off it. A failed call throws a `PorterException`: `PorterSecurityException`
when the server refused it, usually because your app has no grant, and `PorterRemoteException` when
the Binder call itself failed. Cancelling a call returns at once, so `withTimeout` around it works
against a server that stopped answering. A call still queued is then never made; one already sent
takes effect anyway.

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

`PorterConnectionLostException` is a `PorterException`, and unchecked. Uncaught inside the collector
above it takes down the coroutine that was watching `Porter.connection`, so the replacement
connection is never handled.

`connection.permission` is a `StateFlow<PermissionState>` holding the latest state the server
reported, so a screen can react to a grant or a revocation without asking again.
`checkPermission()` does not always reach the server: a held grant is answered from that state and
only a denial is asked about again, so a revocation reaches you when the server pushes it, not when
you call. On the Shizuku backend nothing pushes it at all, and a grant there can stay published
until the Binder dies.

The server enforces the permission on every call it serves, so handle a refused call rather than
trusting a recent check.

## 4. Do privileged work

Three routes, with very different requirements.

### A shell command

`sdk-extras` runs a command at Porter's identity and returns its exit code and output:

```kotlin
val result = connection.exec(context, "sh", "-c", "pm list packages -3")
if (result.exitCode == 0) show(result.output)
```

The command runs in a user service that `sdk-extras` ships, named `your.app:porter_shell`. The first
call starts it and later calls reuse it, so there is nothing to declare or implement. It works on
both backends.

`exec` closes the command's input and reads its output as UTF-8. For a command that takes input,
writes binary output or runs until you stop it, `connection.startProcess(context, ...)` returns a
`java.lang.Process` whose streams are pipes to the command. Read what it writes as it comes, or it
blocks once a pipe is full.

Cancelling either call returns at once and kills the command and the processes it started, so
`withTimeout` bounds one that hangs. A command also dies with the app process that started it. A
command that is not found exits with 127, as in a shell. A working directory that does not exist, or
a service that stops while the command runs, throws `PorterShellException`. A missing grant throws
the SDK's `PorterSecurityException`, as for any user service.

A shell script is text in and text out. When you need structured results or many calls in a row,
your own service below does the same work in-process.

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
hands you next, and again on the same connection after the service died; the SDK's README shows a
shared flow that does both. Cancelling the collection releases your binding at once; when the last
collector of that service is gone, Porter is asked to drop the binding.
`userService(args, start = false)` only binds an instance that is already running, and completes
without emitting when there is none. `peekUserService(args)` reports a running instance's version
without binding.

Dropping a binding does not stop the process, and neither does `stopUserService(args)` on its own:
all it does is send the `destroy` transaction above (`UserServiceArgs.TRANSACTION_DESTROY`). Porter
has no other way to stop the process, so a service that leaves the method unimplemented keeps
running.

A service is not a daemon unless you set `daemon = true`: it ends when your app's process that bound
it dies. A daemon outlives that process, so `destroy` is the only thing that ends it.

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

Each call through the wrapper blocks like any Binder call, so make it off the main thread. A refusal
comes back in the reply, so the interface's own proxy raises it as the platform's
`SecurityException`, not as a `PorterException`.

Porter does not grant your app privileges; it re-issues the transaction you construct. Constructing
it means speaking the system service's AIDL, and that is not in the public SDK. Interfaces like
`android.content.pm.IPackageManager` are platform-internal, so you need compile-time stubs and, on
current Android versions, a way past the non-SDK interface restrictions.
[HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin) and
[AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) cover those two.

If what you need is file, process or filesystem access rather than a specific system service, prefer
a shell command or your own service above. Neither needs any of that.

## 5. Tell the user why nothing happened

`Porter.availability(context)` distinguishes the cases behind a connection that never arrives:

```kotlin
lifecycleScope.launch {
    when (val availability = Porter.availability(this@MyActivity)) {
        is PorterAvailability.Connected -> Unit // a connection is held and answers
        is PorterAvailability.InstalledNotConnected -> offerToOpen(availability.packageName, "Start the service")
        PorterAvailability.NotInstalled -> promptUser("Install Porter")
        is PorterAvailability.InstalledUnrecognized -> promptUser("An unrecognized app owns that permission")
        is PorterAvailability.Incompatible -> if (availability.incompatibility.serverTooOld) {
            promptUser("Update Porter")
        } else {
            promptUser("This app needs an update to work with this Porter")
        }
    }
}
```

It reports whether a manager is installed, not whether its service is running, so `InstalledNotConnected` is the normal state before the user
starts Porter. `InstalledUnrecognized` means the selected backend's permission belongs to a package
this SDK does not recognize as its manager; say so rather than presenting or launching that package
as the manager. `Incompatible` means a service is running and answered, and the two sides share no
protocol version; the reason travels with the answer.

Every case but `NotInstalled` carries `packageName`: the app that declares the backend's permission,
found by the permission rather than by name, so a renamed fork or Shizuku+ is found too. Open that
package to send the user to their manager. It names the manager; it does not prove that package
served the connection, and `Connected` and `Incompatible` name none when no app declares the
permission any more. The installed and connected cases also carry the `backend`.

## Apps with several processes

The provider that receives the connection is not multiprocess, so one process gets the Binder and
the others ask it for the connection.

In every process that is not the provider process, call:

```kotlin
PorterApiProvider.requestBinderForNonProviderProcess(context)
```

That reads the connection through the SDK's own provider, off the calling thread, and the result
arrives on `Porter.connection`. It does not accept a Binder from a broadcast, so another app cannot
supply one. Calling it in the provider process does nothing, so one call site for every process is
fine.

The first call also starts listening: the provider process announces each connection it publishes,
and the secondary process asks again then, and again after its connection dies. A lookup that finds
nothing is not retried on its own, so call `requestBinderForNonProviderProcess()` again when that
process next needs privileged access. Repeated calls only ask again; they register nothing twice.

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
