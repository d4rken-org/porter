package eu.darken.porter.starter

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the user-service start command through a real `sh`, with a stand-in for app_process that
 * records the argument vector it was given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserServiceCommandTest {

    @get:Rule
    val dir = TemporaryFolder()

    private fun argvOf(className: String, suffix: String?, debug: Boolean = false): List<String> {
        val out = File(dir.root, "argv")
        val appProcess = File(dir.root, "app_process")
        appProcess.writeText("#!/bin/sh\nprintf '%s\\0' \"\$@\" > '${out.path}'\n")
        appProcess.setExecutable(true)

        val command = ServiceStarter.commandForUserService(
            appProcess.path, "/data/app/porter/base.apk", "eu.darken.porter", "token-1",
            "com.example.app", className, suffix, 10123, setOf("ab12", "cd34"), debug,
        )
        val shell = ProcessBuilder("/bin/sh", "-c", "$command wait").directory(dir.root).start()
        shell.waitFor(10, TimeUnit.SECONDS)
        return out.readText().split('\u0000').dropLast(1)
    }

    @Test
    fun anOrdinaryLaunchPassesTheSameArguments() {
        val argv = argvOf("com.example.app.Service", "service")

        assertEquals(
            listOf(
                "/system/bin",
                "--nice-name=com.example.app:service",
                "eu.darken.porter.starter.ServiceStarter",
                "--manager=eu.darken.porter",
                "--token=token-1",
                "--package=com.example.app",
                "--class=com.example.app.Service",
                "--uid=10123",
                "--signers=ab12,cd34",
            ),
            argv,
        )
    }

    @Test
    fun hostileNamesStayOneArgumentEachAndRunNothing() {
        val marker = File(dir.root, "pwned")
        val className = "x'; touch '${marker.path}'; '"
        val suffix = "a --package=other.app \$(touch ${marker.path})"

        val argv = argvOf(className, suffix, debug = true)

        assertFalse(marker.exists())
        assertEquals("--class=$className", argv.single { it.startsWith("--class=") })
        assertEquals("--nice-name=com.example.app:$suffix", argv.single { it.startsWith("--nice-name=") })
        assertEquals("--debug-name=com.example.app:$suffix", argv.single { it.startsWith("--debug-name=") })
        assertEquals(listOf("--package=com.example.app"), argv.filter { it.startsWith("--package=") })
    }
}
