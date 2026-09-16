---
title: For app developers
---
# Add Porter support

Porter gives your app a privileged Binder connection: an interface running with ADB shell or root
identity, which your app can call into for work the app sandbox cannot do. Your app asks Porter for
permission once, the user approves it, and from then on your app can forward Binder transactions or
run its own service at that identity.

Contributions and integrations from other app developers are welcome.

The integration has three parts: add the dependency, wait for the connection, and ask for permission
before doing privileged work. The SDK contributes its own manifest entries, so there is nothing to
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

**You do not need to touch your manifest.** The SDK declares Porter's permission, the package
visibility entry that Android 11 and newer require, and the provider that receives the connection.
Manifest merging adds them to your app. If you inspect the merged manifest you will see a provider
named `eu.darken.porter.sdk.PorterApiProvider` on the `${applicationId}.porter.api` authority; that
is the SDK's, and it is protected so only Porter can deliver through it.

### What a version promises

SDK release numbers are independent of Porter's own app version. While the SDK is `0.x`:

- A minor release can add API and can change behaviour this guide documents. Read the release notes
  before bumping.
- The Binder protocol and the provider authority stay compatible across `0.x`. An app built against
  an earlier `0.x` keeps working with a newer Porter.
- A newer SDK does not require a newer Porter unless a release note says so.

Fixes reach apps only through a new SDK release and a dependency bump in your build. There is no
runtime update path for the library.

## 2. Wait for the connection

Porter delivers a Binder to your app once its service is running, typically as your app comes to the
foreground, and delivers a new one whenever the user restarts Porter while your app is alive. You do
not control when that happens, so register a listener rather than polling.

```java
public class MyActivity extends Activity {

    private final Porter.OnBinderReceivedListener received = () -> runOnUiThread(this::onPorterReady);
    private final Porter.OnBinderDeadListener died = () -> showDisconnected();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Sticky: calls you at once if a connection is already held, which is the common
        // case when your activity is recreated.
        Porter.addBinderReceivedListenerSticky(received);
        Porter.addBinderDeadListener(died);
    }

    @Override protected void onDestroy() {
        Porter.removeBinderReceivedListener(received);
        Porter.removeBinderDeadListener(died);
        super.onDestroy();
    }
}
```

Both `add` methods take an optional `Handler` if you want the callback somewhere other than the main
thread. Always remove what you registered; the SDK holds its listeners for the life of the process.

`Porter.pingBinder()` answers whether a live connection is held right now. Use it for a one-off check,
not as a substitute for the listener.

## 3. Ask for permission

A connection is not access. Ask before doing privileged work, and handle the answer asynchronously:
the user sees a dialog.

```java
private final Porter.OnRequestPermissionResultListener permission = (requestCode, result) -> {
    if (result == PackageManager.PERMISSION_GRANTED) onPorterReady();
};

private void onPorterReady() {
    if (Porter.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        doPrivilegedWork();
    } else if (Porter.shouldShowRequestPermissionRationale()) {
        // The user denied it and asked not to be asked again. Explain why you need it
        // and point them at Porter's own screen.
        explainWhyWeNeedIt();
    } else {
        Porter.requestPermission(1);
    }
}
```

Register that listener with `Porter.addRequestPermissionResultListener` and remove it in `onDestroy`,
the same as the others.

Permission can be revoked while your app runs, and a one-time grant expires. Check
`checkSelfPermission()` before privileged work rather than caching the answer from earlier in the
session.

## 4. Do privileged work

There are two routes, and they have very different requirements.

### Your own service, at Porter's identity

Define an AIDL interface, implement it, and let Porter run it in a process with shell or root
identity. This needs no hidden API and no platform stubs.

```java
public class MyService extends IMyService.Stub {
    @Override public void destroy() { System.exit(0); }
    @Override public String readRestrictedFile(String path) { /* plain java.io */ }
}
```

```java
Porter.UserServiceArgs args = new Porter.UserServiceArgs(new ComponentName(this, MyService.class))
        .processNameSuffix("my-service")
        .version(1)
        .daemon(false);

Porter.bindUserService(args, connection);
```

You get an ordinary `ServiceConnection`. `peekUserService` asks for an already-running instance
without starting one, and `unbindUserService(args, connection, remove)` releases it. Implement a
`destroy` method that calls `System.exit`, or an unbound service stays alive.

Bump `version(...)` whenever the service code changes, so Porter replaces a running instance instead
of reusing a stale one.

### Forwarding calls to a system service

`PorterBinderWrapper` wraps a system service's Binder so every transaction on it is re-issued by
Porter at its own identity:

```java
IPackageManager pm = IPackageManager.Stub.asInterface(
        new PorterBinderWrapper(ServiceManager.getService("package")));
pm.getInstalledPackages(0, 0);
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
`Porter.getAvailability(context)` distinguishes the cases:

```java
switch (Porter.getAvailability(this)) {
    case CONNECTED:               /* a connection is held and answers */         break;
    case INSTALLED_NOT_CONNECTED: promptUser("Open Porter and start the service"); break;
    case NOT_INSTALLED:           promptUser("Install Porter");                    break;
    case INSTALLED_UNRECOGNIZED:  promptUser("Another app owns Porter's permission"); break;
}
```

This reports whether a manager is installed, not whether its service is running, so
`INSTALLED_NOT_CONNECTED` is the normal state before the user starts Porter.
`INSTALLED_UNRECOGNIZED` means some package other than Porter declares Porter's permission; say so
rather than naming or launching that package.

## Multiple app processes

The provider that receives the connection is not multiprocess, so one process gets the Binder and
the others ask it for the connection.

Call this as early as possible, in a static initializer of your `Application` class, before any
provider runs:

```java
static {
    PorterApiProvider.enableMultiProcessSupport(BuildConfig.APPLICATION_ID.equals(currentProcessName));
}
```

Then in a process that is not the provider process:

```java
PorterApiProvider.requestBinderForNonProviderProcess(context);
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
- Porter restarted while your app is alive: the connection is replaced and your listener hears about
  it, without reusing a stale Binder reference.
- Your app restarted: a sticky listener is told about the existing connection immediately.
- Secondary processes: each one obtains the connection, and a process that started before Porter did
  can still get it.
- Your user service: replaced when you bump its version, and gone after an unbind with removal.

Link users to the [setup guide](/setup). It selects English or German from the browser language,
with a dropdown to override. The URL is the same in both languages. Keep instructions for your app's
own settings, and which of your app versions support Porter, in your own documentation.
