package eu.darken.porter.manager.compatibility

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
                "ps" -> "PID PPID UID NAME\n42 1 2000 porter_server\n43 1 2000 shizuku_server_helper\n" + if (running) "44 1 2000 shizuku_server\n" else ""
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
                if (it.first() == "ps") "PID PPID UID NAME\n44 1 2000 shizuku_server\n" else if (it.last().endsWith("/stat")) stat else "porter_server\u0000"
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
                    "ps" -> "PID PPID UID NAME\n44 1 0 shizuku_server\n"
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
                    "ps" -> "PID PPID UID NAME\n44 1 2000 shizuku_server\n"
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
            "PID PPID UID NAME\n44 1 10123 shizuku_server\n1 0 0 shizuku_server\n"
        }, pause = {}).stop()
    }

    @Test fun alreadyStoppedServiceNeedsNoSignal() = runTest {
        OriginalShizukuService(command = {
            assertEquals("ps", it.first())
            "PID PPID UID NAME\n"
        }, pause = {}).stop()
    }

    @Test fun processExitingBeforeIdentityReadCountsAsStopped() = runTest {
        var running = true
        OriginalShizukuService(command = {
            when (it.first()) {
                "ps" -> "PID PPID UID NAME\n" + if (running) "44 1 2000 shizuku_server\n" else ""
                "cat" -> { running = false; error("No such file or directory") }
                else -> error("Must not send a signal")
            }
        }, pause = {}).stop()
    }

    @Test fun zombieAfterSignalCountsAsStopped() = runTest {
        var killed = false
        OriginalShizukuService(command = {
            when (it.first()) {
                "ps" -> "PID PPID UID NAME\n44 1 2000 shizuku_server\n"
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
                "ps" -> "PID PPID UID NAME\n" + if (running) "44 1 2000 shizuku_server\n" else ""
                "cat" -> {
                    check(running) { "No such file or directory" }
                    if (it.last().endsWith("/stat")) stat else "shizuku_server\u0000"
                }
                else -> { running = false; error("No such process") }
            }
        }, pause = {}).stop()
    }

    private val starter = "/data/app/~~a/kerneldroid.nightzuku-b/lib/x86_64/libshizuku.so"

    @Test fun supervisingStarterIsSignalledBeforeServer() = runTest {
        val alive = mutableSetOf("40", "44")
        val commands = mutableListOf<List<String>>()
        OriginalShizukuService(command = {
            commands += it.toList()
            when (it.first()) {
                "ps" -> "PID PPID UID NAME\n" + (if ("40" in alive) "40 1 2000 libshizuku.so\n" else "") +
                    if ("44" in alive) "44 40 2000 shizuku_server\n" else ""
                "cat" -> if (it.last().split("/")[2] !in alive) error("No such file or directory")
                    else if (it.last().endsWith("/stat")) stat
                    else if (it.last() == "/proc/40/cmdline") "$starter\u0000" else "shizuku_server\u0000"
                else -> { alive -= it.last(); "" }
            }
        }, pause = {}).stop()
        assertEquals(listOf(listOf("kill", "-TERM", "40"), listOf("kill", "-TERM", "44")), commands.filter { it.first() == "kill" })
    }

    @Test fun otherParentsAreNeverSignalled() = runTest {
        val alive = mutableSetOf("44", "45", "46")
        val commands = mutableListOf<List<String>>()
        OriginalShizukuService(command = {
            commands += it.toList()
            when (it.first()) {
                "ps" -> "PID PPID UID NAME\n40 1 2000 sh\n41 1 10123 libshizuku.so\n" +
                    listOf("44 40", "45 41", "46 1").filter { row -> row.substringBefore(' ') in alive }
                        .joinToString("") { row -> "$row 2000 shizuku_server\n" }
                "cat" -> when {
                    it.last().endsWith("/stat") -> stat
                    it.last() == "/proc/40/cmdline" -> "/system/bin/sh\u0000$starter\u0000"
                    it.last() == "/proc/41/cmdline" -> "$starter\u0000"
                    else -> "shizuku_server\u0000"
                }
                else -> { alive -= it.last(); "" }
            }
        }, pause = {}).stop()
        assertEquals(listOf("44", "45", "46"), commands.filter { it.first() == "kill" }.map { it.last() })
    }

    @Test fun changedStarterIdentityIsNeverSignalled() = runTest {
        var starterReads = 0
        val commands = mutableListOf<List<String>>()
        val result = runCatching {
            OriginalShizukuService(command = {
                commands += it.toList()
                when (it.first()) {
                    "ps" -> "PID PPID UID NAME\n40 1 2000 libshizuku.so\n44 40 2000 shizuku_server\n"
                    "cat" -> when (it.last()) {
                        "/proc/40/stat" -> if (++starterReads == 1) stat else stat.replace("123", "456")
                        "/proc/40/cmdline" -> "$starter\u0000"
                        "/proc/44/stat" -> stat
                        else -> "shizuku_server\u0000"
                    }
                    else -> ""
                }
            }, pause = {}).stop()
        }
        assertTrue(result.isFailure)
        assertFalse(commands.any { it.first() == "kill" })
    }

    @Test fun android7SupervisingStarterIsSignalledBeforeServer() = runTest {
        val alive = mutableSetOf("40", "44")
        val commands = mutableListOf<List<String>>()
        OriginalShizukuService(command = {
            commands += it.toList()
            when (it.first()) {
                "ps" -> "USER PID PPID VSIZE RSS WCHAN PC NAME\n" +
                    (if ("40" in alive) "2000 40 1 100 10 do_wait 0000 S $starter\n" else "") +
                    if ("44" in alive) "2000 44 40 100 10 ep_poll 0000 S shizuku_server\n" else ""
                "cat" -> if (it.last().split("/")[2] !in alive) error("No such file or directory")
                    else if (it.last().endsWith("/stat")) stat
                    else if (it.last() == "/proc/40/cmdline") "$starter\u0000" else "shizuku_server\u0000"
                else -> { alive -= it.last(); "" }
            }
        }, pause = {}, legacyPs = true).stop()
        assertEquals(listOf(listOf("kill", "-TERM", "40"), listOf("kill", "-TERM", "44")), commands.filter { it.first() == "kill" })
    }

    @Test fun stopWaitsForSignalledStarter() = runTest {
        var killed = false
        var pauses = 0
        val starterAlive = { !killed || pauses < 3 }
        OriginalShizukuService(command = {
            when (it.first()) {
                "ps" -> "PID PPID UID NAME\n" + (if (starterAlive()) "40 1 2000 libshizuku.so\n" else "") +
                    if (!killed) "44 40 2000 shizuku_server\n" else ""
                "cat" -> {
                    check(!it.last().startsWith("/proc/40/") || starterAlive()) { "No such file or directory" }
                    check(!it.last().startsWith("/proc/44/") || !killed) { "No such file or directory" }
                    if (it.last().endsWith("/stat")) stat
                    else if (it.last() == "/proc/40/cmdline") "$starter\u0000" else "shizuku_server\u0000"
                }
                else -> { killed = true; "" }
            }
        }, pause = { pauses++ }).stop()
        assertEquals(3, pauses)
    }

    @Test fun starterThatNeverExitsFailsReplacement() = runTest {
        var killed = false
        val result = runCatching {
            OriginalShizukuService(command = {
                when (it.first()) {
                    "ps" -> "PID PPID UID NAME\n40 1 2000 libshizuku.so\n" + if (!killed) "44 40 2000 shizuku_server\n" else ""
                    "cat" -> {
                        check(!it.last().startsWith("/proc/44/") || !killed) { "No such file or directory" }
                        if (it.last().endsWith("/stat")) stat
                        else if (it.last() == "/proc/40/cmdline") "$starter\u0000" else "shizuku_server\u0000"
                    }
                    else -> { killed = true; "" }
                }
            }, pause = {}).stop()
        }
        assertTrue(result.isFailure)
    }
}
