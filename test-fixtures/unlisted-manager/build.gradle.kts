plugins { id("com.android.application") }

android {
    namespace = "eu.darken.porter.unlistedmanager"
    defaultConfig {
        applicationId = "eu.darken.porter.unlistedmanager"
        versionCode = 1
        versionName = "1"
    }
}
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { it.enable = false }
