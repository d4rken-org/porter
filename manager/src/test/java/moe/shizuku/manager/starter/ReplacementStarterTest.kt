package moe.shizuku.manager.starter

import android.os.DeadObjectException
import kotlinx.coroutines.test.runTest
import moe.shizuku.server.IRemoteProcess
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplacementStarterTest {
    private fun process(alive: () -> Boolean, code: Int = 0) = object : IRemoteProcess.Stub() {
        override fun alive() = alive.invoke()
        override fun exitValue() = code
        override fun getInputStream() = error("unused")
        override fun getOutputStream() = error("unused")
        override fun getErrorStream() = error("unused")
        override fun waitFor() = error("must not block on remote waitFor")
        override fun waitForTimeout(timeout: Long, unit: String?) = error("unused")
        override fun destroy() = error("must not terminate an accepted handoff")
    }

    @Test fun preflightRejectionReportsNativeExitCodeImmediately() = runTest {
        val failure = runCatching { awaitReplacementStarter(process({ false }, 9)) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("exit 9"))
        assertEquals(0, testScheduler.currentTime)
    }
    @Test fun waitsForSuccessfulParentExit() = runTest {
        var polls = 0
        awaitReplacementStarter(process({ ++polls < 4 }))
        assertEquals(4, polls)
    }
    @Test fun oldBinderDeathIsPassedToTheHandoffEngine() = runTest {
        assertTrue(runCatching { awaitReplacementStarter(process({ throw DeadObjectException() })) }.exceptionOrNull() is DeadObjectException)
    }
    @Test fun launcherTimeoutReportsUnknownOutcome() = runTest {
        val failure = runCatching { awaitReplacementStarter(process({ true })) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("outcome is unknown"))
        assertEquals(10_000, testScheduler.currentTime)
    }
}
