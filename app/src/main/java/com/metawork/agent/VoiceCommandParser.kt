package com.metawork.agent

import java.util.Locale

/** Deterministic helpers for common Hindi/Hinglish and English voice intents. */
internal object VoiceCommandParser {
    fun extractYouTubeQuery(spoken: String): String {
        var query = spoken.trim()
        query = query.replace(
            Regex("^(?:please[ ]+)?(?:open|launch)[ ]+youtube(?:[ ]+app)?(?:[ ]+(?:and|aur))?[ ]*(?:play|chalao|bajao)?[ ]*", RegexOption.IGNORE_CASE),
            ""
        )
        query = query.replace(
            Regex("^youtube[ ]+(?:par|pe|mein|me)[ ]*", RegexOption.IGNORE_CASE),
            ""
        )
        query = query.replace(Regex("^(?:play|chalao|bajao)[ ]+", RegexOption.IGNORE_CASE), "")
        query = query.replace(Regex("[ ]+(?:on|in)[ ]+youtube$", RegexOption.IGNORE_CASE), "")
        query = query.replace(
            Regex("[ ]+(?:(?:ka|ke)[ ]+)?(?:gaana|gaane|song|music)?[ ]*(?:chalao|bajao|play[ ]+karo)$", RegexOption.IGNORE_CASE),
            ""
        )
        query = query.replace(Regex("[ ]+(?:song|gaana|gaane)$", RegexOption.IGNORE_CASE), "")
        return query.trim().trim(',', '.', '!', '?')
    }

    fun extractCallTarget(spoken: String): String {
        return spoken.trim()
            .replace(Regex("^(?:please[ ]+)?(?:call|dial)(?:[ ]+to)?[ ]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:please[ ]+)?(?:phone[ ]+lagao|phone[ ]+(?:karo|kar[ ]+do)|call[ ]+(?:karo|kar[ ]+do)|dial[ ]+karo)[ ]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[ ]+(?:ko[ ]+)?(?:phone[ ]+lagao|phone[ ]+(?:karo|kar[ ]+do)|call[ ]+(?:karo|kar[ ]+do)|dial[ ]+karo|call|dial)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[ ]+ko$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun parseWhatsAppIntent(spoken: String): Pair<String, String> {
        val clean = spoken.trim()
        val lower = clean.lowercase(Locale.ROOT)
        val strippedOpen = lower
            .replace("open", "")
            .replace("launch", "")
            .replace("kholo", "")
            .replace("whatsapp", "")
            .replace("app", "")
            .replace("please", "")
            .trim()
        if (strippedOpen.isEmpty()) return Pair("", "")

        val withoutPrefix = clean
            .replace(Regex("^(?:open[ ]+whatsapp[ ]+(?:and[ ]+)?|whatsapp[ ]+(?:open[ ]+karke|khol[ ]+ke|kholo[ ]+aur|par|pe|mein|me)[ ]+)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:please[ ]+)?(?:send[ ]+(?:a[ ]+)?(?:whatsapp[ ]+)?(?:message|msg|text)[ ]+(?:on[ ]+whatsapp[ ]+)?(?:to|for)[ ]+)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:whatsapp[ ]+(?:message|msg)[ ]+(?:to|for)[ ]+|(?:message|msg|whatsapp)[ ]+to[ ]+)", RegexOption.IGNORE_CASE), "")
            .trim()

        val koIndex = Regex("[ ]+ko[ ]+", RegexOption.IGNORE_CASE).find(withoutPrefix)
        if (koIndex != null) {
            val recipient = withoutPrefix.substring(0, koIndex.range.first)
                .replace(Regex("^(?:whatsapp[ ]+par|whatsapp[ ]+pe|whatsapp|to|send|message)[ ]+", RegexOption.IGNORE_CASE), "")
                .trim()
            val body = withoutPrefix.substring(koIndex.range.last + 1).trim()
            if (recipient.isNotBlank()) return Pair(recipient, cleanWhatsAppMessageBody(body))
        }

        val explicitBody = Regex(
            "(?:to|for)[ ]+(.+?)[ ]+(?:saying|that|message(?:[ ]+is)?|msg(?:[ ]+is)?|text(?:[ ]+is)?)[ ]+(.+)$",
            RegexOption.IGNORE_CASE
        ).find(clean)
        if (explicitBody != null) {
            return Pair(explicitBody.groupValues[1].trim(), cleanWhatsAppMessageBody(explicitBody.groupValues[2]))
        }

        val toMatch = Regex("(?:to|for)[ ]+([a-zA-Z0-9+]+)[ ]+(.+)$", RegexOption.IGNORE_CASE).find(clean)
        if (toMatch != null) {
            return Pair(toMatch.groupValues[1].trim(), cleanWhatsAppMessageBody(toMatch.groupValues[2]))
        }

        val tokens = withoutPrefix.split(Regex("[ ]+")).filter { it.isNotBlank() }
        if (tokens.size >= 2 && tokens[0].lowercase(Locale.ROOT) !in setOf("whatsapp", "message", "send")) {
            val recipient = tokens.first()
            val body = withoutPrefix.substringAfter(recipient).trim()
            return Pair(recipient, cleanWhatsAppMessageBody(body))
        }
        return Pair("", "")
    }

    private fun cleanWhatsAppMessageBody(raw: String): String {
        var msg = raw.trim()
        msg = msg.replace(
            Regex("^(?:on[ ]+whatsapp[ ]+|whatsapp[ ]+par[ ]+|whatsapp[ ]+pe[ ]+)?(?:a[ ]+)?(?:whatsapp[ ]+message|whatsapp|message|msg)[ ]+(?:bhejo|bhego|bhej[ ]+do|karo|kar[ ]+do|send[ ]+karo|likho|bolo|saying|that|ki)[ ]+", RegexOption.IGNORE_CASE),
            ""
        )
        msg = msg.replace(Regex("^(?:bhejo|bhego|bhej[ ]+do|saying|that|ki|likho|bolo|message|msg)[ ]+", RegexOption.IGNORE_CASE), "")
        msg = msg.replace(
            Regex("[ ]+(?:whatsapp[ ]+message[ ]+bhejo|message[ ]+bhejo|msg[ ]+bhejo|whatsapp[ ]+karo|message[ ]+karo|msg[ ]+karo|bhejo|bhego|bhej[ ]+do|send[ ]+karo|on[ ]+whatsapp)$", RegexOption.IGNORE_CASE),
            ""
        )
        if (Regex("^(?:whatsapp[ ]+)?(?:message|msg)[ ]+(?:bhejo|bhego|send[ ]+karo)|(?:bhejo|bhego|bhej[ ]+do)$", RegexOption.IGNORE_CASE).matches(msg.trim())) {
            return ""
        }
        return msg.trim()
    }

    fun accountSignupTarget(spoken: String): String? {
        val lower = spoken.lowercase(Locale.ROOT)
        return listOf("instagram", "facebook", "whatsapp", "telegram", "snapchat", "spotify")
            .firstOrNull { lower.contains(it) }
    }

    fun isAccountSignupRequest(lowercaseSpoken: String): Boolean {
        val lower = lowercaseSpoken.lowercase(Locale.ROOT)
        return lower.contains("create account") || lower.contains("create an account") ||
            lower.contains("sign up") || lower.contains("signup") || lower.contains("register") ||
            lower.contains("account banao") || lower.contains("account bana do") || lower.contains("account banana") ||
            lower.contains("account create") || lower.contains("naya account")
    }
}
