import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenLocal()
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }
    plugins {
        id("com.android.application") version "8.11.1"
        id("com.android.library") version "8.11.1"
        id("org.jetbrains.kotlin.android") version "2.2.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
        id("dev.rikka.tools.refine") version "4.4.0"
        id("com.android.compose.screenshot") version "0.0.1-alpha16"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenLocal()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
    }
    versionCatalogs {
        create("libs") {
            version("hidden-api", "4.4.0")
            library("hidden-compat", "dev.rikka.hidden", "compat").versionRef("hidden-api")
            library("hidden-stub", "dev.rikka.hidden", "stub").versionRef("hidden-api")

            version("refine", "4.4.0")
            library("refine-runtime", "dev.rikka.tools.refine", "runtime").versionRef("refine")
        }
    }
}

include(":server", ":starter", ":shell", ":manager", ":common", ":compat", ":probe")
include(":service-update-fixture")
project(":service-update-fixture").projectDir = file("test-fixtures/service-update")
include(":unlisted-manager-fixture")
project(":unlisted-manager-fixture").projectDir = file("test-fixtures/unlisted-manager")

var root = "api"

val propFile = file("local.properties")
if (propFile.canRead()) {
    val props = Properties()
    propFile.inputStream().use { props.load(it) }
    if (props["api.useLocal"] == "true") {
        root = props["api.dir"] as String
    }
}

fun includeSdkModule(name: String) {
    include(":$name")
    project(":$name").projectDir = file("$root${File.separator}$name")
}

includeSdkModule("aidl")
includeSdkModule("porsh")
includeSdkModule("shared")
includeSdkModule("server-shared")

// Present only in newer SDK checkouts; skipped when the pinned commit predates them. Either script
// name counts: the checkout may predate the SDK's move to Kotlin DSL.
listOf("protocol", "manager-protocol", "sdk", "shizuku-compat").forEach { name ->
    val dir = file("$root${File.separator}$name")
    if (File(dir, "build.gradle.kts").isFile || File(dir, "build.gradle").isFile) {
        includeSdkModule(name)
    }
}
