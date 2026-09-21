plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // The same AGP and Kotlin plugin the build uses, declared once more here because buildSrc
    // compiles before the settings plugin block is read. Both belong on this classpath: AGP looks
    // the Kotlin plugin up beside itself, and a buildSrc that carried only AGP would hide the one
    // the settings block resolves.
    implementation("com.android.tools.build:gradle:8.11.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
}
