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
    fun onlyBuiltModesCanBeChosen() {
        assertEquals(listOf(PrivacyMode.Fast), PrivacyMode.entries.filter { it.available })
    }

    @Test
    fun unknownStatusIsNotInvented() {
        assertEquals(DeliveryStatus.Delivered, DeliveryStatus.of("delivered"))
        assertEquals(null, DeliveryStatus.of("received"))
    }
}
