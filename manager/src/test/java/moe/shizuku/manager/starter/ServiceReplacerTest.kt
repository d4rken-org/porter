package moe.shizuku.manager.starter

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.shizuku.manager.model.PorterServiceVersion
import moe.shizuku.manager.support.ServerDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceReplacerTest {
    private val installed = PorterServiceVersion("0.1.1-beta1", 101010, "new:debug")
    private val old = Binder()
    private val replacement = Binder()
    private var current: IBinder? = old
    private var oldVersion: PorterServiceVersion? = installed.copy(buildId = "old:debug")
    private var nextVersion = installed
    private var nextPid = 200
    private var nextUid = 2000
    private var launches = 0
    private var attempts = 0
    private fun engine(launch: () -> Unit = {}) = ServiceReplacer(installed, { current },
        readInfo = { if (it === old) ServerDiagnostics.Info(100, oldVersion) else ServerDiagnostics.Info(nextPid, nextVersion) },
        readUid = { if (it === old) 2000 else nextUid },
        launch = { binder, pid ->
            assertSame(old, binder); assertEquals(100, pid)
            launches++; launch()
        })

    @Test fun matchingBuildDoesNotLaunchOrRecordAnAttempt() = runTest {
        oldVersion = installed
        engine().replace { attempts++ }
        assertEquals(0, launches)
        assertEquals(0, attempts)
    }

    @Test fun pidOnlyLegacyServiceCanLaunchTheCurrentBuild() = runTest {
        oldVersion = null
        engine { current = replacement }.replace { attempts++ }
        assertEquals(1, launches)
        assertEquals(1, attempts)
    }

    @Test fun oldLiveBinderCannotBeMistakenForSuccessfulReplacement() = runTest {
        val result = async { engine().replace {} }
        runCurrent()
        advanceTimeBy(1000)
        assertFalse(result.isCompleted)
        current = replacement
        advanceTimeBy(100)
        result.await()
    }

    @Test fun deathDuringLaunchReplyStillWaitsForNewService() = runTest {
        engine { current = replacement; throw DeadObjectException() }.replace {}
        assertEquals(1, launches)
    }

    @Test fun uncertainLauncherOutcomeStillWaitsForTheReplacement() = runTest {
        val result = async { engine { throw ReplacementLaunchUncertain() }.replace {} }
        runCurrent()
        assertFalse(result.isCompleted)
        current = replacement
        advanceTimeBy(100)
        result.await()
        assertEquals(1, launches)
    }

    @Test fun unknownOrWrongReplacementBuildFails() = runTest {
        nextVersion = installed.copy(buildId = null)
        val failure = runCatching { engine { current = replacement }.replace {} }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test fun replacementCannotChangePrivileges() = runTest {
        nextUid = 0
        assertTrue(runCatching { engine { current = replacement }.replace {} }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun aDifferentBinderWithTheOldPidIsNotSuccess() = runTest {
        nextPid = 100
        assertTrue(runCatching { engine { current = replacement }.replace {} }.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test fun blockedAttemptLeavesTheOldServiceUntouched() = runTest {
        assertTrue(runCatching { engine().replace { error("already attempted") } }.isFailure)
        assertEquals(0, launches)
        assertSame(old, current)
    }

    @Test fun failureToReconnectTimesOut() = runTest {
        assertTrue(runCatching { engine { current = null }.replace {} }.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(1, launches)
    }

    @Test fun cancellationAfterLaunchStillFinishesHandoff() = runTest {
        var observed = false
        val result = async {
            ServiceReplacer(installed, { current },
                readInfo = {
                    if (it === old) ServerDiagnostics.Info(100, oldVersion)
                    else { observed = true; ServerDiagnostics.Info(200, installed) }
                }, readUid = { 2000 }, launch = { _, _ -> current = null }).replace {}
        }
        runCurrent()
        result.cancel()
        assertFalse(result.isCompleted)
        current = replacement
        advanceTimeBy(100)
        runCurrent()
        assertTrue(observed)
        assertTrue(result.isCompleted)
    }

    @Test fun newBinderMustBeReadyBeforeSuccess() = runTest {
        var ready = false
        val result = async {
            ServiceReplacer(installed, { current },
                readInfo = { if (it === old) ServerDiagnostics.Info(100, oldVersion) else ServerDiagnostics.Info(200, installed) },
                readUid = { 2000 }, isReady = { ready }, launch = { _, _ -> current = replacement }).replace {}
        }
        runCurrent()
        assertFalse(result.isCompleted)
        ready = true
        advanceTimeBy(100)
        result.await()
    }
}
