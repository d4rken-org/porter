plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eu.darken.porter.probe"
    defaultConfig {
        applicationId = "eu.darken.porter.probe"
        versionCode = 1
        versionName = "1"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The probe applies no signing script, so a release variant is unsigned and uninstallable
            // without this. The debug key is what every other probe flavour is already signed with.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { aidl = true }
    flavorDimensions += "backend"
    productFlavors {
        create("porter") { applicationIdSuffix = ".native" }
        create("bridge") { applicationIdSuffix = ".bridge" }
        create("legacy") { applicationIdSuffix = ".legacy" }
        create("terminal") { applicationIdSuffix = ".terminal" }
    }
    // Two activities of the same name, one per SDK. The two flavours that speak the Shizuku API
    // share the one under src/shizuku; the two that use the Porter SDK share the one under src/sdk.
    sourceSets {
        getByName("porter") { java.srcDirs("src/sdk/java") }
        getByName("bridge") { java.srcDirs("src/sdk/java") }
        getByName("legacy") { java.srcDirs("src/shizuku/java") }
        getByName("terminal") { java.srcDirs("src/shizuku/java") }
    }
    lint {
        // A non-shipping fixture, like :shell. Lint-vital runs on release variants of application
        // modules and would gate the emulator matrix on the probe's style.
        checkReleaseBuilds = false
    }
}

androidComponents {
    // Build types multiply across flavours. Only the bridge pairs the SDK with shizuku-compat, and
    // only its keep rules have been considered; a legacyRelease would shrink
    // dev.rikka.shizuku:provider, which ships no consumer rules, into a variant nothing builds.
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = variant.productFlavors.any { it.second == "bridge" }
    }
}

dependencies {
    "legacyImplementation"("dev.rikka.shizuku:api:13.1.5")
    "legacyImplementation"("dev.rikka.shizuku:provider:13.1.5")
    "terminalImplementation"("dev.rikka.shizuku:api:13.1.5")
    "terminalImplementation"("dev.rikka.shizuku:provider:13.1.5")
    "porterImplementation"(project(":sdk"))
    // For the app-owned transaction code the probe proves is refused to a client.
    "porterImplementation"(project(":common"))
    "bridgeImplementation"(project(":common"))
    // dev.rikka.shizuku:provider is deliberately absent: it ships the same
    // moe.shizuku.api.BinderContainer class as :shizuku-compat and the two do not dex together.
    "bridgeImplementation"(project(":sdk"))
    "bridgeImplementation"(project(":shizuku-compat"))
    // Only the two flavours built on src/sdk forward a system service call, and only they can:
    // the wrapper that does it is the Porter SDK's. The bypass is part of that, not a workaround
    // for the fixture: an app reaching a system service through the wrapper links the hidden
    // method itself, and the platform blocks that from an app without one. The manager ships the
    // same pair for the same reason.
    "porterImplementation"(libs.hidden.compat)
    "bridgeImplementation"(libs.hidden.compat)
    "porterImplementation"("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    "bridgeImplementation"("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    compileOnly(libs.hidden.stub)
}
