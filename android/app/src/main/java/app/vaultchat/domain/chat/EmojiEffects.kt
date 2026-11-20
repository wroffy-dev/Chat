package app.vaultchat.domain.chat

/**
 * Full-screen effects for emoji messages, as in Apple Messages (owner request 2026-11-20): a heart sends
 * hearts floating up the screen, a party popper rains confetti, and so on. Only messages that are nothing
 * but 1 to 3 emoji trigger one; the first emoji that has an effect chooses it. Nothing is sent or stored:
 * each phone plays the effect when the message appears.
 */
object EmojiEffects {
    /** How the pieces move: up from the bottom, or down from the top. */
    enum class Motion { RISE, FALL }

    data class Effect(val pieces: List<String>, val motion: Motion)

    private val HEARTS = Effect(listOf("❤️", "💖", "💕", "💗"), Motion.RISE)
    private val CONFETTI = Effect(listOf("🎉", "🎊", "✨", "🎈"), Motion.FALL)
    private val FIRE = Effect(listOf("🔥"), Motion.RISE)
    private val LAUGH = Effect(listOf("😂", "🤣"), Motion.RISE)
    private val STARS = Effect(listOf("⭐", "✨", "🌟"), Motion.FALL)
    private val FLOWERS = Effect(listOf("🌹", "🌸", "🌺"), Motion.FALL)
    private val THUMBS = Effect(listOf("👍"), Motion.RISE)

    /** Emoji (without variation selectors or skin tones) and their effect, checked in this order. */
    private val TRIGGERS: List<Pair<String, Effect>> = listOf(
        // Hearts of every colour, and loving faces.
        "❤" to HEARTS, "🧡" to HEARTS, "💛" to HEARTS, "💚" to HEARTS,
        "💙" to HEARTS, "💜" to HEARTS, "🖤" to HEARTS, "🤍" to HEARTS,
        "🤎" to HEARTS, "💕" to HEARTS, "💖" to HEARTS, "💗" to HEARTS,
        "💓" to HEARTS, "💞" to HEARTS, "💘" to HEARTS, "💝" to HEARTS,
        "😍" to HEARTS, "🥰" to HEARTS, "😘" to HEARTS, "😻" to HEARTS,
        // Celebration.
        "🎉" to CONFETTI, "🎊" to CONFETTI, "🥳" to CONFETTI, "🎂" to CONFETTI,
        "🎈" to CONFETTI,
        "🔥" to FIRE,
        "😂" to LAUGH, "🤣" to LAUGH,
        "⭐" to STARS, "🌟" to STARS, "✨" to STARS,
        "🌹" to FLOWERS, "🌸" to FLOWERS, "💐" to FLOWERS,
        "👍" to THUMBS,
    )

    /** The effect for [text], or null when it is not an emoji-only message with an effect. */
    fun effectFor(text: String): Effect? {
        val trimmed = text.trim()
        val count = EmojiText.count(trimmed) ?: return null
        if (count !in 1..EmojiText.MAX_BIG) return null
        val plain = strip(trimmed)
        // The earliest emoji in the message that has an effect wins.
        return TRIGGERS
            .mapNotNull { (emoji, effect) -> plain.indexOf(emoji).takeIf { it >= 0 }?.let { it to effect } }
            .minByOrNull { it.first }
            ?.second
    }

    /** Drops variation selectors and skin-tone modifiers, so "❤️" and "👍🏽" match "❤" and "👍". */
    private fun strip(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF) continue
            appendCodePoint(cp)
        }
    }
}
