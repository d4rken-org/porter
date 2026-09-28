import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Properties

/*
 * Applied by the application modules that ship a signed APK. Fills the "sign" signing config from
 * CI environment variables, else from ~/.config/projects/eu.darken.porter/signing-foss.properties,
 * else from the debug key, and refuses a release build that would fall through to the debug key
 * unless it is declared development-only.
 */

val signingFile = File(System.getProperty("user.home"), ".config/projects/eu.darken.porter/signing-foss.properties")
val developmentSigning = providers.gradleProperty("porter.developmentSigning").orNull == "true"
val signingEnv = listOf("STORE_PATH", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith { System.getenv(it) }
val hasEnvSigning = signingEnv.values.all { !it.isNullOrEmpty() }
if (signingEnv.values.any { !it.isNullOrEmpty() } && !hasEnvSigning) {
    throw GradleException("CI signing requires STORE_PATH, STORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD")
}

plugins.withId("com.android.application") {
    // After the module's own android block, which is where the "sign" config is created.
    extensions.getByType<ApplicationAndroidComponentsExtension>().finalizeDsl { android ->
        val sign = android.signingConfigs.getByName("sign")
        if (hasEnvSigning) {
            sign.storeFile = file(signingEnv.getValue("STORE_PATH"))
            sign.storePassword = signingEnv["STORE_PASSWORD"]
            sign.keyAlias = signingEnv["KEY_ALIAS"]
            sign.keyPassword = signingEnv["KEY_PASSWORD"]
        } else if (signingFile.canRead()) {
            val signingProperties = Properties().also { props -> signingFile.inputStream().use { props.load(it) } }
            listOf("release.storePath", "release.storePassword", "release.keyAlias", "release.keyPassword").forEach { key ->
                if (signingProperties.getProperty(key).isNullOrEmpty()) throw GradleException("Missing $key in $signingFile")
            }
            sign.storeFile = signingFile.parentFile.resolve(signingProperties.getProperty("release.storePath"))
            sign.storePassword = signingProperties.getProperty("release.storePassword")
            sign.keyAlias = signingProperties.getProperty("release.keyAlias")
            sign.keyPassword = signingProperties.getProperty("release.keyPassword")
        } else {
            val debug = android.signingConfigs.getByName("debug")
            sign.storeFile = debug.storeFile
            sign.storePassword = debug.storePassword
            sign.keyAlias = debug.keyAlias
            sign.keyPassword = debug.keyPassword
        }
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        if (!hasEnvSigning && !signingFile.canRead() && !developmentSigning) {
            throw GradleException("Release signing requires CI signing variables or $signingFile. Local testing only: -Pporter.developmentSigning=true")
        }
    }
}
tasks.configureEach {
    // Flavored modules name these per variant: preFossReleaseBuild, validateSigningGplayRelease.
    if ((name.startsWith("pre") && name.endsWith("ReleaseBuild")) ||
        (name.startsWith("validateSigning") && name.endsWith("Release"))
    ) {
        dependsOn(verifyReleaseSigning)
    }
}
