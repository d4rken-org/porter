package moe.shizuku.manager.home

import moe.shizuku.manager.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeTitleIconTest {
    @Test fun stoppedServiceShowsTheNeutralMascot() {
        assertEquals(R.drawable.porter_mascot, homeTitleIcon(false))
    }

    @Test fun runningServiceShowsTheHappyMascot() {
        assertEquals(R.drawable.porter_mascot_happy, homeTitleIcon(true))
    }

    @Test fun theTwoMascotsAreDistinctDrawables() {
        assertNotEquals(R.drawable.porter_mascot, R.drawable.porter_mascot_happy)
    }
}
