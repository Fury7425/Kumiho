package kumiho

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun stricterModeWins() {
        assertEquals(PrivacyMode.Untraceable, PrivacyMode.inUse(PrivacyMode.Fast, PrivacyMode.Untraceable))
        assertEquals(PrivacyMode.Private, PrivacyMode.inUse(PrivacyMode.Private, PrivacyMode.Fast))
        assertTrue(PrivacyMode.Fast.allowsCalls)
        assertFalse(PrivacyMode.Private.allowsCalls)
    }

    @Test
    fun pairingCodeChecks() {
        assertEquals(CodeCheck.Ok, checkPairingCode(DEMO_WORDS))
        assertEquals(CodeCheck.Ok, checkPairingCode("  RIVER maple orbit tiger seven "))
        assertEquals(CodeCheck.Ok, checkPairingCode(DEMO_DIGITS))
        // A typo in the format must not burn the code; a well-formed wrong code must.
        assertEquals(CodeCheck.Malformed, checkPairingCode("river-maple"))
        assertEquals(CodeCheck.Malformed, checkPairingCode("1234"))
        assertEquals(CodeCheck.Wrong, checkPairingCode("one-two · three-four-five"))
        assertEquals(CodeCheck.Wrong, checkPairingCode("00000000000000000000"))
    }
}
