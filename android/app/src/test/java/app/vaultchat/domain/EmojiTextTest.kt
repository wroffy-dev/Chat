package app.vaultchat.domain

import app.vaultchat.domain.chat.EmojiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiTextTest {
    @Test
    fun `counts whole emoji, including skin tones, flags, keycaps and ZWJ sequences`() {
        assertEquals(1, EmojiText.count("😂")) // 😂
        assertEquals(1, EmojiText.count("❤️")) // ❤️
        assertEquals(1, EmojiText.count("👍🏽")) // 👍🏽
        assertEquals(1, EmojiText.count("👨‍👩‍👧")) // 👨‍👩‍👧
        assertEquals(2, EmojiText.count("🇮🇳🇺🇸")) // 🇮🇳🇺🇸
        assertEquals(1, EmojiText.count("1️⃣")) // 1️⃣
        assertEquals(2, EmojiText.count("😂 😂"))
    }

    @Test
    fun `anything that is not emoji is not big`() {
        assertNull(EmojiText.count("hi 😂"))
        assertNull(EmojiText.count("1"))
        assertNull(EmojiText.count(":)"))
        assertFalse(EmojiText.isBigEmoji(""))
        assertFalse(EmojiText.isBigEmoji("ok"))
    }

    @Test
    fun `one to three emoji are shown big, four or more are not`() {
        assertTrue(EmojiText.isBigEmoji(" 😂 "))
        assertTrue(EmojiText.isBigEmoji("😂😂😂"))
        assertFalse(EmojiText.isBigEmoji("😂😂😂😂"))
    }
}
