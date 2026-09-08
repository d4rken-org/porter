package moe.shizuku.manager;

import moe.shizuku.manager.utils.MultiLocaleEntity;

public class Helps {

    public static final MultiLocaleEntity ADB = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_ANDROID11 = new MultiLocaleEntity();
    public static final MultiLocaleEntity APPS = new MultiLocaleEntity();
    public static final MultiLocaleEntity HOME = new MultiLocaleEntity();
    public static final MultiLocaleEntity DOWNLOAD = new MultiLocaleEntity();
    public static final MultiLocaleEntity SUI = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_PERMISSION = new MultiLocaleEntity();

    public static final String WEBSITE = "https://porter.darken.eu/";
    public static final String SOURCE = "https://github.com/d4rken-org/porter";
    public static final String STOPPING = WEBSITE + "troubleshooting#porter-keeps-stopping";
    public static final String STARTUP = WEBSITE + "troubleshooting#automatic-start-does-not-work";

    static {
        ADB.put("en", WEBSITE + "setup#with-a-computer");
        ADB_ANDROID11.put("en", WEBSITE + "setup#wireless-debugging");
        APPS.put("en", WEBSITE + "compatibility");
        HOME.put("en", WEBSITE + "developers");
        DOWNLOAD.put("en", SOURCE + "/releases");
        ADB_PERMISSION.put("en", WEBSITE + "troubleshooting#access-is-allowed-but-an-operation-still-fails");
        SUI.put("en", "https://github.com/RikkaApps/Sui");
    }
}
