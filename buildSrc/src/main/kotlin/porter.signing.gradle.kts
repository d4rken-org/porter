import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Properties

/*
 * Applied by the application modules that ship a signed APK. Fills the "sign" signing config from
 * CI environment variables, else from signing.properties, else from the debug key, and refuses a
 * release build that would fall through to the debug key unless it is declared development-only.
 */

val signingFile = rootProject.file("signing.properties")
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
            listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEYSTORE_ALIAS", "KEYSTORE_ALIAS_PASSWORD").forEach { key ->
                if (signingProperties.getProperty(key).isNullOrEmpty()) throw GradleException("Missing $key in signing.properties")
            }
            sign.storeFile = rootProject.file(signingProperties.getProperty("KEYSTORE_FILE"))
            sign.storePassword = signingProperties.getProperty("KEYSTORE_PASSWORD")
            sign.keyAlias = signingProperties.getProperty("KEYSTORE_ALIAS")
            sign.keyPassword = signingProperties.getProperty("KEYSTORE_ALIAS_PASSWORD")
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
            throw GradleException("Release signing requires CI signing variables or signing.properties. Local testing only: -Pporter.developmentSigning=true")
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease") {
        dependsOn(verifyReleaseSigning)
    }
}
