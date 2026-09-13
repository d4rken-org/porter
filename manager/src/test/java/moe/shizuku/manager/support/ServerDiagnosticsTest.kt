package moe.shizuku.manager.support

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import moe.shizuku.manager.model.PorterServiceVersion
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.ServerConstants

/** Parses the manager-only diagnostics reply from current, older and misbehaving services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerDiagnosticsTest {
    private val descriptor = "moe.shizuku.server.IShizukuService"

    /** A service whose diagnostics transaction writes [reply]; every other code is unsupported. */
    private fun service(write: Parcel.() -> Unit) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ServerConstants.BINDER_TRANSACTION_getDiagnostics) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(descriptor)
            reply!!.write()
            return true
        }
    }
    private fun Parcel.versionBundle(name: String?, code: Int?) = writeBundle(Bundle().apply {
        name?.let { putString(ServerConstants.DIAGNOSTICS_VERSION_NAME, it) }
        code?.let { putInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, it) }
    })

    @Test fun currentServiceReportsPidAndVersion() {
        val info = ServerDiagnostics.readInfo(service { writeNoException(); writeInt(4321); versionBundle("1.2.0-beta3", 1200030) })
        assertEquals(ServerDiagnostics.Info(4321, PorterServiceVersion("1.2.0-beta3", 1200030)), info)
    }

    @Test fun buildIdentifierSurvivesTheDiagnosticsHandshake() {
        val info = ServerDiagnostics.readInfo(service {
            writeNoException(); writeInt(4321)
            writeBundle(Bundle().apply {
                putString(ServerConstants.DIAGNOSTICS_VERSION_NAME, "1.2.0-beta3")
                putInt(ServerConstants.DIAGNOSTICS_VERSION_CODE, 1200030)
                putString(eu.darken.porter.common.PorterBuildIdentity.DIAGNOSTICS_KEY, "dirty-build:debug")
            })
        })
        assertEquals("dirty-build:debug", info!!.version!!.buildId)
    }

    @Test fun serviceWithoutVersionReportsOnlyPid() {
        val info = ServerDiagnostics.readInfo(service { writeNoException(); writeInt(77) })
        assertEquals(ServerDiagnostics.Info(77, null), info)
    }

    @Test fun bundleWithoutVersionKeysIsTreatedAsUnknown() {
        assertNull(ServerDiagnostics.readInfo(service { writeNoException(); writeInt(77); versionBundle(null, null) })!!.version)
    }

    @Test fun blankVersionNameIsTreatedAsUnknown() {
        assertNull(ServerDiagnostics.readInfo(service { writeNoException(); writeInt(77); versionBundle("  ", 1200030) })!!.version)
    }

    @Test fun negativeVersionCodeIsTreatedAsUnknown() {
        assertNull(ServerDiagnostics.readInfo(service { writeNoException(); writeInt(77); versionBundle("1.2.0-beta3", -5) })!!.version)
    }

    /** A service whose debug-logging transaction grants [granted]; every other code is unsupported. */
    private fun leasing(granted: Long) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ServerConstants.BINDER_TRANSACTION_setDebugLogging) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(descriptor)
            assertNotNull(data.readStrongBinder())
            requested = data.readLong()
            reply!!.writeNoException()
            reply.writeLong(granted)
            return true
        }
    }
    private var requested = -1L

    @Test fun grantedDebugLoggingReportsWhatTheServiceAllowed() {
        assertEquals(600_000L, ServerDiagnostics.requestDebugLogging(leasing(600_000L), Binder(), 1_800_000L))
        assertEquals(1_800_000L, requested)
    }

    @Test fun serviceWithoutDebugLoggingTransactionIsUnsupportedRatherThanRefused() {
        assertNull(ServerDiagnostics.requestDebugLogging(Binder(), Binder(), 1_800_000L))
    }

    @Test fun aServiceGrantingNothingIsDistinguishableFromAnUnsupportedOne() {
        assertEquals(0L, ServerDiagnostics.requestDebugLogging(leasing(0L), Binder(), 1_800_000L))
    }

    @Test(expected = SecurityException::class)
    fun refusedDebugLoggingSurfacesTheServiceException() {
        val refusing = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(descriptor)
                reply!!.writeException(SecurityException("setDebugLogging requires the manager"))
                return true
            }
        }
        ServerDiagnostics.requestDebugLogging(refusing, Binder(), 1_800_000L)
    }

    @Test fun releasingPassesZeroSoTheServiceClosesItsGate() {
        ServerDiagnostics.requestDebugLogging(leasing(0L), Binder(), 0L)
        assertEquals(0L, requested)
    }

    @Test fun serviceWithoutDiagnosticsTransactionYieldsNothing() {
        assertNull(ServerDiagnostics.readInfo(Binder()))
    }

    @Test(expected = SecurityException::class)
    fun refusedTransactionSurfacesTheServiceException() {
        ServerDiagnostics.readInfo(service { writeException(SecurityException("getDiagnostics requires the manager")) })
    }

    @Test(expected = IllegalStateException::class)
    fun nonPositivePidIsRejected() {
        ServerDiagnostics.readInfo(service { writeNoException(); writeInt(0) })
    }

    @Test fun requestCarriesTheServiceInterfaceToken() {
        var enforced = false
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(descriptor)
                enforced = true
                reply!!.writeNoException(); reply.writeInt(1)
                return true
            }
        }
        assertNotNull(ServerDiagnostics.readInfo(binder))
        assertTrue(enforced)
    }
}
