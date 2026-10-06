package ru.opendev.odrfidmne.protocol

internal class FramingException(message: String) : IllegalStateException(message)

internal class CrlfFramer(
    private val maxBufferBytes: Int = MAX_READ_BUFFER,
) {
    private val buffer = ArrayList<Byte>()

    fun feed(chunk: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        for (rawByte in chunk) {
            val value = rawByte.toInt() and 0xFF
            if (value != CR && value != LF && value != TAB && value !in PRINTABLE_ASCII) {
                throw FramingException("В потоке CDC получен не-ASCII байт 0x%02X".format(value))
            }

            if (value == LF) {
                if (buffer.isEmpty() || (buffer.last().toInt() and 0xFF) != CR) {
                    throw FramingException("Получен LF без предшествующего CR")
                }
                buffer.removeAt(buffer.lastIndex)
                lines += buffer.toByteArray().toString(Charsets.US_ASCII)
                buffer.clear()
                continue
            }

            if (buffer.isNotEmpty() && (buffer.last().toInt() and 0xFF) == CR) {
                throw FramingException("После CR ожидался LF")
            }

            buffer += rawByte
            if (buffer.size > maxBufferBytes) {
                throw FramingException("Буфер CDC превысил $maxBufferBytes байт без CRLF")
            }
        }
        return lines
    }

    fun reset() = buffer.clear()

    internal fun bufferedByteCount(): Int = buffer.size

    private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }

    private companion object {
        const val CR = 0x0D
        const val LF = 0x0A
        const val TAB = 0x09
        val PRINTABLE_ASCII = 0x20..0x7E
        const val MAX_READ_BUFFER = 64 * 1024
    }
}

