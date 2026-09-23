import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("porter.signing")
}

android {
    namespace = "eu.darken.porter.compat"
    lint {
        // CI runs lintVital, which reports fatal issues only, and a call the minimum SDK lacks crashes.
        fatal += "NewApi"
    }
    defaultConfig {
        applicationId = "moe.shizuku.privileged.api"
        // Its own contract number; the name still says which Porter release shipped it.
        versionCode = rootProject.extra["compatVersionCode"] as Int
        versionName = rootProject.extra["versionName"] as String
    }
    signingConfigs {
        create("sign")
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("sign") }
        release { signingConfig = signingConfigs.getByName("sign") }
    }
    applicationVariants.configureEach {
        val variant = this
        outputs.configureEach {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "porter-compat-v${variant.versionName}-${variant.name}.apk"
        }
    }
}


androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    configurations.create("embedded${variant.name.replaceFirstChar { it.uppercase() }}") {
        isCanBeConsumed = true
        isCanBeResolved = false
        outgoing.artifact(variant.artifacts.get(SingleArtifact.APK))
    }
}
