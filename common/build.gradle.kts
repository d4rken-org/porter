import java.security.MessageDigest

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

abstract class GeneratePorterBuildIdentity : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceInputs: ConfigurableFileCollection
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val rootDir = project.rootDir.toPath()
        val digest = MessageDigest.getInstance("SHA-256")
        sourceInputs.files.sortedBy { rootDir.relativize(it.toPath()).toString() }.forEach { source ->
            digest.update(rootDir.relativize(source.toPath()).toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(source.readBytes())
            digest.update(0)
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        val output = outputDirectory.file("eu/darken/porter/common/PorterBuildIdentity.java").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            """
            |package eu.darken.porter.common;
            |public final class PorterBuildIdentity {
            |    public static final String ID = "$id";
            |    public static final String DIAGNOSTICS_KEY = "eu.darken.porter.build_id";
            |}
            |""".trimMargin(),
        )
    }
}

val identity = tasks.register<GeneratePorterBuildIdentity>("generatePorterBuildIdentity") {
    sourceInputs.from(rootProject.fileTree(rootProject.rootDir) {
        include("*.gradle.kts", "buildSrc/build.gradle.kts", "buildSrc/src/**", "gradle/**", "gradle.properties", "version.properties")
    })
    val sdkModules = listOf("aidl", "shared", "server-shared", "porsh") +
        listOf("protocol", "manager-protocol", "sdk", "shizuku-compat").filter { rootProject.findProject(":$it") != null }
    (listOf("manager", "server", "common", "starter", "shell", "compat") + sdkModules).forEach { name ->
        sourceInputs.from(rootProject.fileTree(rootProject.project(":$name").projectDir) {
            include("src/**", "*.gradle", "*.gradle.kts", "gradle.properties")
            exclude("src/test*/**", "src/androidTest*/**", "src/screenshotTest*/**")
        })
    }
    outputDirectory.set(layout.buildDirectory.dir("generated/porterIdentity"))
}

androidComponents.onVariants(androidComponents.selector().all()) { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(identity, GeneratePorterBuildIdentity::outputDirectory)
}

android {
    namespace = "eu.darken.porter.common"
    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation(project(":protocol"))
    compileOnly(libs.hidden.stub)
}
