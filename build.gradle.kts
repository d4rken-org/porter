import com.android.build.gradle.BaseExtension
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("idea")
    id("com.android.library") apply false
    id("org.jetbrains.kotlin.android") apply false
}

idea.module {
    excludeDirs.add(file("out"))
}

subprojects {
    plugins.withId("com.android.base") {
        extensions.configure<BaseExtension>("android") {
            compileSdkVersion(37)
            buildToolsVersion = "37.0.0"
            ndkVersion = "29.0.13113456"
            defaultConfig {
                minSdk = 24
                targetSdk = 37
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }
    // The SDK modules included from the api checkout pin their own Java target, so Kotlin follows
    // whatever javac ends up with in each module rather than one number for the whole build.
    plugins.withId("org.jetbrains.kotlin.android") {
        afterEvaluate {
            val android = extensions.getByType<BaseExtension>()
            extensions.configure<KotlinAndroidProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.fromTarget(android.compileOptions.targetCompatibility.majorVersion))
            }
        }
    }
}

val versioning = Properties().also { props -> file("version.properties").inputStream().use { props.load(it) } }
val major = versioning.getProperty("project.versioning.major").toInt()
val minor = versioning.getProperty("project.versioning.minor").toInt()
val patch = versioning.getProperty("project.versioning.patch").toInt()
val build = versioning.getProperty("project.versioning.build").toInt()
extra["versionCode"] = major * 10000000 + minor * 100000 + patch * 1000 + build * 10
extra["versionName"] = "$major.$minor.$patch-${versioning.getProperty("project.versioning.type")}$build"
