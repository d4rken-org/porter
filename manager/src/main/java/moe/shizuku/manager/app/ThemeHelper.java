package moe.shizuku.manager.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import androidx.annotation.StyleRes;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.snackbar.Snackbar;
import moe.shizuku.manager.R;
import moe.shizuku.manager.ShizukuSettings;
import moe.shizuku.manager.utils.EnvironmentUtils;
import rikka.core.util.ResourceUtils;

public class ThemeHelper {

    public static String getThemeStyle() {
        return ShizukuSettings.getPreferences().getString(ShizukuSettings.Keys.KEY_THEME_STYLE, "DEFAULT");
    }

    public static String getThemeColor() {
        return ShizukuSettings.getPreferences().getString(ShizukuSettings.Keys.KEY_THEME_COLOR, "BLUE");
    }

    public static boolean isUsingSystemColor() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && "MATERIAL_YOU".equals(getThemeStyle());
    }

    public static String getTheme(Context context) {
        return getThemeStyle() + ":" + getThemeColor();
    }

    @StyleRes
    public static int getThemeStyleRes(Context context) {
        boolean dark = ResourceUtils.isNightMode(context.getResources().getConfiguration());
        int contrast = "HIGH_CONTRAST".equals(getThemeStyle()) ? 2 : "MEDIUM_CONTRAST".equals(getThemeStyle()) ? 1 : 0;
        int index = contrast * 2 + (dark ? 1 : 0);
        switch (getThemeColor()) {
            case "GREEN":
                return new int[] {
                    R.style.PorterPalette_Green_LightDefault, R.style.PorterPalette_Green_DarkDefault, R.style.PorterPalette_Green_LightMediumContrast, R.style.PorterPalette_Green_DarkMediumContrast, R.style.PorterPalette_Green_LightHighContrast, R.style.PorterPalette_Green_DarkHighContrast
                }[index];
            case "AMOLED":
                return new int[] {
                    R.style.PorterPalette_Amoled_LightDefault, R.style.PorterPalette_Amoled_DarkDefault, R.style.PorterPalette_Amoled_LightMediumContrast, R.style.PorterPalette_Amoled_DarkMediumContrast, R.style.PorterPalette_Amoled_LightHighContrast, R.style.PorterPalette_Amoled_DarkHighContrast
                }[index];
            default:
                return new int[] {
                    R.style.PorterPalette_Blue_LightDefault, R.style.PorterPalette_Blue_DarkDefault, R.style.PorterPalette_Blue_LightMediumContrast, R.style.PorterPalette_Blue_DarkMediumContrast, R.style.PorterPalette_Blue_LightHighContrast, R.style.PorterPalette_Blue_DarkHighContrast
                }[index];
        }
    }

    public static void applySnackbarTheme(Context context, Snackbar snackbar) {
        snackbar.setBackgroundTint(resolveColor(context, R.attr.colorPrimaryContainer))
            .setTextColor(resolveColor(context, R.attr.colorOnSurface))
            .setActionTextColor(resolveColor(context, R.attr.colorPrimary));
    }

    private static int resolveColor(Context context, int color) {
        TypedValue typedValue = new TypedValue();
        context.getTheme().resolveAttribute(color, typedValue, true);
        return typedValue.data;
    }
}
