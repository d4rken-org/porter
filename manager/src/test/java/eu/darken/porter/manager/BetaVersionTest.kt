package eu.darken.porter.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetaVersionTest {
    @Test fun betaNamesAreBeta() {
        assertTrue(isBetaVersion("0.7.0-beta1"))
    }

    @Test fun releaseCandidatesAreNotBeta() {
        assertFalse(isBetaVersion("0.7.0-rc0"))
    }

    @Test fun releasesAreNotBeta() {
        assertFalse(isBetaVersion("0.7.0"))
    }
}
