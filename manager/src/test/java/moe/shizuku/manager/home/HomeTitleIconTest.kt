package moe.shizuku.manager.home

import moe.shizuku.manager.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeTitleIconTest {
    @Test fun stoppedServiceShowsTheNeutralMascot() {
        assertEquals(R.drawable.porter_mascot, homeTitleIcon(false, false))
    }

    @Test fun runningServiceShowsTheHappyMascot() {
        assertEquals(R.drawable.porter_mascot_happy, homeTitleIcon(true, false))
    }

    @Test fun restrictedServiceShowsTheUnhappyMascot() {
        assertEquals(R.drawable.porter_mascot_unhappy, homeTitleIcon(true, true))
        assertEquals(R.drawable.porter_mascot, homeTitleIcon(false, true))
    }

    @Test fun theThreeMascotsAreDistinctDrawables() {
        assertNotEquals(R.drawable.porter_mascot, R.drawable.porter_mascot_happy)
        assertNotEquals(R.drawable.porter_mascot, R.drawable.porter_mascot_unhappy)
        assertNotEquals(R.drawable.porter_mascot_happy, R.drawable.porter_mascot_unhappy)
    }
}
