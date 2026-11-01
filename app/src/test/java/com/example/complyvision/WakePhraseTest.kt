package com.example.complyvision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {

    @Test
    fun acceptsTheWakePhraseInEveryCommonSpelling() {
        listOf(
            "खुल जा सिम सिम", "खुलजा सिमसिम", "खुल जा सिम सिम।", "कुल जा सिम सिम", "खोल जा सिम सिम",
            "Khul Ja Sim Sim", "khulja simsim", "Khul jaa sim sim", "cool ja sim sim", "kool jaa seem seem",
            "full ja sim sim", "khula sim sim", "cool jar sim sim", "kul jai sim sim", "Khul ja Sim-Sim!",
            "please khul ja sim sim", "open sesame", "Open Sesame", "ओपन सेसमी"
        ).forEach { assertTrue("should match: $it", WakePhrase.matches(it)) }
    }

    @Test
    fun rejectsOrdinarySpeech() {
        listOf(
            "", "sim sim", "सिम सिम", "khul ja", "मेरा सिम कार्ड", "kya sim sim hai", "simple simon",
            "dukaan khul gayi", "open the door", "jaa raha hoon", "sim card change karna hai", "hello"
        ).forEach { assertFalse("should not match: $it", WakePhrase.matches(it)) }
    }

    @Test
    fun matchesAnyAlternative() {
        assertTrue(WakePhrase.matchesAny(listOf("kuch aur", "khul ja sim sim")))
        assertFalse(WakePhrase.matchesAny(emptyList()))
    }

    @Test
    fun phoneticFolding() {
        assertEquals("kuljasimsim", WakePhrase.phonetic("खुल जा सिम सिम"))
        assertEquals("kuljasimsim", WakePhrase.phonetic("Cool Jaa Seem Seem"))
        assertEquals(0, WakePhrase.distanceInside("abc", "xxabcxx"))
        assertEquals(1, WakePhrase.distanceInside("abc", "xxabxx"))
    }
}
