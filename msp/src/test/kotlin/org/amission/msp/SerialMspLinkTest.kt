package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun reply(command: Int, payload: ByteArray, error: Boolean = false): ByteArray {
    var crc = payload.size xor command
    for (b in payload) crc = crc xor (b.toInt() and 0xFF)
    return byteArrayOf('$'.code.toByte(), 'M'.code.toByte(), (if (error) '!' else '>').code.toByte(),
        payload.size.toByte(), command.toByte()) + payload + byteArrayOf(crc.toByte())
}

/** Channel that answers each write with queued bytes, delivered in tiny chunks. */
private class ScriptedChannel(private val responses: ArrayDeque<ByteArray>, val time: LongArray) : ByteChannel {
    val writes = mutableListOf<ByteArray>()
    private var pending = ByteArray(0)

    override fun write(data: ByteArray) {
        writes += data
        responses.removeFirstOrNull()?.let { pending += it }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (pending.isEmpty()) { time[0] += timeoutMs; return 0 }
        val n = minOf(3, pending.size, buffer.size)
        pending.copyInto(buffer, 0, 0, n)
        pending = pending.copyOfRange(n, pending.size)
        return n
    }
}

class SerialMspLinkTest {
    private val time = longArrayOf(0)
    private fun link(vararg responses: ByteArray, retries: Int = 2): Pair<SerialMspLink, ScriptedChannel> {
        val ch = ScriptedChannel(ArrayDeque(responses.toList()), time)
        return SerialMspLink(ch, timeoutMs = 500, retries = retries, clock = { time[0] }) to ch
    }

    @Test
    fun returnsPayloadOfMatchingReplyIgnoringStaleOnes() {
        val stale = reply(99, byteArrayOf(1))
        val good = reply(20, byteArrayOf(0, 120, 1, 4))
        val (link, _) = link(stale + good)
        assertContentEquals(byteArrayOf(0, 120, 1, 4), link.request(Msp.WP_GETINFO))
    }

    @Test
    fun retriesAfterTimeout() {
        val (link, ch) = link(ByteArray(0), reply(20, byteArrayOf(0, 120, 0, 0)))
        link.request(Msp.WP_GETINFO)
        assertEquals(2, ch.writes.size)
    }

    @Test
    fun throwsTimeoutAfterRetriesExhausted() {
        val (link, ch) = link(retries = 1)
        assertFailsWith<MspTimeoutException> { link.request(Msp.WP_GETINFO) }
        assertEquals(2, ch.writes.size)
    }

    @Test
    fun errorFrameThrowsWithoutRetry() {
        val (link, ch) = link(reply(209, ByteArray(0), error = true))
        assertFailsWith<MspException> { link.request(Msp.SET_WP, ByteArray(21)) }
        assertEquals(1, ch.writes.size)
    }
}
