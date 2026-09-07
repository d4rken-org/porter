package eu.darken.porter.compat;

import android.content.Context;
import android.content.pm.PackageManager;

final class PorterIdentity {
    static final String PACKAGE = "eu.darken.porter";

    static boolean isInstalled(Context context) {
        return context.getPackageManager().checkSignatures(context.getPackageName(), PACKAGE)
                == PackageManager.SIGNATURE_MATCH;
    }

    private PorterIdentity() {}
}
