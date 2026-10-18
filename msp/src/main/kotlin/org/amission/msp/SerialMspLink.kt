package org.amission.msp

/** Raw byte pipe to the FC. USB serial, Bluetooth SPP/BLE and TCP each provide one. */
interface ByteChannel {
    fun write(data: ByteArray)

    /** Reads up to [buffer].size bytes, waiting at most [timeoutMs]. Returns the count read (0 on timeout). */
    fun read(buffer: ByteArray, timeoutMs: Int): Int
}

/**
 * [MspLink] over any [ByteChannel]: sends a v1 request and waits for the reply with the same
 * command id. Replies for other commands (e.g. stale data) are discarded. Not thread-safe;
 * serialise calls from the caller.
 */
class SerialMspLink(
    private val channel: ByteChannel,
    private val timeoutMs: Long = 1000,
    private val retries: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
) : MspLink {
    private val parser = MspParser()
    private val buffer = ByteArray(256)

    override fun request(command: Int, payload: ByteArray): ByteArray {
        var lastTimeout: MspException? = null
        repeat(retries + 1) {
            channel.write(Msp.encodeRequest(command, payload))
            try {
                return awaitReply(command)
            } catch (e: MspTimeoutException) {
                lastTimeout = e
            }
        }
        throw lastTimeout ?: MspTimeoutException(command)
    }

    private fun awaitReply(command: Int): ByteArray {
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            val n = channel.read(buffer, 50)
            for (i in 0 until n) {
                val msg = parser.feed(buffer[i]) ?: continue
                if (msg.command != command) continue
                if (msg.isError) throw MspException("FC rejected command $command")
                return msg.payload
            }
        }
        throw MspTimeoutException(command)
    }
}

class MspTimeoutException(command: Int) : MspException("No reply to MSP command $command")
