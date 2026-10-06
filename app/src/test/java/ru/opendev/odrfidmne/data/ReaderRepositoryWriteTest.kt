package ru.opendev.odrfidmne.data

import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.opendev.odrfidmne.model.CardFamily
import ru.opendev.odrfidmne.model.CardTypes
import ru.opendev.odrfidmne.model.RiskLevel

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderRepositoryWriteTest {
    @Test
    fun handshakeFailureWithoutMessageReportsExceptionType() = runTest {
        val harness = RepositoryHarness(this)
        harness.transport.flushFailure = UnsupportedOperationException()
        runCurrent()

        harness.transport.connect()
        runCurrent()

        assertFalse(harness.transport.isOpen)
        assertEquals(
            "Ошибка handshake: UnsupportedOperationException",
            harness.transport.closeReasons.single(),
        )
        assertTrue(harness.client.log.text.value.contains("UnsupportedOperationException"))
        assertTrue(harness.transport.writes.isEmpty())
    }

    @Test
    fun handshakeAndPollingDiscoverCard() = runTest {
        val harness = connectedClassicHarness()
        assertEquals("3.19m", harness.repository.state.value.firmwareVersion)
        assertEquals("01020304", harness.repository.state.value.card?.uidHex)
        assertEquals(CardFamily.MIFARE_CLASSIC, harness.repository.state.value.card?.family)
        assertEquals(
            listOf("\r", "ATI\r", "AT+SCAN0\r", "AT+RF=1\r", "AT+i\r", "AT+S\r"),
            harness.transport.writes.take(6),
        )
    }

    @Test
    fun hfWriteUsesPreReadOneWriteAndIndependentReadBack() = runTest {
        val harness = connectedClassicHarness()
        harness.hfBlocks[4] = "00112233445566778899AABBCCDDEEFF"

        val pending = execute {
            harness.repository.prepareHfWrite(4, "FFEEDDCCBBAA99887766554433221100")
        }
        val result = execute { harness.repository.commitHfWrite(pending, confirmed = true) }

        assertTrue(result.verified)
        assertEquals("00112233445566778899AABBCCDDEEFF", result.oldHex)
        assertEquals("FFEEDDCCBBAA99887766554433221100", result.readBackHex)
        assertEquals(1, harness.hfWriteCount)
        assertEquals(3, harness.commandCount("AT+R4\r"))
    }

    @Test
    fun hfWriteIsCancelledIfCardChangesAfterPreRead() = runTest {
        val harness = connectedClassicHarness()
        harness.hfBlocks[4] = "00".repeat(16)
        val pending = execute { harness.repository.prepareHfWrite(4, "11".repeat(16)) }

        harness.presentCard = SimulatedCard("A1A2A3A4", CardTypes.CLASSIC_1K)
        val failure = executeResult { harness.repository.commitHfWrite(pending, confirmed = true) }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("заменена"))
        assertEquals(0, harness.hfWriteCount)
    }

    @Test
    fun hfReadBackMismatchNeverRetriesWrite() = runTest {
        val harness = connectedClassicHarness()
        harness.persistHfWrite = false
        val pending = execute { harness.repository.prepareHfWrite(4, "11".repeat(16)) }
        val failure = executeResult { harness.repository.commitHfWrite(pending, confirmed = true) }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("read-back"))
        assertEquals(1, harness.hfWriteCount)
    }

    @Test
    fun serviceHfRegionsCannotEvenBePrepared() = runTest {
        val harness = connectedClassicHarness()
        val blockZero = executeResult { harness.repository.prepareHfWrite(0, "00".repeat(16)) }
        val trailer = executeResult { harness.repository.prepareHfWrite(3, "00".repeat(16)) }
        assertTrue(blockZero.isFailure)
        assertTrue(trailer.isFailure)
        assertEquals(0, harness.hfWriteCount)
    }

    @Test
    fun lfWritePerformsExactlyOneWriteAndSeparateExactReadBack() = runTest {
        val harness = connectedLfHarness()
        harness.lfBlocks[1 to 1] = "01020304"
        val pending = execute {
            harness.repository.prepareLfWrite(
                LfWriteDraft(1, 1, "DEADBEEF", lock = false, access = 'N', password = null),
            )
        }
        val result = execute {
            harness.repository.commitLfWrite(pending, confirmed = true, typedPhrase = "WRITE P1/B1")
        }

        assertTrue(result.verified)
        assertEquals("DEADBEEF", result.readBackHex)
        assertEquals(1, harness.lfWriteCount)
        assertEquals(3, harness.commandCount("AT+LFREAD=T,1,1"))
        assertEquals(
            1,
            harness.commandCount("AT+LFWRITE=T,1,1,DEADBEEF,0,N,NONE,CONFIRM\r"),
        )
    }

    @Test
    fun firmwareLfFailureIsNeverRetriedAndSkipsReadBack() = runTest {
        val harness = connectedLfHarness()
        harness.lfWriteStatus = "VERIFY_MISMATCH"
        val pending = execute {
            harness.repository.prepareLfWrite(
                LfWriteDraft(1, 2, "01020304", false, 'N', null),
            )
        }
        val readsBeforeCommit = harness.commandCount("AT+LFREAD=T,1,2")
        val failure = executeResult {
            harness.repository.commitLfWrite(pending, true, "WRITE P1/B2")
        }

        assertTrue(failure.isFailure)
        assertEquals(1, harness.lfWriteCount)
        assertEquals(readsBeforeCommit + 1, harness.commandCount("AT+LFREAD=T,1,2"))
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("автоматически не повторялась"))
    }

    @Test
    fun lfIndependentReadBackMismatchNeverRetries() = runTest {
        val harness = connectedLfHarness()
        harness.persistLfWrite = false
        val pending = execute {
            harness.repository.prepareLfWrite(LfWriteDraft(0, 4, "AABBCCDD", false, 'N', null))
        }
        val failure = executeResult { harness.repository.commitLfWrite(pending, true, "") }
        assertTrue(failure.isFailure)
        assertEquals(1, harness.lfWriteCount)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("read-back"))
    }

    @Test
    fun ambiguousP1B0AndWrongCriticalPhraseAreRejectedBeforeWrite() = runTest {
        val harness = connectedLfHarness()
        val alias = executeResult {
            harness.repository.prepareLfWrite(LfWriteDraft(1, 0, "00000000", false, 'N', null))
        }
        assertTrue(alias.isFailure)
        assertTrue(alias.exceptionOrNull()?.message.orEmpty().contains("Page 1 Block 0"))

        val pending = execute {
            harness.repository.prepareLfWrite(LfWriteDraft(1, 1, "AABBCCDD", false, 'N', null))
        }
        val wrongPhrase = executeResult { harness.repository.commitLfWrite(pending, true, "write p1/b1") }
        assertTrue(wrongPhrase.isFailure)
        assertEquals(0, harness.lfWriteCount)
    }

    @Test
    fun enablingPasswordModeRequiresFuturePasswordAndUsesItForReadBack() = runTest {
        val harness = connectedLfHarness()
        val absent = executeResult {
            harness.repository.prepareLfWrite(LfWriteDraft(0, 0, "00000010", false, 'N', null))
        }
        assertTrue(absent.isFailure)
        assertTrue(absent.exceptionOrNull()?.message.orEmpty().contains("Будущий пароль"))

        val pending = execute {
            harness.repository.prepareLfWrite(
                LfWriteDraft(0, 0, "00000010", false, 'N', "11223344"),
            )
        }
        assertEquals("11223344", pending.verificationPassword)
        execute { harness.repository.commitLfWrite(pending, true, "WRITE P0/B0") }
        assertEquals(
            1,
            harness.commandCount("AT+LFREAD=T,0,0,P,11223344\r"),
        )
    }

    @Test
    fun protectedPasswordBlockVerifiesWithNewPasswordValue() = runTest {
        val harness = connectedLfHarness()
        val pending = execute {
            harness.repository.prepareLfWrite(
                LfWriteDraft(0, 7, "A1B2C3D4", false, 'P', "11223344"),
            )
        }
        assertEquals("A1B2C3D4", pending.verificationPassword)
        execute { harness.repository.commitLfWrite(pending, true, "WRITE P0/B7") }
        assertEquals(1, harness.commandCount("AT+LFREAD=T,0,7,P,A1B2C3D4\r"))
    }

    @Test
    fun lockedWriteUsesStrongerTokensAndTypedLockPhrase() = runTest {
        val harness = connectedLfHarness()
        val pending = execute {
            harness.repository.prepareLfWrite(
                LfWriteDraft(0, 4, "01020304", true, 'P', "11223344"),
            )
        }
        assertEquals("LOCK P0/B4", pending.risk.confirmationPhrase)
        harness.lfWriteStatus = "OK_LOCK_UNVERIFIED"
        val result = execute { harness.repository.commitLfWrite(pending, true, "LOCK P0/B4") }
        assertEquals("OK_LOCK_UNVERIFIED", result.status)
        assertEquals(
            1,
            harness.commandCount(
                "AT+LFWRITE=T,0,4,01020304,1,P,11223344,CONFIRM_LOCK\r",
            ),
        )
    }

    @Test
    fun lfInfoFallbackAndEmFamiliesStayReadOnly() = runTest {
        val fallback = connectedLfHarness(lfClassSupported = false)
        val fallbackClass = fallback.repository.state.value.lfClassification!!
        assertFalse(fallbackClass.structured)
        assertFalse(fallback.repository.state.value.memory.any { it.writable })
        val rejected = executeResult {
            fallback.repository.prepareLfWrite(LfWriteDraft(0, 4, "01020304", false, 'N', null))
        }
        assertTrue(rejected.isFailure)

        val em = connectedLfHarness(chip = "EM4305", evidence = "EM4X05_WORD0")
        assertEquals(CardFamily.LF_EM4X05, em.repository.state.value.lfClassification?.family)
        assertFalse(em.repository.state.value.memory.any { it.writable })
        val emRejected = executeResult {
            em.repository.prepareLfWrite(LfWriteDraft(0, 4, "01020304", false, 'N', null))
        }
        assertTrue(emRejected.isFailure)
    }

    private suspend fun TestScope.connectedClassicHarness(): RepositoryHarness {
        val harness = RepositoryHarness(this)
        harness.presentCard = SimulatedCard("01020304", CardTypes.CLASSIC_1K)
        harness.transport.connect()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        check(harness.repository.state.value.card != null)
        return harness
    }

    private suspend fun TestScope.connectedLfHarness(
        lfClassSupported: Boolean = true,
        chip: String = "T5577",
        evidence: String = "T55XX_CONFIG",
    ): RepositoryHarness {
        val harness = RepositoryHarness(this)
        harness.presentCard = SimulatedCard("0102030405", CardTypes.EM_4100)
        harness.lfClassSupported = lfClassSupported
        harness.classificationChip = chip
        harness.classificationEvidence = evidence
        harness.transport.connect()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        execute { harness.repository.detectLfMemory("FAST") }
        return harness
    }

    private suspend fun <T> TestScope.execute(block: suspend () -> T): T {
        val deferred = async { block() }
        runCurrent()
        return deferred.await()
    }

    private suspend fun <T> TestScope.executeResult(block: suspend () -> T): Result<T> =
        execute { runCatching { block() } }
}
