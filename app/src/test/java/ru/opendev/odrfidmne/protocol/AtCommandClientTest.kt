package ru.opendev.odrfidmne.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AtCommandClientTest {
    @Test
    fun writesAtCommandAndParsesFragmentedResponse() = runTest {
        val transport = FakeSerialTransport().apply { connect() }
        val client = AtCommandClient(transport, backgroundScope)
        runCurrent()

        val pending = async { client.send("+LFREAD=", "T,1,7") }
        runCurrent()
        assertEquals(listOf("AT+LFREAD=T,1,7\r"), transport.writes)

        transport.receive("+LFREAD=T,1,")
        transport.receive("7,01234567\r\nOK\r\n")
        val response = pending.await()
        val payload = response.payload as AtPayload.LfBlock
        assertEquals(7, payload.address)
    }

    @Test
    fun serializesConcurrentCommandsInOneQueue() = runTest {
        val transport = FakeSerialTransport().apply { connect() }
        val client = AtCommandClient(transport, backgroundScope)
        runCurrent()

        val first = async { client.send("+A") }
        val second = async { client.send("+B") }
        runCurrent()
        assertEquals(listOf("AT+A\r"), transport.writes)

        transport.receive("OK\r\n")
        first.await()
        runCurrent()
        assertEquals(listOf("AT+A\r", "AT+B\r"), transport.writes)
        transport.receive("OK\r\n")
        second.await()
    }

    @Test
    fun timeoutClosesTransportSoLateOkCannotReachNextCommand() = runTest {
        val transport = FakeSerialTransport().apply { connect() }
        val client = AtCommandClient(transport, backgroundScope)
        runCurrent()

        val pending = async { runCatching { client.send("+SLOW", timeoutMs = 50) } }
        runCurrent()
        advanceTimeBy(51)
        runCurrent()
        val error = pending.await().exceptionOrNull()

        assertTrue(error is AtDisconnectedException)
        assertFalse(transport.isOpen)
        assertTrue(transport.closeReasons.single().orEmpty().contains("Таймаут"))
        assertThrows(AtDisconnectedException::class.java) {
            kotlinx.coroutines.runBlocking { client.send("+NEXT") }
        }
    }

    @Test
    fun disconnectFailsPendingCommand() = runTest {
        val transport = FakeSerialTransport().apply { connect() }
        val client = AtCommandClient(transport, backgroundScope)
        runCurrent()

        val pending = async { runCatching { client.send("+WAIT") } }
        runCurrent()
        transport.disconnect()
        runCurrent()
        assertTrue(pending.await().exceptionOrNull() is AtDisconnectedException)
    }

    @Test
    fun framingViolationClosesConnection() = runTest {
        val transport = FakeSerialTransport().apply { connect() }
        val client = AtCommandClient(transport, backgroundScope)
        runCurrent()

        val pending = async { runCatching { client.send("+BAD") } }
        runCurrent()
        transport.receive("OK\n")
        runCurrent()

        assertTrue(pending.await().exceptionOrNull() is AtDisconnectedException)
        assertFalse(transport.isOpen)
        assertTrue(transport.closeReasons.any { it.orEmpty().contains("CRLF") })
    }

    @Test
    fun ignoresUnsolicitedScanLinesInsideAResponse() = runTest {
        val transport = FakeSerialTransport().apply { connect() }
        val client = AtCommandClient(transport, backgroundScope)
        runCurrent()
        val pending = async { client.send("+i") }
        runCurrent()
        transport.receive("SCAN: noise\r\n+UID=0102030408\r\nOK\r\n")
        val response = pending.await()
        assertEquals(1, response.lines.size)
        assertTrue(response.payload is AtPayload.Uid)
    }

    @Test
    fun lfCommunicationErrorNamesSafeExactCommand() = runTest {
        val transport = FakeSerialTransport().apply { connect() }
        val client = AtCommandClient(transport, backgroundScope)
        runCurrent()
        val pending = async {
            runCatching { client.send("+LFREAD=", "T,0,3") }
        }
        runCurrent()
        transport.receive("+CME ERROR: 2048\r\nERROR\r\n")
        val message = pending.await().exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("AT+LFREAD=T,0,3"))
        assertTrue(message.contains("+CME ERROR: 2048"))
    }

    @Test
    fun lfWriteFailureIsExplainedWithoutPasswordOrRetry() = runTest {
        val transport = FakeSerialTransport().apply { connect() }
        val client = AtCommandClient(transport, backgroundScope)
        runCurrent()
        val pending = async {
            runCatching {
                client.send(
                    "+LFWRITE=",
                    "T,1,1,DEADBEEF,0,P,11223344,CONFIRM",
                )
            }
        }
        runCurrent()
        transport.receive(
            "+LFWRITE=VERIFY_MISMATCH,T,1,1,DEADBEEF,0\r\n" +
                "+CME ERROR: 2048\r\nERROR\r\n",
        )
        val message = pending.await().exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("32-битное контрольное чтение не совпало"))
        assertTrue(message.contains("автоматически не повторялась"))
        assertFalse(message.contains("11223344"))
        assertEquals(1, transport.writes.size)
    }
}
