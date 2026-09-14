package moe.shizuku.manager.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

public final class ClipboardUtils {

    private ClipboardUtils() {
    }

    public static boolean put(Context context, CharSequence text) {
        return put(context, ClipData.newPlainText("label", text));
    }

    public static boolean put(Context context, ClipData clipData) {
        try {
            ((ClipboardManager) context.getSystemService("clipboard")).setPrimaryClip(clipData);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
