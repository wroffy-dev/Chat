package app.vaultchat.domain

import app.vaultchat.domain.chat.EmojiEffects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiEffectsTest {
    @Test
    fun `hearts rise for a heart, in any colour and with or without the variation selector`() {
        for (heart in listOf("❤️", "❤", "💙", " 😍 ", "❤️❤️")) {
            val e = EmojiEffects.effectFor(heart)
            assertEquals(heart, EmojiEffects.Motion.RISE, e?.motion)
            assertEquals(heart, "❤️", e?.pieces?.first())
        }
    }

    @Test
    fun `confetti falls for a party popper and skin tones still match`() {
        assertEquals(EmojiEffects.Motion.FALL, EmojiEffects.effectFor("🎉")?.motion)
        assertEquals(listOf("👍"), EmojiEffects.effectFor("👍🏽")?.pieces)
    }

    @Test
    fun `the first emoji with an effect chooses it`() {
        assertEquals("🔥", EmojiEffects.effectFor("🔥❤️")?.pieces?.first())
    }

    @Test
    fun `no effect for text or long emoji runs`() {
        assertNull(EmojiEffects.effectFor("I ❤️ you"))
        assertNull(EmojiEffects.effectFor("❤️❤️❤️❤️"))
        assertNull(EmojiEffects.effectFor(""))
    }

    @Test
    fun `every other emoji floats up itself`() {
        val e = EmojiEffects.effectFor("😐")
        assertEquals(listOf("😐"), e?.pieces)
        assertEquals(EmojiEffects.Motion.RISE, e?.motion)
        assertEquals(listOf("🐶", "🐱"), EmojiEffects.effectFor("🐶 🐱")?.pieces)
    }

    @Test
    fun `tears and snow fall`() {
        assertEquals(EmojiEffects.Motion.FALL, EmojiEffects.effectFor("😭")?.motion)
        assertEquals(EmojiEffects.Motion.FALL, EmojiEffects.effectFor("❄️")?.motion)
    }
}
