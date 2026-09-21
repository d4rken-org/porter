import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine")
}

evaluationDependsOn(":manager")

android {
    namespace = "eu.darken.porter.shell"
    defaultConfig {
        versionCode = rootProject.extra["versionCode"] as Int
        versionName = rootProject.extra["versionName"] as String
        buildConfigField("String", "MANAGER_APPLICATION_ID", "\"${project(":manager").extensions.getByType<ApplicationExtension>().defaultConfig.applicationId}\"")
        buildConfigField("int", "LOADER_VERSION", "${rootProject.extra["porshLoaderVersion"]}")
    }
    buildTypes {
        debug {
            multiDexEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
    lint {
        checkReleaseBuilds = false
    }
    buildFeatures {
        buildConfig = true
    }
}

android.applicationVariants.all {
    val variant = this
    outputs.all {
        val outDir = File(rootDir, "out")
        val mappingPath = File(outDir, "mapping").absolutePath
        variant.assembleProvider.get().doLast {
            if (variant.buildType.isMinifyEnabled) {
                copy {
                    from(variant.mappingFileProvider.get())
                    into(mappingPath)
                    rename { mappingPath + File.separator + "cmd-v${variant.versionName}.txt" }
                }
            }
        }
    }
}

androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    configurations.create("porshDex${variant.name.replaceFirstChar { it.uppercase() }}") {
        isCanBeConsumed = true
        isCanBeResolved = false
        outgoing.artifact(variant.artifacts.get(SingleArtifact.APK))
    }
}

dependencies {
    implementation(libs.hidden.compat)
    compileOnly(libs.hidden.stub)
}
