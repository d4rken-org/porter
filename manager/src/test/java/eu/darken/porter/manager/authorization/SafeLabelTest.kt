package eu.darken.porter.manager.authorization

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeLabelTest {
    @Test fun anOrdinaryLabelIsUnchanged() {
        assertEquals("Termux", safeLabel("Termux"))
        assertEquals("Übersicht 日本語 😀", safeLabel("Übersicht 日本語 😀"))
    }

    @Test fun onlyTheFirstLineIsKept() {
        assertEquals("Good app", safeLabel("Good app\nwants nothing"))
        assertEquals("Good app", safeLabel("Good app\r\nwants nothing"))
        assertEquals("Good app", safeLabel("Good app\u2028wants nothing"))
        assertEquals("Good app", safeLabel("Good app\u0085wants nothing"))
    }

    @Test fun directionControlsAreRemoved() {
        assertEquals("ppa liveE", safeLabel("\u202Eppa liveE"))
        assertEquals("abc", safeLabel("\u202Aa\u202Bb\u202Cc\u202D"))
        assertEquals("abc", safeLabel("\u2066a\u2067b\u2068c\u2069"))
        assertEquals("abc", safeLabel("\u200Ea\u200Fb\u061Cc"))
    }

    @Test fun otherControlCharactersAreRemoved() {
        assertEquals("ab", safeLabel("a\u0000\u0007b\u009B"))
    }

    @Test fun surroundingWhitespaceIsTrimmed() {
        assertEquals("Good app", safeLabel("  \u202E Good app \t"))
        assertEquals("", safeLabel("\n Porter"))
    }

    @Test fun theLengthIsCappedWithoutSplittingACharacter() {
        assertEquals("abc", safeLabel("abcdef", maxLength = 3))
        assertEquals("ab", safeLabel("ab😀", maxLength = 3))
        assertEquals(MAX_LABEL_LENGTH, safeLabel("x".repeat(MAX_LABEL_LENGTH + 10)).length)
    }
}
