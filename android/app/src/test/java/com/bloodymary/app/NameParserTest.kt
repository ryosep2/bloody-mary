package com.bloodymary.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameParserTest {

    @Test
    fun extractsNameFromCommonAnswers() {
        assertEquals("Sarah", NameParser.extractName("sarah"))
        assertEquals("Mike", NameParser.extractName("um mike"))
        assertEquals("Jess", NameParser.extractName("my name is jess"))
        assertEquals("Tom", NameParser.extractName("it's tom"))
        assertEquals("Ana", NameParser.extractName("i am ana and i'm scared"))
        assertEquals("Leo", NameParser.extractName("they call me leo"))
    }

    @Test
    fun ignoresAnswersWithoutAName() {
        assertNull(NameParser.extractName(""))
        assertNull(NameParser.extractName("um"))
        assertNull(NameParser.extractName("no"))
        assertNull(NameParser.extractName("what"))
        assertNull(NameParser.extractName("bloody mary"))
        assertNull(NameParser.extractName("child"))                  // the end of her own question
        assertNull(NameParser.extractName("speak your name child"))
    }

    @Test
    fun matchesKnownGuestsLoosely() {
        val guests = mapOf("Liam" to null, "Ja'Marcus" to "Ja-Marcus", "Aoife" to "Eefa", "Madison" to null, "Jon" to null)
        assertEquals("Liam", NameParser.matchGuest("Liam", guests))
        assertEquals("Aoife", NameParser.matchGuest("Eefa", guests))         // by pronunciation spelling
        assertEquals("Ja'Marcus", NameParser.matchGuest("Jamarcus", guests))
        assertEquals("Madison", NameParser.matchGuest("Madisen", guests))    // one letter off
        assertNull(NameParser.matchGuest("Bob", guests))
        assertNull(NameParser.matchGuest("Jan", guests))                     // short names must match exactly
    }
}
