package rikka.shizuku.server;

import android.content.Context;
import android.content.pm.PackageManager;
import java.lang.reflect.Proxy;
import java.util.function.IntConsumer;

final class PermissionObserver {
    static void register(IntConsumer changed) throws ReflectiveOperationException {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object thread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = activityThreadClass.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(thread);
        Class<?> listenerClass = Class.forName("android.content.pm.PackageManager$OnPermissionsChangedListener");
        Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onPermissionsChanged": changed.accept((Integer) args[0]); return null;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        case "toString": return "Porter permission observer";
                        default: return null;
                    }
                });
        PackageManager.class.getMethod("addOnPermissionsChangeListener", listenerClass)
                .invoke(context.getPackageManager(), listener);
    }

    private PermissionObserver() {}
}
