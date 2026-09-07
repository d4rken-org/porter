package eu.darken.porter.compat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public class OpenPorterActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent launch = PorterIdentity.isInstalled(this)
                ? getPackageManager().getLaunchIntentForPackage(PorterIdentity.PACKAGE) : null;
        if (launch != null) {
            try {
                startActivity(launch);
                finish();
                return;
            } catch (ActivityNotFoundException ignored) {}
        }
        Toast.makeText(this, R.string.porter_missing, Toast.LENGTH_LONG).show();
        finish();
    }
}
