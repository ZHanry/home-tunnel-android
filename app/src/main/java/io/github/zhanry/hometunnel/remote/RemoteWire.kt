package io.github.zhanry.hometunnel.remote

import io.github.zhanry.hometunnel.remote.protocol.RemoteProtocol as P
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object RemoteWire {
    fun frame(type: Int, epoch: Long, inputEpoch: Long, sequence: Long, payload: ByteArray, maximum: Int = 8192, flags: Int = 0): ByteArray {
        require(type in 0..255 && epoch in 1..0xffffffffL && inputEpoch in 0..0xffffffffL)
        require(sequence in 1 until P.SEQUENCE_RECONNECT_AT && payload.size + P.HEADER_LENGTH <= maximum)
        require(flags == 0 || type == P.BUTTON && flags == 1)
        return ByteBuffer.allocate(P.HEADER_LENGTH + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(P.MAGIC.toShort()).put(P.VERSION.toByte()).put(type.toByte())
            .putShort(flags.toShort()).putShort(P.HEADER_LENGTH.toShort())
            .putInt(epoch.toInt()).putInt(inputEpoch.toInt()).putInt(sequence.toInt()).putInt(payload.size)
            .put(payload).array()
    }
    fun keyPayload(usage: Int, down: Boolean, repeat: Boolean = false): ByteArray {
        require(usage in 1..255 && (!repeat || down))
        return ByteBuffer.allocate(8).putShort(7).putShort(usage.toShort()).put(if (down) 1 else 0)
            .put(if (repeat) 1 else 0).putShort(0).array()
    }
    fun textPayload(text: String, submission: UUID = UUID.randomUUID()): ByteArray {
        require(RemoteJson.validUnicode(text)) { "RD_TEXT_UNICODE" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..P.TEXT_BYTES) { "RD_TEXT_TOO_LARGE" }
        return ByteBuffer.allocate(20 + bytes.size).putLong(submission.mostSignificantBits)
            .putLong(submission.leastSignificantBits).putInt(bytes.size).put(bytes).array()
    }
    fun pointerPayload(layout: Long, display: Int, x: Int, y: Int, motion: Long): ByteArray {
        coordinates(layout, display, x, y, motion)
        return ByteBuffer.allocate(16).putInt(layout.toInt()).putShort(display.toShort())
            .putShort(x.toShort()).putShort(y.toShort()).putShort(0).putInt(motion.toInt()).array()
    }
    fun buttonPayload(layout: Long, display: Int, x: Int, y: Int, button: Int, down: Boolean, motion: Long): ByteArray {
        coordinates(layout, display, x, y, motion)
        require(button in 1..5)
        return ByteBuffer.allocate(32).putInt(layout.toInt()).putShort(display.toShort())
            .putShort(x.toShort()).putShort(y.toShort()).put(button.toByte()).put(if (down) 1 else 0)
            .putInt(motion.toInt()).putLong(0).putLong(0).array()
    }
    private fun coordinates(layout: Long, display: Int, x: Int, y: Int, motion: Long) {
        require(layout in 1..0xffffffffL && display in 0..15 && x in 0..65535 && y in 0..65535 && motion in 0 until P.SEQUENCE_RECONNECT_AT)
    }

    /** Fit-center coordinates; taps in letterbox bars never become desktop clicks. */
    fun point(x: Float, y: Float, viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int): Pair<Int, Int>? {
        if (!x.isFinite() || !y.isFinite() || minOf(viewWidth, viewHeight, videoWidth, videoHeight) <= 0) return null
        val scale = minOf(viewWidth.toDouble() / videoWidth, viewHeight.toDouble() / videoHeight)
        val left = (viewWidth - videoWidth * scale) / 2
        val top = (viewHeight - videoHeight * scale) / 2
        val px = (x - left) / scale
        val py = (y - top) / scale
        if (px < 0 || py < 0 || px >= videoWidth || py >= videoHeight) return null
        return (px / maxOf(1, videoWidth - 1) * 65535).toInt().coerceIn(0, 65535) to
            (py / maxOf(1, videoHeight - 1) * 65535).toInt().coerceIn(0, 65535)
    }
}
