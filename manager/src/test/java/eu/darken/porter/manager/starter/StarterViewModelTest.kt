package eu.darken.porter.manager.starter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.darken.porter.manager.TestApplication
import java.net.ConnectException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The root start keeps its own path; an ADB start goes through the shared step and ends with its output. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class StarterViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private var rootStarts = 0
    private val adbStarts = mutableListOf<Pair<Int, Boolean>>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun advance() = dispatcher.scheduler.advanceUntilIdle()

    /** The root seam parks after recording, so the root path never reaches the real service wait. */
    private fun model(
        adbStep: suspend (Int, Boolean, ((String) -> Unit)?) -> Unit = { port, tls, _ -> adbStarts += port to tls },
    ) = StarterViewModel(application).apply {
        rootStart = { rootStarts++; awaitCancellation() }
        adbStart = { _, port, tls, log -> adbStep(port, tls, log) }
    }

    @Test fun aRootStartNeverUsesTheAdbStep() {
        model().start(root = true, port = 0, requireTls = false)
        advance()
        assertEquals(1, rootStarts)
        assertEquals(emptyList<Pair<Int, Boolean>>(), adbStarts)
    }

    @Test fun aTcpStartPassesItsPortWithoutTls() {
        model().start(root = false, port = 5555, requireTls = false)
        advance()
        assertEquals(listOf(5555 to false), adbStarts)
        assertEquals(0, rootStarts)
    }

    @Test fun aTlsStartPassesItsPortWithTls() {
        model().start(root = false, port = 37123, requireTls = true)
        advance()
        assertEquals(listOf(37123 to true), adbStarts)
        assertEquals(0, rootStarts)
    }

    @Test fun aFailedAdbStepIsReported() {
        val failure = ConnectException("refused")
        val model = model(adbStep = { _, _, _ -> throw failure })
        model.start(root = false, port = 37123, requireTls = true)
        advance()
        assertSame(failure, model.output.value.error)
        assertTrue(model.output.value.text.contains("refused"))
    }

    /** The activity closes itself when the output ends with the started message. */
    @Test fun aSuccessfulAdbStartEndsWithTheStartedMessage() {
        val model = model(adbStep = { _, _, log ->
            log?.invoke("Connecting on port 37123...")
            log?.invoke(Starter.serviceStartedMessage)
        })
        model.start(root = false, port = 37123, requireTls = true)
        advance()
        assertNull(model.output.value.error)
        assertTrue(model.output.value.text.trim().endsWith(Starter.serviceStartedMessage))
    }
}
