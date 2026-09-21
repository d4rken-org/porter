import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine")
}

evaluationDependsOn(":manager")

android {
    namespace = "eu.darken.porter.starter"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "MANAGER_APPLICATION_ID", "\"${project(":manager").extensions.getByType<ApplicationExtension>().defaultConfig.applicationId}\"")
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(project(":server-shared"))
    implementation("androidx.annotation:annotation:1.3.0")
    implementation(libs.hidden.compat)
    compileOnly(libs.hidden.stub)
    implementation(libs.refine.runtime)
}
