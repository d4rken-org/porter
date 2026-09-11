# Service update fixture

This separate debug-only APK supplies synthetic running services for testing Porter updates. It is
not a dependency of the manager and has no launcher activity. Normal upgrade tests should use an
actual previous Porter APK. These modes exercise failure and compatibility boundaries:

| Mode | Behavior |
| --- | --- |
| `outdated` | Current service implementation reporting an older build identity |
| `legacy` | PID-only diagnostics; discovery and global access transactions unavailable |
| `incompatible` | Unsupported discovery/global access protocol; throws if the manager attempts a global write |
| `restricted` | Reports missing runtime-permission management privilege |
| `preflight` | Substitutes PID 1 in the real native replacement command, which must reject it before killing anything |
| `handoff-failure` | Kills only its own service process after accepting replacement, without starting a successor |
| `slow` | Delays the real replacement command three seconds for cancellation and concurrent requests |

Build `:service-update-fixture:assembleDebug`, install alongside Porter, and launch through ADB as
UID 2000 or root. Set `CLASSPATH` to the fixture APK and `-Dshizuku.library.path` to the installed
Porter native library directory. Use `app_process /system/bin --nice-name=porter_server
eu.darken.porter.updatefixture.FixtureService MODE` after those VM properties. Keep stdout and stdin
detached. The process deliberately has the same name as Porter so the real starter can replace it.

Use only isolated test devices. Save the initial emulator snapshot with the old service running and
the current Porter app open; test the action, record PID/build/UID and UI results, then restore that
snapshot. Background tests must use a real APK reinstall to deliver `MY_PACKAGE_REPLACED` while the
manager is backgrounded. A matching service and an opted-in stopped service must stay unchanged.

The root scenario can start the old service from userdebug `adb root`, followed by `adb unroot`.
This tests replacement retaining UID 0; it does not test acquiring root through a particular `su`
implementation. Independent Shizuku and attached Porter clients should be checked separately.

The `restricted` mode also exposes 60 clearly named synthetic, unsupported applications for scrolling and large-text UI checks. They are display fixtures, not installed client packages.
