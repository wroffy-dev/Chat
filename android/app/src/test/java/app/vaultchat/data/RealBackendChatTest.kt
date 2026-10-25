package app.vaultchat.data

import app.vaultchat.data.auth.AuthApi
import app.vaultchat.data.auth.AuthorizedCaller
import app.vaultchat.data.auth.DeviceDto
import app.vaultchat.data.auth.RemoteAuthRepository
import app.vaultchat.data.chat.RemoteChatRepository
import app.vaultchat.data.network.ApiClient
import app.vaultchat.data.network.HttpJson
import app.vaultchat.domain.auth.LoginResult
import app.vaultchat.domain.auth.MeResult
import app.vaultchat.domain.chat.ChatController
import app.vaultchat.domain.chat.ChatNotice
import app.vaultchat.domain.chat.ChatPhase
import app.vaultchat.domain.chat.ChatResult
import app.vaultchat.domain.chat.PendingLeave
import app.vaultchat.domain.chat.PendingLeaveStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two connected users chat through the real backend using the app's own chat logic, and the
 * D-01 option D exit rule is checked end to end. Opt-in: needs VAULTCHAT_TEST_API_URL and two
 * connected users with changed passwords in VAULTCHAT_TEST_CHAT ("userA:passA:userB:passB").
 */
class RealBackendChatTest {
    private class Store : PendingLeaveStore {
        var value: PendingLeave? = null
        override fun save(leave: PendingLeave) {
            value = leave
        }
        override fun load() = value
        override fun clear() {
            value = null
        }
    }

    private class Client(url: String) {
        val store = InMemoryTokenStore()
        private val http = HttpJson(url, ApiClient.defaultHttpClient())
        private val caller = AuthorizedCaller(AuthApi(http), store)
        val auth = RemoteAuthRepository(AuthApi(http), store, DeviceDto("JVM test", 1), caller)
        val chat = RemoteChatRepository(http, caller)
        val pending = Store()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(100)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    @Test
    fun `two users chat and an exit deletes what was seen, for both`() = runBlocking {
        val url = System.getenv("VAULTCHAT_TEST_API_URL")
        val users = System.getenv("VAULTCHAT_TEST_CHAT")?.split(":")
        assumeTrue("real backend chat not configured", !url.isNullOrBlank() && users?.size == 4)
        val (userA, passA, userB, passB) = users!!
        val a = Client(url!!)
        val b = Client(url)
        try {
            assertEquals(LoginResult.Success(false), a.auth.login(userA, passA))
            assertEquals(LoginResult.Success(false), b.auth.login(userB, passB))
            val meA = a.auth.me() as MeResult.Success
            val meB = b.auth.me() as MeResult.Success
            val conversation = meA.connection!!.conversationId
            assertEquals(conversation, meB.connection!!.conversationId)

            val chatA = ChatController(a.chat, a.pending, conversation, meA.user.id, a.scope, a.scope)
            val chatB = ChatController(b.chat, b.pending, conversation, meB.user.id, b.scope, b.scope)
            chatA.start()
            chatB.start()
            waitFor("both open") { chatA.state.value.phase == ChatPhase.OPEN && chatB.state.value.phase == ChatPhase.OPEN }

            assertTrue(chatA.send("hello from A"))
            waitFor("B receives") { chatB.state.value.messages.any { it.text == "hello from A" } }
            assertEquals(meA.user.id, chatB.state.value.messages.single().senderId)

            // A message B has not displayed yet: B looks away (a call on top) before it arrives.
            chatB.setOnScreen(false)
            assertTrue(chatA.send("sent while B is not looking"))
            waitFor("B holds it unseen") { chatB.state.value.messages.size == 2 }
            delay(500) // let B's read receipt for the first message settle

            // A leaves: the message B saw is deleted for both; the unseen one waits for B.
            chatA.leave()
            waitFor("A's exit sent") { a.pending.value == null }
            waitFor("B sees the clear") { chatB.state.value.messages.map { it.text } == listOf("sent while B is not looking") }
            assertEquals(ChatNotice.CLEARED, chatB.state.value.notice)

            // B looks at it and leaves: nothing is left on the server.
            chatB.setOnScreen(true)
            delay(500)
            chatB.leave()
            waitFor("B's exit sent") { b.pending.value == null }
            val history = a.chat.history(conversation, 0, null) as ChatResult.Ok
            assertTrue("server kept: ${history.value.items.map { it.text }}", history.value.items.isEmpty())
            assertNull(a.pending.value)
        } finally {
            a.scope.cancel()
            b.scope.cancel()
        }
    }
}
