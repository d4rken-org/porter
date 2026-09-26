---
title: For app developers
---
# Add Porter support

Porter runs a service at the ADB shell or root identity and hands your app a Binder to it. Your app
asks for permission once, the user approves it, and from then on it can run commands, run its own
code or forward Binder transactions at that identity.

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
implementation("com.github.d4rken-org.porter-api:sdk-extras:+")
```

The examples below use `sdk-extras`, which brings `sdk` with it and adds shell commands,
`PorterSystemServices` and typed system property getters. Depend on `sdk` alone if you need none of
those. Pin an exact version from the
[releases page](https://github.com/d4rken-org/porter-api/releases) rather than `+`, so your build
does not move under you.

The SDK needs Android 7.0 (API 24) or newer. It is coroutines and `Flow` throughout; use from Java
is not supported.

There is nothing to add to your manifest; the SDK brings its own entries.

To connect to an original Shizuku server as well, see [Shizuku](#shizuku). If your app already
depends on `dev.rikka.shizuku:provider`, do not add `shizuku-compat`.

## 2. Wait for the connection

Your app gets a connection once Porter's service is running, and a new one whenever the user
restarts Porter while your app is alive. `Porter.connection` is a `StateFlow<PorterConnection?>`:
null until a connection exists, then the connection, and null again when it dies. Collect it rather
than reading it once.

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

Handle the null: Porter can stop at any time. After a restart, use the new connection from the
flow rather than the old one.

Every call on a connection that reaches the server suspends and is safe on the main thread. A failed
call throws a `PorterException`: `PorterSecurityException` when the server refused it, usually
because your app has no grant, and `PorterRemoteException` when the Binder call itself failed.
Cancelling a call returns at once, so `withTimeout` works against a server that stopped answering.

## 3. Tell the user why nothing happened

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

`InstalledNotConnected` is the normal state before the user starts Porter; its `packageName` is
the manager app to open. `InstalledUnrecognized` means a package this SDK does not recognize owns
Porter's permission; do not present it as the manager.

## 4. Ask for permission

A connection is not access. The user sees a dialog, so the request suspends until they answer.

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

Catch `PorterConnectionLostException`: uncaught, it ends the coroutine collecting
`Porter.connection`, and the replacement connection is never handled.

`connection.permission` is a `StateFlow<PermissionState>` a screen can observe. The user can revoke
access at any time, so handle a refused call rather than trusting an earlier check.

## 5. Do privileged work

### A shell command

`sdk-extras` runs a command at Porter's identity and returns its exit code and output:

```kotlin
val result = connection.exec("sh", "-c", "pm list packages -3")
if (result.exitCode == 0) show(result.output) else log(result.errors)
```

There is nothing to declare or implement for this. A command that is not found exits with 127, as
in a shell. A working directory that does not exist, or a shell service that stopped answering,
throws `PorterShellException`, which is not a `PorterException`; a missing grant throws
`PorterSecurityException`. Cancelling `exec` kills the command, so `withTimeout` bounds one that
hangs.

For input, binary output or a command that runs until you stop it, `connection.startProcess(...)`
returns a `PorterShellProcess`, a `java.lang.Process` whose streams are pipes to the command. Read
its output as it comes, or the command blocks once a pipe is full:

```kotlin
val logcat = connection.startProcess("logcat", "-v", "brief")
withContext(Dispatchers.IO) {
    try {
        logcat.inputStream.bufferedReader().useLines { lines -> lines.take(100).forEach(::show) }
    } finally {
        logcat.destroy()
    }
}
```

A started command is yours to stop. `destroy()` sends SIGKILL, which gives it no chance to clean up;
a command that has to finish something, such as `screenrecord`, gets a `signal(OsConstants.SIGINT)`
once it is running, and you wait for it to exit before cleaning up. A running command also dies with
the app process that started it. Once it has exited, neither `destroy()` nor the app's death reaches
what it left running, so stop those yourself.

For structured results or many calls in a row, run your own service instead.

### Your own service, at Porter's identity

Define an AIDL interface, implement it, and let Porter run it in a process with shell or root
identity. This needs no hidden API and no platform stubs.

```aidl
// IMyService.aidl
interface IMyService {
    void destroy() = 16777114; // Porter sends this to stop the service
    String readFile(String path) = 1;
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
)

connection.userService(args).collect { binder ->
    val service = IMyService.Stub.asInterface(binder)
    service.readFile("/proc/net/tcp") // readable as shell, not from your app's own process
}
```

`userService(args)` is a cold `Flow<IBinder>`: collecting it starts the service if needed and emits
its Binder. The flow completes when the service dies, and when its connection is replaced or dies.
Collect it again on the same connection after the service died, or on the next one after a restart.
To keep one service for the whole app:

```kotlin
val myService: StateFlow<IMyService?> = Porter.connection
    .flatMapLatest { connection ->
        if (connection == null) return@flatMapLatest flowOf(null)
        connection.permission.flatMapLatest { permission ->
            if (permission !is PermissionState.Granted) return@flatMapLatest flowOf(null)
            flow {
                while (true) {
                    emitAll(
                        connection.userService(args)
                            .map { IMyService.Stub.asInterface(it) }
                            .catch { e -> if (e !is PorterException) throw e },
                    )
                    emit(null) // the service died or could not be bound; try again
                    delay(1_000)
                }
            }
        }
    }
    .stateIn(appScope, SharingStarted.WhileSubscribed(30_000), null)
```

Implement `destroy` to clean up and exit: `stopUserService(args)` calls it, and Porter kills a
process still running a few seconds later. Cancelling the collection does not stop the process. A
service ends with the last app process still collecting it, unless you set `daemon = true`. One you
stopped collecting runs on until you stop it, your app loses its permission or is uninstalled, or
Porter stops.

Bump `version` whenever the service code changes, so Porter replaces a running instance. Porter
identifies a service by its `tag`, or its class name when no tag is set, so set a stable tag if the
class is obfuscated.

`connection.uid` is `2000` for ADB and `0` for root, and shell can do far less than root: it cannot
read other apps' data, and its Android permissions are those of the
[Shell package](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/packages/Shell/AndroidManifest.xml).
The service process is not an Android application process: a `Context` there cannot register
receivers or reach a content resolver.

### Forwarding calls to a system service

`connection.wrap(binder)` wraps a system service's Binder so Porter re-issues every transaction on it
at its own identity. `PorterSystemServices` from `sdk-extras` does the lookup:

```kotlin
val binder = PorterSystemServices.getSystemService("package") ?: return
val pm = IPackageManager.Stub.asInterface(connection.wrap(binder))
pm.getInstalledPackages(0, 0)
```

These calls block like any Binder call, so make them off the main thread. A refusal arrives as the
platform's `SecurityException`, not as a `PorterException`.

Interfaces like `IPackageManager` are platform-internal, so this route needs compile-time stubs and a
way past the non-SDK interface restrictions, such as
[HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin) and
[AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass). For file or process
access, a shell command or your own service needs neither.

## Apps with several processes

Only one process receives the connection. In every other process, call:

```kotlin
PorterApiProvider.requestBinderForNonProviderProcess(context)
```

The connection then arrives on `Porter.connection` there too. Calling it in the process that
receives the connection does nothing, so one call site for every process is fine. The first call
also listens for each connection that process receives later, so a process that started before
Porter still gets one.

## Shizuku

The SDK can also take a Binder from an original Shizuku server. An app without upstream's
`dev.rikka.shizuku:provider` adds `shizuku-compat` and this block in its manifest; without the
permission and the meta-data, a Shizuku server refuses your app:

```kotlin
implementation("com.github.d4rken-org.porter-api:shizuku-compat:+")
```

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

Declaring that provider without `moe.shizuku.api.BinderContainer` on the classpath, which
`shizuku-compat` ships, crashes your app on launch, whether or not Porter or Shizuku is installed.
`dev.rikka.shizuku:provider` ships the same class, so the two cannot both be in one app, including
through another library. Without both the artifact and the provider, a Shizuku-only device reads
`NotInstalled`.

When both are installed, the SDK uses Porter, even when it is stopped, so tell those users to start
Porter.

An app that keeps upstream's `dev.rikka.shizuku:api` and `:provider`, for example for a library
built on them, can use this SDK for Porter alone. Leave out `shizuku-compat` and
keep upstream's `ShizukuProvider`. Each client then gets its own server's Binder, and
`Porter.availability(context)` answers only about Porter.

## Versions

SDK release numbers are independent of Porter's own app version. While the SDK is `0.x`:

- A minor release can add API and change behaviour this guide documents. Read the release notes
  before bumping.
- An app built against an earlier `0.x` keeps working with a newer Porter, and a newer SDK does not
  require a newer Porter unless a release note says so.
- Compatibility is at source level only: a library compiled against an earlier `0.x` has to be
  recompiled against the new SDK.

## Before you ship

- Porter installed and running: approval, a real privileged operation and your user service all work.
- Porter installed but stopped: your app says so and offers to open Porter.
- Porter not installed: your app offers to install it.
- Access denied, and denied with "don't ask again": privileged work stops and your UI reflects it.
- Access revoked while running: the next privileged call is refused and your UI recovers.
- Porter restarted while your app is alive: your collector picks up the new connection.
- Your activity recreated: the new collector sees the held connection at once.
- Secondary processes: each one obtains the connection, even one that started before Porter.
- Your user service: replaced when you bump its version, and gone after `stopUserService`.

Link users to the [setup guide](/setup). Keep instructions for your app's own settings, and which of
your app versions support Porter, in your own documentation.

For the full Kotlin surface, see the
[API reference](https://github.com/d4rken-org/porter-api/blob/main/docs/api-reference.md).
