package eu.darken.porter.manager.adb

import eu.darken.porter.manager.adb.AdbProtocol.A_WRTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** These strings go into debug recordings, and a payload can be an auth token or command output. */
class AdbMessageTest {

    private val message = AdbMessage(A_WRTE, 1, 2, "ls")
    private val header = "command=A_WRTE, arg0=1, arg1=2, data_length=3, data_crc32=223, magic=${message.magic}"

    @Test fun theShortFormLeavesThePayloadOut() {
        assertEquals(header, message.toStringShort())
    }

    @Test fun theLongFormLeavesThePayloadOut() {
        assertEquals("AdbMessage($header)", message.toString())
    }

    @Test fun anInvalidMessageLeavesThePayloadOutOfItsError() {
        val invalid = AdbMessage(A_WRTE, 1, 2, 3, 0, 0, "ls\u0000".toByteArray())
        val error = assertThrows(IllegalArgumentException::class.java) { invalid.validateOrThrow() }
        assertEquals("bad message command=A_WRTE, arg0=1, arg1=2, data_length=3, data_crc32=0, magic=0", error.message)
    }
}
