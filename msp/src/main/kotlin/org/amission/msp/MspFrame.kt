package org.amission.msp

/** A decoded MSP v1 message. [isError] is true for '!' frames (FC rejected the command). */
class MspMessage(val command: Int, val payload: ByteArray, val isError: Boolean = false)

open class MspException(message: String) : Exception(message)

object Msp {
    const val WP_MISSION_LOAD = 18
    const val WP_MISSION_SAVE = 19
    const val WP_GETINFO = 20
    const val WP = 118
    const val SET_WP = 209

    /** Builds `$M<` + len + cmd + payload + xor checksum. Payload is limited to 255 bytes in v1. */
    fun encodeRequest(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(command in 0..255) { "MSP v1 command out of range: $command" }
        require(payload.size <= 255) { "MSP v1 payload too large: ${payload.size}" }
        val out = ByteArray(payload.size + 6)
        out[0] = '$'.code.toByte()
        out[1] = 'M'.code.toByte()
        out[2] = '<'.code.toByte()
        out[3] = payload.size.toByte()
        out[4] = command.toByte()
        payload.copyInto(out, 5)
        var crc = payload.size xor command
        for (b in payload) crc = crc xor (b.toInt() and 0xFF)
        out[out.size - 1] = crc.toByte()
        return out
    }
}

/**
 * Streaming MSP v1 response parser. Feed it bytes as they arrive from the link; it returns a
 * message when a complete, checksum-valid frame has been seen. Garbage between frames and
 * frames with a bad checksum are skipped.
 */
class MspParser {
    private enum class State { IDLE, HEADER_M, DIRECTION, LENGTH, COMMAND, PAYLOAD, CHECKSUM }

    private var state = State.IDLE
    private var isError = false
    private var length = 0
    private var command = 0
    private var payload = ByteArray(0)
    private var index = 0
    private var crc = 0

    /** Returns a message if [byte] completed a valid frame, else null. */
    fun feed(byte: Byte): MspMessage? {
        val b = byte.toInt() and 0xFF
        when (state) {
            State.IDLE -> if (b == '$'.code) state = State.HEADER_M
            State.HEADER_M -> state = if (b == 'M'.code) State.DIRECTION else State.IDLE
            State.DIRECTION -> when (b) {
                '>'.code -> { isError = false; state = State.LENGTH }
                '!'.code -> { isError = true; state = State.LENGTH }
                else -> state = State.IDLE
            }
            State.LENGTH -> {
                length = b
                crc = b
                payload = ByteArray(length)
                index = 0
                state = State.COMMAND
            }
            State.COMMAND -> {
                command = b
                crc = crc xor b
                state = if (length == 0) State.CHECKSUM else State.PAYLOAD
            }
            State.PAYLOAD -> {
                payload[index++] = byte
                crc = crc xor b
                if (index == length) state = State.CHECKSUM
            }
            State.CHECKSUM -> {
                state = State.IDLE
                if (b == crc) return MspMessage(command, payload, isError)
            }
        }
        return null
    }
}
