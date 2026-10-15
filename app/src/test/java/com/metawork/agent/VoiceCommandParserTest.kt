package com.metawork.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun youtubeEnglishAndHindiQueriesAreCleaned() {
        assertEquals("Arijit Singh", VoiceCommandParser.extractYouTubeQuery("Open YouTube and play Arijit Singh song"))
        assertEquals("Arijit Singh", VoiceCommandParser.extractYouTubeQuery("YouTube par Arijit Singh ke gaane chalao"))
        assertEquals("Arijit Singh", VoiceCommandParser.extractYouTubeQuery("Play Arijit Singh on YouTube"))
    }

    @Test
    fun callRecipientIsExtractedFromEnglishAndHinglish() {
        assertEquals("Rahul", VoiceCommandParser.extractCallTarget("Call Rahul"))
        assertEquals("Rahul", VoiceCommandParser.extractCallTarget("Rahul ko phone lagao"))
        assertEquals("Rahul", VoiceCommandParser.extractCallTarget("Rahul ko call karo"))
        assertEquals("Rahul", VoiceCommandParser.extractCallTarget("Rahul ko call kar do"))
        assertEquals("Rahul", VoiceCommandParser.extractCallTarget("Please dial to Rahul"))
    }

    @Test
    fun whatsappParsesFullNamesAndNeverInventsMissingMessageText() {
        assertEquals(
            "Rahul Sharma" to "I will be late",
            VoiceCommandParser.parseWhatsAppIntent("Send WhatsApp message to Rahul Sharma saying I will be late")
        )
        assertEquals(
            "Rahul Sharma" to "I will be late",
            VoiceCommandParser.parseWhatsAppIntent("WhatsApp par Rahul Sharma ko message bhejo I will be late")
        )
        assertEquals("Rahul" to "", VoiceCommandParser.parseWhatsAppIntent("Rahul ko message bhejo"))
    }

    @Test
    fun accountSignupRoutesToTheNamedApp() {
        assertTrue(VoiceCommandParser.isAccountSignupRequest("Instagram par mera account bana do"))
        assertEquals("instagram", VoiceCommandParser.accountSignupTarget("Instagram par mera account bana do"))
        assertFalse(VoiceCommandParser.isAccountSignupRequest("Google par Instagram account search karo"))
        assertEquals(null, VoiceCommandParser.accountSignupTarget("create an account"))
    }
}
