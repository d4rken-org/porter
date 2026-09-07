package moe.shizuku.manager;

import moe.shizuku.manager.utils.MultiLocaleEntity;

public class Helps {

    public static final MultiLocaleEntity ADB = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_ANDROID11 = new MultiLocaleEntity();
    public static final MultiLocaleEntity APPS = new MultiLocaleEntity();
    public static final MultiLocaleEntity HOME = new MultiLocaleEntity();
    public static final MultiLocaleEntity DOWNLOAD = new MultiLocaleEntity();
    public static final MultiLocaleEntity SUI = new MultiLocaleEntity();
    public static final MultiLocaleEntity RISH = new MultiLocaleEntity();
    public static final MultiLocaleEntity ADB_PERMISSION = new MultiLocaleEntity();

    public static final String WEBSITE = "https://d4rken-org.github.io/porter/";
    public static final String SOURCE = "https://github.com/d4rken-org/porter";
    public static final String STOPPING = WEBSITE + "troubleshooting.html#porter-keeps-stopping";
    public static final String STARTUP = WEBSITE + "troubleshooting.html#automatic-start-does-not-work";

    static {
        ADB.put("en", WEBSITE + "setup.html#with-a-computer");
        ADB_ANDROID11.put("en", WEBSITE + "setup.html#wireless-debugging");
        APPS.put("en", WEBSITE + "compatibility.html");
        HOME.put("en", SOURCE + "/blob/HEAD/.github/maintainer/client-integration.md");
        DOWNLOAD.put("en", SOURCE + "/releases");
        ADB_PERMISSION.put("en", WEBSITE + "troubleshooting.html#access-is-allowed-but-an-operation-still-fails");
        SUI.put("en", "https://github.com/RikkaApps/Sui");
        RISH.put("en", WEBSITE + "terminal.html");
    }
}
