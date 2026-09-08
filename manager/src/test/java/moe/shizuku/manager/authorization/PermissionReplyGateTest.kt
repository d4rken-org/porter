package moe.shizuku.manager.authorization

import org.junit.Assert.*
import org.junit.Test

class PermissionReplyGateTest {
    @Test fun repeatedDecisionDoesNotDispatchAgain() {
        val gate = PermissionReplyGate()
        val replies = mutableListOf<Boolean>()
        assertTrue(gate.reply { replies += true })
        assertFalse(gate.reply { replies += false })
        assertEquals(listOf(true), replies)
    }

    @Test fun recreatedRequestKeepsItsReplyState() {
        val before = PermissionReplyGate()
        before.reply {}
        val restored = PermissionReplyGate(before.replied)
        assertFalse(restored.reply { fail("Recreation must not repeat a permission response") })
    }

    @Test fun failedDispatchIsNotRepeatedWithADifferentDecision() {
        val gate = PermissionReplyGate()
        runCatching { gate.reply { throw IllegalStateException("Binder died") } }
        assertFalse(gate.reply { fail("A failed Binder call must not change the user's decision") })
    }
}
