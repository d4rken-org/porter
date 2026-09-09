package moe.shizuku.manager.compatibility

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OriginalShizukuServiceTest {
    private val stat = "44 (app_process64) " + List(20) { "123" }.joinToString(" ")
    @Test fun stopsOnlyExactVerifiedShizukuProcess() = runTest {
        var running = true
        val commands = mutableListOf<List<String>>()
        OriginalShizukuService(command = { args ->
            commands += args.toList()
            when (args.first()) {
                "ps" -> "PID UID NAME\n42 2000 porter_server\n43 2000 shizuku_server_helper\n" + if (running) "44 2000 shizuku_server\n" else ""
                "cat" -> if (args.last().endsWith("/stat")) stat else "shizuku_server\u0000\u0000"
                "kill" -> { running = false; "" }
                else -> error("Unexpected command")
            }
        }, pause = {}).stop()
        assertEquals(listOf(listOf("kill", "-TERM", "44")), commands.filter { it.first() == "kill" })
    }

    @Test fun changedIdentityIsNeverKilled() = runTest {
        val commands = mutableListOf<List<String>>()
        val result = runCatching {
            OriginalShizukuService(command = {
                commands += it.toList()
                if (it.first() == "ps") "PID UID NAME\n44 2000 shizuku_server\n" else if (it.last().endsWith("/stat")) stat else "porter_server\u0000"
            }, pause = {}).stop()
        }
        assertTrue(result.isFailure)
        assertFalse(commands.any { it.first() == "kill" })
    }

    @Test fun permissionDeniedStopsReplacementInsteadOfEscalating() = runTest {
        val commands = mutableListOf<List<String>>()
        val result = runCatching {
            OriginalShizukuService(command = {
                commands += it.toList()
                when (it.first()) {
                    "ps" -> "PID UID NAME\n44 0 shizuku_server\n"
                    "cat" -> if (it.last().endsWith("/stat")) stat else "shizuku_server\u0000"
                    else -> error("Operation not permitted")
                }
            }, pause = {}).stop()
        }
        assertTrue(result.isFailure)
        assertEquals(1, commands.count { it.first() == "kill" })
        assertFalse(commands.any { "-KILL" in it || "porter_server" in it })
    }

    @Test fun reusedPidIsNotSignalled() = runTest {
        var reads = 0
        var killed = false
        val result = runCatching {
            OriginalShizukuService(command = {
                when (it.first()) {
                    "ps" -> "PID UID NAME\n44 2000 shizuku_server\n"
                    "cat" -> if (it.last().endsWith("/stat")) {
                        reads++
                        if (reads == 1) stat else stat.replace("123", "456")
                    } else "shizuku_server\u0000"
                    else -> { killed = true; "" }
                }
            }, pause = {}).stop()
        }
        assertTrue(result.isFailure)
        assertFalse(killed)
    }

    @Test fun unrecognizedProcessTableCannotBeTreatedAsStopped() = runTest {
        assertTrue(runCatching { OriginalShizukuService(command = { "ps failed" }, pause = {}).stop() }.isFailure)
    }
    @Test fun android7NumericTableUsesToolboxFormat() = runTest {
        var running = true
        val commands = mutableListOf<List<String>>()
        OriginalShizukuService(command = {
            commands += it.toList()
            when (it.first()) {
                "ps" -> "USER PID PPID VSIZE RSS WCHAN PC NAME\n" +
                    if (running) "2000 44 1 100 10 ep_poll 0000 S shizuku_server\n" else ""
                "cat" -> if (it.last().endsWith("/stat")) stat else "shizuku_server\u0000"
                else -> { running = false; "" }
            }
        }, pause = {}, legacyPs = true).stop()
        assertTrue(commands.filter { it.first() == "ps" }.all { it == listOf("ps", "-n") })
        assertEquals(1, commands.count { it.first() == "kill" })
    }

    @Test fun ordinaryAppWithSameProcessNameDoesNotBlockReplacement() = runTest {
        OriginalShizukuService(command = {
            assertEquals("ps", it.first())
            "PID UID NAME\n44 10123 shizuku_server\n1 0 shizuku_server\n"
        }, pause = {}).stop()
    }

    @Test fun alreadyStoppedServiceNeedsNoSignal() = runTest {
        OriginalShizukuService(command = {
            assertEquals("ps", it.first())
            "PID UID NAME\n"
        }, pause = {}).stop()
    }

    @Test fun processExitingBeforeIdentityReadCountsAsStopped() = runTest {
        var running = true
        OriginalShizukuService(command = {
            when (it.first()) {
                "ps" -> "PID UID NAME\n" + if (running) "44 2000 shizuku_server\n" else ""
                "cat" -> { running = false; error("No such file or directory") }
                else -> error("Must not send a signal")
            }
        }, pause = {}).stop()
    }

    @Test fun zombieAfterSignalCountsAsStopped() = runTest {
        var killed = false
        OriginalShizukuService(command = {
            when (it.first()) {
                "ps" -> "PID UID NAME\n44 2000 shizuku_server\n"
                "cat" -> if (killed) "" else if (it.last().endsWith("/stat")) stat else "shizuku_server\u0000"
                else -> { killed = true; "" }
            }
        }, pause = {}).stop()
        assertTrue(killed)
    }

    @Test fun processExitingJustBeforeSignalDoesNotFailReplacement() = runTest {
        var running = true
        OriginalShizukuService(command = {
            when (it.first()) {
                "ps" -> "PID UID NAME\n" + if (running) "44 2000 shizuku_server\n" else ""
                "cat" -> {
                    check(running) { "No such file or directory" }
                    if (it.last().endsWith("/stat")) stat else "shizuku_server\u0000"
                }
                else -> { running = false; error("No such process") }
            }
        }, pause = {}).stop()
    }

}
