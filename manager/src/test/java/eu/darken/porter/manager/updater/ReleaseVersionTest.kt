package eu.darken.porter.manager.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {

    private fun version(text: String) = checkNotNull(ReleaseVersion.parse(text)) { text }

    @Test fun aHigherPatchOutranksAnyBuild() {
        assertTrue(version("0.7.0-rc0") > version("0.6.9-rc5"))
    }

    /** The versionCode leaves the suffix type out, so the build number decides first. */
    @Test fun theBuildNumberDecidesBeforeTheSuffixType() {
        assertTrue(version("0.7.0-beta1") > version("0.7.0-rc0"))
    }

    @Test fun buildNumbersCompareNumerically() {
        assertTrue(version("0.7.0-rc10") > version("0.7.0-rc9"))
    }

    @Test fun theTagPrefixIsIgnored() {
        assertEquals(version("0.7.0-rc0"), version("v0.7.0-rc0"))
        assertEquals(0, version("0.7.0-rc0").compareTo(version("v0.7.0-rc0")))
    }

    @Test fun rcOutranksBetaOnATie() {
        assertTrue(version("0.7.0-rc0") > version("0.7.0-beta0"))
    }

    @Test fun onlyTheToolingGrammarParses() {
        assertNull(ReleaseVersion.parse("0.7.0"))
        assertNull(ReleaseVersion.parse("garbage"))
        assertNull(ReleaseVersion.parse("0.7.0-alpha1"))
        assertNull(ReleaseVersion.parse("0.7.0-rc0-debug"))
    }

    @Test fun anUnparseableVersionIsNeverNewer() {
        assertFalse(ReleaseVersion.isNewer("0.7.0", "0.0.0-beta0"))
        assertFalse(ReleaseVersion.isNewer("garbage", "0.0.0-beta0"))
        assertFalse(ReleaseVersion.isNewer("v9.9.9-rc9", "garbage"))
    }

    @Test fun isNewerIsStrict() {
        assertTrue(ReleaseVersion.isNewer("v0.7.0-rc1", "0.7.0-rc0"))
        assertFalse(ReleaseVersion.isNewer("v0.7.0-rc0", "0.7.0-rc0"))
        assertFalse(ReleaseVersion.isNewer("v0.6.9-rc9", "0.7.0-rc0"))
    }
}
