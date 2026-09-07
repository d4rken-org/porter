# Development validation

Local validation on 7 September 2026, using isolated emulators. These results establish a development baseline, not a production release or an OEM compatibility guarantee.

## Builds

Java 21, pinned upstream API submodule, Android build tools 37.0.0.

- Manager, companion, client adapter and both probe debug variants compile.
- Manager and companion release variants pass R8/resource shrinking with explicit development signing.
- The signing guard rejects a release without signing configuration.
- APK manifests declare the intended distinct permission owners. Manager and companion signing certificates match.
- Server routing unit tests: 3 passed.
- Two existing client apps with direct Porter integration compile and pass their focused wrapper and setup tests. Coverage includes manager discovery and selecting an absent Porter without falling back to Shizuku.

## Android 16 (API 36)

- Original `v13.7.0-thedjchi` release and Porter install together and run separate shell server processes.
- Native and unchanged legacy probes receive prompts from Porter and Shizuku respectively, start their user services as uid 2000, and read a shell-only file.
- Restarting either server preserves the other server; clients reconnect to their selected server.
- Android rejects the Porter companion as an update to the differently signed original package. After removing the original, the companion installs and the unchanged legacy probe receives Porter's prompt and user service.
- Both Porter and original-release rish loaders execute a shell command through the companion from an approved legacy client. A terminal fixture declaring neither API permission receives explicit approval and runs as shell; the original loader keeps waiting when the approval dialog remains open beyond five seconds. Removing the companion revokes legacy grants and removes their user services.
- Denial produces no privileged user service. Approved native and legacy probes cannot call Porter manager-only grant operations.
- Revoking either API permission with Android's package manager removes the Porter decision and destroys the corresponding probe user service. A subsequent request prompts again.
- Integrated client apps save their service choice, preserve the active backend until process restart, open the selected manager and connect their privileged helpers after approval.
- Root mode starts a uid 0 probe service. After stopping root mode and starting ADB mode, the grant persists and the probe runs as uid 2000. The authorization file remains owned by shell with mode 0600.

## Android 17 (API 37, 16 KB pages)

- Development-signed release APK starts the native probe user service as uid 2000 and reads the shell-only fixture.
- Runtime permission revocation removes the grant and destroys the user service.
- Original Shizuku and Porter run concurrently. The unchanged legacy probe works with the original.
- Explicitly selecting Shizuku in the dual-provider probe connects to Shizuku. Stopping Shizuku disconnects the probe while Porter remains running.

## Published legacy client with compatibility companion

An unmodified published client APK was tested on a fresh Android 16 API 36 emulator with the development-signed Porter 0.1.0 release artifacts. The client requests only the original Shizuku API permission; original Shizuku was absent.

- Approval granted the legacy permission and started the client's user service as shell (UID 2000).
- With root and accessibility disabled, the client successfully force-stopped another app. Android recorded the caller as the client's shell helper.
- Revoking the legacy permission removed Porter's grant and the helper process. Reopening the client prompted again; denial left no privileged helper running.
- Android's crash buffer was empty. DebugBadger's crash heuristic reported unrelated runtime startup/shutdown lines and a missing marker, so that heuristic did not provide reliable crash evidence.

The detailed client-specific record and device evidence are retained locally under `out/porter-0.1.0-development/`. App-specific setup instructions and supported-version information belong to the client apps.

## Remaining physical-device coverage

Wireless pairing, vendor-specific background restrictions, work profiles/secondary users and production root implementations still require device beta testing. Emulator ADB startup exercises the server and client paths, not the wireless pairing UX. The artwork and signing certificates used here are for development.
