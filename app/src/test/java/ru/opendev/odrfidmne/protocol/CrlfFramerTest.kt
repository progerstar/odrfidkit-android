package ru.opendev.odrfidmne.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CrlfFramerTest {
    @Test
    fun fragmentedAndCombinedPacketsAreReassembled() {
        val framer = CrlfFramer()

        assertEquals(emptyList<String>(), framer.feed("+UID=01\r".toByteArray()))
        assertEquals(
            listOf("+UID=01", "OK"),
            framer.feed("\nOK\r\nPART".toByteArray()),
        )
        assertEquals(listOf("PARTIAL"), framer.feed("IAL\r\n".toByteArray()))
        assertEquals(0, framer.bufferedByteCount())
    }

    @Test
    fun emptyLineIsAValidCrlfFrame() {
        assertEquals(listOf(""), CrlfFramer().feed("\r\n".toByteArray()))
    }

    @Test
    fun rejectsLfWithoutCrAndDataAfterCr() {
        assertThrows(FramingException::class.java) {
            CrlfFramer().feed("OK\n".toByteArray())
        }
        assertThrows(FramingException::class.java) {
            CrlfFramer().feed("OK\rX".toByteArray())
        }
    }

    @Test
    fun rejectsNonAsciiAndBufferOverflow() {
        assertThrows(FramingException::class.java) {
            CrlfFramer().feed(byteArrayOf(0x01))
        }
        val framer = CrlfFramer(maxBufferBytes = 8)
        assertThrows(FramingException::class.java) {
            framer.feed("123456789".toByteArray())
        }
    }

    @Test
    fun resetDropsIncompleteFrame() {
        val framer = CrlfFramer()
        framer.feed("OLD".toByteArray())
        framer.reset()
        assertEquals(listOf("NEW"), framer.feed("NEW\r\n".toByteArray()))
    }
}
