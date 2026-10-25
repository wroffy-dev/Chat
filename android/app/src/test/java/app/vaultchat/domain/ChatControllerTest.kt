package app.vaultchat.domain

import app.vaultchat.domain.chat.ChatController
import app.vaultchat.domain.chat.ChatFailure
import app.vaultchat.domain.chat.ChatMessage
import app.vaultchat.domain.chat.ChatNotice
import app.vaultchat.domain.chat.ChatPhase
import app.vaultchat.domain.chat.ChatRepository
import app.vaultchat.domain.chat.ChatResult
import app.vaultchat.domain.chat.ChatText
import app.vaultchat.domain.chat.EnterInfo
import app.vaultchat.domain.chat.HistoryPage
import app.vaultchat.domain.chat.OutgoingStatus
import app.vaultchat.domain.chat.PendingLeave
import app.vaultchat.domain.chat.PendingLeaveStore
import app.vaultchat.domain.chat.SentMessage
import app.vaultchat.domain.chat.flushPendingLeave
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tiny in-memory stand-in for the chat API, following the server's epoch rules. */
class FakeChatRepo : ChatRepository {
    val log = mutableListOf<String>()
    var epoch = 0L
    val server = mutableListOf<ChatMessage>()
    private var nextSeq = 1L
    private var leases = 0
    var heartbeatFailure: ChatFailure? = null
    var sendFailure: ChatFailure? = null
    var leaveFailure: ChatFailure? = null
    val reads = mutableListOf<Long>()
    val sends = mutableListOf<Triple<String, Long, String>>()

    override suspend fun enter(conversationId: String): ChatResult<EnterInfo> {
        log += "enter"
        return ChatResult.Ok(EnterInfo("lease${++leases}", 20, epoch))
    }

    override suspend fun heartbeat(conversationId: String, leaseId: String): ChatResult<Unit> {
        log += "beat:$leaseId"
        return heartbeatFailure?.let { ChatResult.Failure(it) } ?: ChatResult.Ok(Unit)
    }

    override suspend fun leave(conversationId: String, leaveId: String): ChatResult<Unit> {
        log += "leave:$leaveId"
        return leaveFailure?.let { ChatResult.Failure(it) } ?: ChatResult.Ok(Unit)
    }

    override suspend fun history(conversationId: String, afterSeq: Long, epoch: Long?): ChatResult<HistoryPage> {
        log += "history:$afterSeq"
        return if (epoch != null && epoch != this.epoch) {
            ChatResult.Ok(HistoryPage(this.epoch, true, server.toList(), false))
        } else {
            ChatResult.Ok(HistoryPage(this.epoch, false, server.filter { it.seq > afterSeq }, false))
        }
    }

    override suspend fun send(conversationId: String, clientMessageId: String, epoch: Long, text: String): ChatResult<SentMessage> {
        sends += Triple(clientMessageId, epoch, text)
        sendFailure?.let { return ChatResult.Failure(it) }
        if (epoch != this.epoch) return ChatResult.Failure(ChatFailure.CLEARED)
        val m = ChatMessage("m$nextSeq", nextSeq++, "me", text, 0)
        server += m
        return ChatResult.Ok(SentMessage(m.id, m.seq, this.epoch, 0))
    }

    override suspend fun markRead(conversationId: String, seq: Long): ChatResult<Unit> {
        reads += seq
        return ChatResult.Ok(Unit)
    }

    fun peerSays(text: String) {
        server += ChatMessage("m$nextSeq", nextSeq++, "peer", text, 0)
    }

    /** The other user left after seeing everything. */
    fun clearAll() {
        server.clear()
        epoch++
    }
}

class MemoryPendingLeaves : PendingLeaveStore {
    var value: PendingLeave? = null
    override fun save(leave: PendingLeave) {
        value = leave
    }
    override fun load() = value
    override fun clear() {
        value = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private val repo = FakeChatRepo()
    private val pending = MemoryPendingLeaves()
    private var ids = 0

    private fun TestScope.controller() =
        ChatController(repo, pending, "conv", "me", backgroundScope, backgroundScope, newId = { "id${++ids}" })

    private fun ChatController.texts() = state.value.messages.map { it.text }

    @Test
    fun `opening the chat enters, loads what the server has and marks it seen`() = runTest {
        repo.peerSays("hi")
        repo.peerSays("there")
        val c = controller()
        c.start()
        runCurrent()
        assertEquals(ChatPhase.OPEN, c.state.value.phase)
        assertEquals(listOf("hi", "there"), c.texts())
        assertEquals(listOf(2L), repo.reads)
    }

    @Test
    fun `an exit that never reached the server is sent before entering again`() = runTest {
        pending.value = PendingLeave("conv", "old-leave")
        controller().start()
        runCurrent()
        assertEquals(listOf("leave:old-leave", "enter"), repo.log.take(2))
        assertNull(pending.value)
    }

    @Test
    fun `new messages arrive by polling and are marked seen only while the chat is visible`() = runTest {
        val c = controller()
        c.start()
        runCurrent()
        repo.peerSays("one")
        advanceTimeBy(ChatController.POLL_MS + 1)
        assertEquals(listOf("one"), c.texts())
        assertEquals(listOf(1L), repo.reads)

        c.setForeground(false)
        advanceTimeBy(ChatController.POLL_MS + 1) // finishes the cycle already under way
        val polls = repo.log.count { it.startsWith("history") }
        repo.peerSays("two")
        advanceTimeBy(60_000)
        assertEquals("nothing is polled or renewed in the background", polls, repo.log.count { it.startsWith("history") })
        assertEquals(listOf(1L), repo.reads)

        c.setForeground(true)
        runCurrent()
        assertTrue("renews the lease straight away on return", repo.log.last { !it.startsWith("history") }.startsWith("beat"))
        assertEquals(listOf("one", "two"), c.texts())
        assertEquals(listOf(1L, 2L), repo.reads)
    }

    @Test
    fun `under a call screen the chat stays open but nothing new counts as seen`() = runTest {
        val c = controller()
        c.start()
        runCurrent()
        c.setOnScreen(false)
        repo.peerSays("during the call")
        advanceTimeBy(41_000)
        assertEquals(listOf("during the call"), c.texts())
        assertTrue("lease still renewed", repo.log.count { it.startsWith("beat") } >= 2)
        assertTrue(repo.reads.isEmpty())

        c.setOnScreen(true)
        runCurrent()
        assertEquals(listOf(1L), repo.reads)
    }

    @Test
    fun `heartbeats run every 20 seconds while visible`() = runTest {
        controller().start()
        runCurrent()
        advanceTimeBy(41_000)
        assertEquals(2, repo.log.count { it.startsWith("beat") })
    }

    @Test
    fun `sending trims, shows the message as mine and keeps nothing pending`() = runTest {
        val c = controller()
        c.start()
        runCurrent()
        assertTrue(c.send("  hello  "))
        runCurrent()
        assertEquals(listOf("hello"), c.texts())
        assertEquals("me", c.state.value.messages.single().senderId)
        assertTrue(c.state.value.outgoing.isEmpty())
        assertFalse("whitespace is not sent", c.send("   "))
    }

    @Test
    fun `too long messages are refused on the device`() = runTest {
        val c = controller()
        c.start()
        runCurrent()
        assertTrue(ChatText.tooLong("x".repeat(4001)))
        assertFalse(ChatText.tooLong("😀".repeat(4000)))
        assertFalse(c.send("x".repeat(4001)))
        assertEquals(ChatNotice.TOO_LONG, c.state.value.notice)
        assertTrue(repo.sends.isEmpty())
    }

    @Test
    fun `a send refused because the chat was cleared puts the text back and resyncs`() = runTest {
        repo.peerSays("seen")
        val c = controller()
        c.start()
        runCurrent()
        repo.clearAll()
        c.send("composed before the clear")
        runCurrent()
        assertEquals("composed before the clear", c.state.value.restoredDraft)
        assertEquals(ChatNotice.CLEARED, c.state.value.notice)
        assertTrue(c.texts().isEmpty())
        c.draftRestored()
        assertNull(c.state.value.restoredDraft)
        // Now on the new epoch, sending works.
        c.send("again")
        runCurrent()
        assertEquals(listOf("again"), c.texts())
    }

    @Test
    fun `a failed send is retried with the same id and epoch`() = runTest {
        val c = controller()
        c.start()
        runCurrent()
        repo.sendFailure = ChatFailure.NETWORK
        c.send("hello")
        runCurrent()
        val failed = c.state.value.outgoing.single()
        assertEquals(OutgoingStatus.FAILED, failed.status)
        assertEquals(ChatNotice.NETWORK, c.state.value.notice)

        repo.sendFailure = null
        c.retry(failed.clientMessageId)
        runCurrent()
        assertEquals(repo.sends[0].first, repo.sends[1].first)
        assertEquals(repo.sends[0].second, repo.sends[1].second)
        assertEquals(listOf("hello"), c.texts())
        assertTrue(c.state.value.outgoing.isEmpty())
    }

    @Test
    fun `when the other side clears the chat, removed messages disappear here too`() = runTest {
        repo.peerSays("a")
        val c = controller()
        c.start()
        runCurrent()
        repo.clearAll()
        repo.peerSays("unseen stays")
        advanceTimeBy(ChatController.POLL_MS + 1)
        assertEquals(listOf("unseen stays"), c.texts())
        assertEquals(ChatNotice.CLEARED, c.state.value.notice)
    }

    @Test
    fun `coming back after the lease lapsed drops everything and enters again`() = runTest {
        repo.peerSays("a")
        val c = controller()
        c.start()
        runCurrent()
        c.setForeground(false)
        advanceTimeBy(90_000)
        repo.heartbeatFailure = ChatFailure.LEASE_EXPIRED
        repo.clearAll() // the server exited for us and deleted what was seen
        c.setForeground(true)
        runCurrent()
        repo.heartbeatFailure = null
        assertEquals(ChatNotice.LEFT_WHILE_AWAY, c.state.value.notice)
        assertEquals(2, repo.log.count { it == "enter" })
        assertTrue(c.texts().isEmpty())
        assertEquals(ChatPhase.OPEN, c.state.value.phase)
    }

    @Test
    fun `leaving drops messages at once and sends the exit, kept until the server confirms`() = runTest {
        repo.peerSays("a")
        val c = controller()
        c.start()
        runCurrent()
        repo.leaveFailure = ChatFailure.NETWORK
        c.leave()
        assertEquals(ChatPhase.LEFT, c.state.value.phase)
        assertTrue(c.texts().isEmpty())
        runCurrent()
        val stored = pending.value!!
        assertTrue(repo.log.contains("leave:${stored.leaveId}"))

        // Next launch: the same exit is retried, so it is applied once.
        repo.leaveFailure = null
        flushPendingLeave(repo, pending)
        assertEquals(2, repo.log.count { it == "leave:${stored.leaveId}" })
        assertNull(pending.value)

        val polls = repo.log.size
        advanceTimeBy(60_000)
        assertEquals("nothing runs after leaving", polls, repo.log.size)
    }

    @Test
    fun `a revoked session ends the chat`() = runTest {
        val c = controller()
        c.start()
        runCurrent()
        repo.sendFailure = ChatFailure.SESSION_ENDED
        c.send("x")
        runCurrent()
        assertEquals(ChatPhase.SESSION_ENDED, c.state.value.phase)
        c.leave()
        assertNull("no exit is stored for an ended session", pending.value)
    }
}
