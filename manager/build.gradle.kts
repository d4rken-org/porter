import com.android.build.api.artifact.SingleArtifact
import com.android.build.gradle.internal.tasks.CompileArtProfileTask
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

abstract class EmbedCompatibilityApk : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectories: ConfigurableFileCollection
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun embed() {
        val apks = apkDirectories.asFileTree.matching { include("**/*.apk") }.files
        if (apks.size != 1) throw GradleException("Expected one signed compatibility APK, found ${apks.size}")
        val target = outputDirectory.file("compat/porter-compat.apk").get().asFile
        target.parentFile.mkdirs()
        Files.copy(apks.first().toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

abstract class ExtractPorshDex : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectories: ConfigurableFileCollection
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val apks = apkDirectories.asFileTree.matching { include("**/*.apk") }.files
        if (apks.size != 1) throw GradleException("Expected one shell APK, found ${apks.size}")
        val apk = apks.first()
        val target = outputDirectory.file("porsh.dex").get().asFile
        target.parentFile.mkdirs()
        ZipFile(apk).use { archive ->
            val dexes = archive.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
            if (dexes.size != 1) throw GradleException("Expected one dex in $apk, found ${dexes.map { it.name }}")
            archive.getInputStream(dexes.first()).use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

abstract class VerifyLocaleConfig : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val text = mergedManifest.get().asFile.readText()
        if (!text.contains("android:localeConfig=")) {
            throw GradleException("Merged manifest is missing android:localeConfig; Porter will not appear in the system per-app language picker")
        }
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("porter.signing")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.rikka.tools.refine")
    id("com.android.compose.screenshot")
}

android {
    namespace = "eu.darken.porter.manager"
    defaultConfig {
        applicationId = "eu.darken.porter"
        versionCode = rootProject.extra["versionCode"] as Int
        versionName = rootProject.extra["versionName"] as String
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
        prefab = true
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    signingConfigs {
        create("sign")
    }
    flavorDimensions += "version"
    productFlavors {
        create("foss") {
            dimension = "version"
            buildConfigField("boolean", "IS_FOSS", "true")
        }
        create("gplay") {
            dimension = "version"
            buildConfigField("boolean", "IS_FOSS", "false")
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sign")
        }
        release {
            signingConfig = signingConfigs.getByName("sign")
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles("proguard-rules.pro")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.31.0+"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.maxHeapSize = "1g" }
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "**"
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
    androidResources {
        generateLocaleConfig = true
        val translated = file("src/main/res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.matches(Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")) }
            .map { it.name.substring("values-".length) }
        val locales = listOf("en") + translated
        if (translated.isEmpty() || !locales.contains("en")) {
            throw GradleException("Derived locale filters look wrong: $locales")
        }
        localeFilters.addAll(locales)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    val variantName = variant.name.replaceFirstChar { it.uppercase() }
    val buildTypeName = variant.buildType!!.replaceFirstChar { it.uppercase() }
    // Refine must leave Robolectric's signed JVM crypto provider byte-for-byte intact.
    variant.unitTest?.instrumentation?.excludes?.addAll(listOf("org/conscrypt/**", "org/bouncycastle/**"))
    val porshDex = configurations.create("${variant.name}PorshDex") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies.add(porshDex.name, dependencies.project(":shell", configuration = "porshDex$buildTypeName"))
    val porshTask = tasks.register<ExtractPorshDex>("extract${variantName}PorshDex") {
        apkDirectories.from(porshDex)
        outputDirectory.set(layout.buildDirectory.dir("generated/porshAssets/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(porshTask, ExtractPorshDex::outputDirectory)
    val localeConfigTask = tasks.register<VerifyLocaleConfig>("verify${variantName}LocaleConfig") {
        mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
    tasks.matching { it.name == "assemble$variantName" }.configureEach {
        dependsOn(localeConfigTask)
    }
    if (variant.productFlavors.any { it.second == "foss" }) {
        val embedded = configurations.create("${variant.name}CompatibilityApk") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies.add(embedded.name, dependencies.project(":compat", configuration = "embedded$buildTypeName"))
        val task = tasks.register<EmbedCompatibilityApk>("embed${variantName}Compatibility") {
            apkDirectories.from(embedded)
            outputDirectory.set(layout.buildDirectory.dir("generated/compatibilityAssets/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, EmbedCompatibilityApk::outputDirectory)
    }
}

android.applicationVariants.configureEach {
    val variant = this
    // Retained to trigger the shell's R8 mapping export, which runs in assemble's doLast. Task
    // ordering for the dex itself comes from the porshDex configuration dependency.
    variant.preBuildProvider.configure {
        dependsOn(":shell:assemble${variant.buildType.name.replaceFirstChar { it.uppercase() }}")
    }
    variant.outputs.configureEach {
        val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        output.outputFileName = "porter-v${variant.versionName}-${if (variant.flavorName == "foss") variant.buildType.name else variant.name}.apk"

        variant.assembleProvider.get().doLast {
            val outDir = File(rootDir, "out")
            val mappingDir = File(outDir, "mapping").absolutePath
            val apkDir = File(outDir, "apk").absolutePath

            if (variant.buildType.isMinifyEnabled) {
                copy {
                    from(variant.mappingFileProvider.get())
                    into(mappingDir)
                    rename { "mapping-${variant.versionName}-${variant.name}.txt" }
                }
                copy {
                    from(output.outputFile)
                    into(apkDir)
                }
            }
        }
    }
}

tasks.withType<CompileArtProfileTask>().configureEach {
    enabled = false
}
configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "screenshotTestImplementation"(platform("androidx.compose:compose-bom:2025.09.01"))
    "screenshotTestImplementation"("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
    "screenshotTestImplementation"("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(project(":common"))
    implementation(project(":server"))
    implementation(project(":porsh"))
    implementation(project(":starter"))
    implementation(project(":sdk"))
    // Not for the manager's own calls: the server and the starter declare it compileOnly and load
    // moe.shizuku.api.BinderContainer out of this APK at runtime for Shizuku-wire delivery.
    implementation(project(":shizuku-compat"))

    implementation(libs.hidden.compat)
    compileOnly(libs.hidden.stub)

    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.fragment:fragment-ktx:1.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.4")

    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")

    implementation("io.github.vvb2060.ndk:boringssl:20250114")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.lsposed.libcxx:libcxx:27.0.12077973")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}

