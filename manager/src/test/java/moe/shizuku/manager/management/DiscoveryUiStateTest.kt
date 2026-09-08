package moe.shizuku.manager.management

import eu.darken.porter.common.DiscoveredApplication as Entry
import org.junit.Assert.*
import org.junit.Test

class DiscoveryUiStateTest {
    private fun app(status: Int, authorization: Int = Entry.DEFAULT) = AppsViewModel.App(
        "example", 10123, "Example", null, authorization, status, Entry.API_SHIZUKU, false, null)

    @Test fun missingCompanionAllowsSavingAndRevokingAccess() {
        assertTrue(app(Entry.NEEDS_COMPANION).canToggle)
        val granted = app(Entry.NEEDS_COMPANION, Entry.ALLOWED)
        assertTrue(granted.canToggle)
        assertTrue(granted.canAuthorize)
    }
    @Test fun compatibilityWarningRequiresAnAllowedAppWithMissingCompanion() {
        assertEquals(0, AppsViewModel.State(listOf(app(Entry.NEEDS_COMPANION))).pendingCompanionCount)
        assertEquals(0, AppsViewModel.State(listOf(app(Entry.NEEDS_COMPANION, Entry.DENIED))).pendingCompanionCount)
        assertEquals(1, AppsViewModel.State(listOf(app(Entry.NEEDS_COMPANION, Entry.PENDING_COMPANION),
            app(Entry.COMPANION, Entry.ALLOWED))).pendingCompanionCount)
    }
    @Test fun permissionDeclarationDoesNotMakeUnsupportedClientActionable() {
        assertFalse(app(Entry.UNSUPPORTED).canAuthorize)
        assertFalse(app(Entry.UNSUPPORTED).canToggle)
    }
    @Test fun countsDistinguishGrantedCompatibleAndCompanionRequiredClients() {
        val state = AppsViewModel.State(listOf(app(Entry.DIRECT, Entry.ALLOWED),
            app(Entry.COMPANION), app(Entry.NEEDS_COMPANION), app(Entry.MANAGED_ONLY, Entry.ALLOWED)))
        assertEquals(2, state.grantedCount)
        assertEquals(2, state.compatibleCount)
        assertEquals(1, state.companionRequiredCount)
        assertFalse(state.allGranted)
    }
    @Test fun pendingIntentChecksTheSwitchButDoesNotCountAsEffectiveAuthorization() {
        val pending = app(Entry.NEEDS_COMPANION, Entry.PENDING_COMPANION)
        assertTrue(pending.granted)
        val state = AppsViewModel.State(listOf(pending))
        assertTrue(state.allGranted)
        assertEquals(0, state.grantedCount)
        assertEquals(1, state.pendingCompanionCount)
    }
}
