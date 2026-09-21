import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine")
}

evaluationDependsOn(":manager")

android {
    testOptions { unitTests.isIncludeAndroidResources = true }
    namespace = "eu.darken.porter.privileged"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "PORTER_VERSION_NAME", "\"${rootProject.extra["versionName"]}\"")
        buildConfigField("int", "PORTER_VERSION_CODE", "${rootProject.extra["versionCode"]}")
        buildConfigField("String", "MANAGER_APPLICATION_ID", "\"${project(":manager").extensions.getByType<ApplicationExtension>().defaultConfig.applicationId}\"")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("com.google.code.gson:gson:2.13.1")
    api("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")

    implementation(project(":aidl"))
    implementation(project(":common"))
    implementation(project(":shared"))
    compileOnly(project(":shizuku-compat"))
    implementation(project(":starter"))
    implementation(project(":porsh"))
    implementation(project(":server-shared"))
    implementation(libs.hidden.compat)
    compileOnly(libs.hidden.stub)
}
