# Integrating Porter with an existing Shizuku client

Keep the `dev.rikka.shizuku:api` and `provider` 13.1.5 dependencies. The `client` module contains a small adapter; it does not replace the SDK. Its Java sources may also be included directly under their existing package when a published adapter artifact is unavailable.

Add the Porter permission and replace the application's existing Shizuku provider declaration with these two declarations:

```xml
<uses-permission android:name="eu.darken.porter.permission.API_V23" />
<application>
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
```

Use `PorterClient.getActiveBackend(context)` to decide which manager discovery permission to inspect. For Porter, use `PorterClient.getPorterPackage(context)`. For Shizuku, retain existing discovery logic. Open the selected manager, not whichever permission owner happens to be listed first.

`PorterClient.setBackendForNextProcess(context, Backend.PORTER)` or `Backend.SHIZUKU` persists an explicit choice. `Backend.AUTO` prefers installed Porter at process initialization. Check the boolean persistence result. Inform users that a changed choice takes effect after restarting the app; do not hot-swap a connected Shizuku singleton or silently kill ongoing work.

Use ordinary Shizuku listeners, `checkSelfPermission`, `requestPermission`, Binder wrappers and user services after connection. Authorization is specific to the selected service. Receiving a binder does not imply authorization.

For a Porter-only integration, remove the original uses-permission contributed by the SDK with `tools:node="remove"`. Keep the `.shizuku` provider when using SDK multiprocess sharing, because the SDK uses that authority for intra-app lookup.

Test with both managers running, with each selected backend stopped, with denied/revoked permissions, and after restarting the selected server. The inactive backend must not silently take over.
