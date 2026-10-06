package ru.opendev.odrfidmne.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtResponseParserTest {
    @Test
    fun parsesFirmwareVersionsIncludingSuffix() {
        listOf("3.19", "3.19m", "12.4F").forEach { version ->
            val response = AtResponseParser.parse(
                "I",
                true,
                listOf("Open Development RFID Reader $version Aug 07 2026"),
            )
            assertEquals(version, (response.payload as AtPayload.Firmware).version)
        }
    }

    @Test
    fun parsesUidAndCardStatusWithoutTreatingLfUidAsSak() {
        val uid = AtResponseParser.parse("+i", true, listOf("+UID=0102030408")).payload as AtPayload.Uid
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 8), uid.bytes)

        val hf = AtResponseParser.parse(
            "+S",
            true,
            listOf("+UID=0102030408,BC=64,BS=16,T=0"),
        ).payload as AtPayload.CardStatus
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), hf.uid)
        assertEquals(0x08, hf.sak)
        assertEquals(64, hf.blocks)
        assertEquals(16, hf.blockSize)

        val lf = AtResponseParser.parse(
            "+S",
            true,
            listOf("+UID=0102030405,BC=1,BS=5,T=27"),
        ).payload as AtPayload.CardStatus
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), lf.uid)
        assertEquals(0xFF, lf.sak)
    }

    @Test
    fun parsesHfBlockData() {
        val payload = AtResponseParser.parse(
            "+R",
            true,
            listOf("+DATA 17:00112233445566778899AABBCCDDEEFF"),
        ).payload as AtPayload.BlockData
        assertEquals(17, payload.block)
        assertEquals("00112233445566778899AABBCCDDEEFF", payload.data.toHexForTest())
    }

    @Test
    fun parsesLfInfoAndDistinguishesTraceFromConfig() {
        val trace = AtResponseParser.parse(
            "+LFINFO?",
            true,
            listOf("+LFINFO=T5577,E03900D0"),
        ).payload as AtPayload.LfInfo
        assertEquals("trace", trace.chipInfoKind)

        val config = AtResponseParser.parse(
            "+LFINFO?",
            true,
            listOf("+LFINFO=T5577,00148040"),
        ).payload as AtPayload.LfInfo
        assertEquals("config", config.chipInfoKind)

        val em = AtResponseParser.parse(
            "+LFINFO?",
            true,
            listOf("+LFINFO=EM4305,DEADBEEF"),
        ).payload as AtPayload.LfInfo
        assertEquals("word", em.chipInfoKind)
    }

    @Test
    fun parsesLfClassKeepingAirProtocolAndPhysicalChipSeparate() {
        val payload = AtResponseParser.parse(
            "+LFCLASS=",
            true,
            listOf("+LFCLASS=OK,EM4100_COMPAT,T5577,T5577_TRACE,E0151234"),
        ).payload as AtPayload.LfClass
        assertEquals("OK", payload.status)
        assertEquals("EM4100_COMPAT", payload.air)
        assertEquals("T5577", payload.chip)
        assertEquals("T5577_TRACE", payload.evidence)
        assertEquals("E0151234", payload.chipInfoHex)
    }

    @Test
    fun parsesLfTraceReadWriteAndStream() {
        val trace = AtResponseParser.parse(
            "+LFTRACE?",
            true,
            listOf("+LFTRACE=01020304,A1B2C3D4"),
        ).payload as AtPayload.LfTrace
        assertEquals("01020304", trace.block1.toHexForTest())
        assertEquals("A1B2C3D4", trace.block2.toHexForTest())

        val read = AtResponseParser.parse(
            "+LFREAD=",
            true,
            listOf("+LFREAD=T,1,7,01234567"),
        ).payload as AtPayload.LfBlock
        assertEquals('T', read.family)
        assertEquals(1, read.page)
        assertEquals(7, read.address)
        assertEquals("01234567", read.data.toHexForTest())

        val write = AtResponseParser.parse(
            "+LFWRITE=",
            true,
            listOf("+LFWRITE=OK_LOCK_UNVERIFIED,T,0,4,01020304,1"),
        ).payload as AtPayload.LfWrite
        assertEquals("OK_LOCK_UNVERIFIED", write.status)
        assertTrue(write.lock)

        val stream = AtResponseParser.parse(
            "+LFSTREAM?",
            true,
            listOf("+LFSTREAM=2,01020304,AABBCCDD"),
        ).payload as AtPayload.LfStream
        assertEquals(listOf("01020304", "AABBCCDD"), stream.words.map { it.toHexForTest() })
    }

    @Test
    fun rejectsMalformedPayloadAndCapturesCmeCode() {
        assertNull(
            AtResponseParser.parse(
                "+LFSTREAM?",
                true,
                listOf("+LFSTREAM=2,01020304"),
            ).payload,
        )
        val error = AtResponseParser.parse(
            "+LFREAD=",
            false,
            listOf("+CME ERROR: 2048"),
        )
        assertEquals(2048, error.errorCode)
    }

    private fun ByteArray.toHexForTest(): String =
        joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
