package app.vaultchat.domain.chat

/**
 * Recognises messages that are only emoji, which the chat shows large and without a bubble, as
 * WhatsApp does (D-25). Counted by hand rather than with BreakIterator: on the JVM used for unit
 * tests it splits ZWJ sequences such as family emoji into several characters.
 */
object EmojiText {
    const val MAX_BIG = 3

    /** True when [text] holds 1 to [max] emoji and nothing else except spaces. */
    fun isBigEmoji(text: String, max: Int = MAX_BIG): Boolean {
        val count = count(text.trim()) ?: return false
        return count in 1..max
    }

    /** Number of emoji in [text], or null when it holds anything that is not part of an emoji. */
    fun count(text: String): Int? {
        val keycap = text.contains('⃣')
        var count = 0
        var afterZwj = false
        var openFlag = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == ZWJ -> {
                    afterZwj = true
                    continue
                }
                isModifier(cp) -> Unit
                cp == ' '.code -> openFlag = false
                isRegionalIndicator(cp) -> {
                    // A flag is a pair of regional indicators.
                    if (openFlag) openFlag = false else {
                        count++
                        openFlag = true
                    }
                }
                isPictographic(cp) || (keycap && isKeycapBase(cp)) -> if (!afterZwj) count++
                else -> return null
            }
            afterZwj = false
        }
        return count
    }

    private const val ZWJ = 0x200D

    /** Variation selectors, keycap, skin tones and tag characters: they belong to the emoji before them. */
    private fun isModifier(cp: Int) =
        cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isKeycapBase(cp: Int) = cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code

    private fun isPictographic(cp: Int) =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF ||
            cp in 0x2190..0x21FF || cp in 0x2900..0x297F || cp in 0x25A0..0x25FF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp == 0x24C2 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
}
