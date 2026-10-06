package ru.opendev.odrfidmne.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.opendev.odrfidmne.model.CardTypes
import ru.opendev.odrfidmne.model.CloneRequest
import ru.opendev.odrfidmne.model.CloneStage
import ru.opendev.odrfidmne.model.EmCoding
import ru.opendev.odrfidmne.model.EmSpeed

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderRepositoryCloneTest {
    @Test
    fun mifareCloneRequiresMagicConfirmationAndFourOrSevenByteUid() = runTest {
        val harness = connectedHarness(SimulatedCard("01020304", CardTypes.CLASSIC_1K))
        val notMagic = runCatching {
            harness.repository.beginClone(CloneRequest.Mifare("01020304", magicTargetConfirmed = false))
        }
        assertTrue(notMagic.isFailure)

        val wrongLength = runCatching {
            harness.repository.beginClone(CloneRequest.Mifare("010203", magicTargetConfirmed = true))
        }
        assertTrue(wrongLength.isFailure)
        assertEquals(CloneStage.IDLE, harness.repository.state.value.cloneStage)
    }

    @Test
    fun mifareCloneWaitsForSwapWritesOnceAndVerifiesUid() = runTest {
        val harness = connectedHarness(SimulatedCard("01020304", CardTypes.CLASSIC_1K))
        harness.repository.beginClone(CloneRequest.Mifare("01020304", magicTargetConfirmed = true))
        assertEquals(CloneStage.WAITING_SOURCE_REMOVAL, harness.repository.state.value.cloneStage)

        harness.presentCard = null
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(CloneStage.WAITING_TARGET, harness.repository.state.value.cloneStage)

        harness.presentCard = SimulatedCard("A1A2A3A4", CardTypes.CLASSIC_1K)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, harness.cloneWriteCount)
        assertEquals(listOf("AT+~01020304\r"), harness.commands("AT+~"))

        advanceTimeBy(700)
        runCurrent()
        assertEquals(CloneStage.COMPLETE, harness.repository.state.value.cloneStage)
        assertEquals("01020304", harness.repository.state.value.card?.uidHex)
    }

    @Test
    fun failedIndependentCloneVerificationNeverRetriesProgramming() = runTest {
        val harness = connectedHarness(SimulatedCard("01020304", CardTypes.CLASSIC_1K))
        harness.cloneChangesUid = false
        harness.repository.beginClone(CloneRequest.Mifare("01020304", true))
        harness.presentCard = null
        advanceTimeBy(1_000)
        runCurrent()
        harness.presentCard = SimulatedCard("A1A2A3A4", CardTypes.CLASSIC_1K)
        advanceTimeBy(1_000)
        runCurrent()

        advanceTimeBy(3_500)
        runCurrent()
        assertEquals(CloneStage.FAILED, harness.repository.state.value.cloneStage)
        assertEquals(1, harness.cloneWriteCount)
        assertTrue(harness.repository.state.value.errorMessage.orEmpty().contains("один раз"))
    }

    @Test
    fun sevenByteMifareUidIsAccepted() = runTest {
        val harness = connectedHarness(
            SimulatedCard("01020304050607", CardTypes.CLASSIC_1K),
        )
        val result = runCatching {
            harness.repository.beginClone(CloneRequest.Mifare("01020304050607", true))
        }
        assertTrue(result.isSuccess)
    }

    @Test
    fun emCloneUsesPasswordCodingAndSingleProgrammingCommandInOrder() = runTest {
        val harness = connectedHarness(SimulatedCard("0102030405", CardTypes.EM_4100))
        harness.repository.beginClone(
            CloneRequest.EmMarine(
                sourceUidHex = "0102030405",
                coding = EmCoding.BIPHASE,
                speed = EmSpeed.RF_32,
                currentPassword = "11223344",
                newPassword = "A1B2C3D4",
            ),
        )

        harness.presentCard = null
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(CloneStage.WAITING_TARGET, harness.repository.state.value.cloneStage)

        harness.presentCard = SimulatedCard("A1A2A3A4A5", CardTypes.EM_4100)
        advanceTimeBy(1_000)
        runCurrent()
        val cloneCommands = harness.transport.writes.filter {
            it.startsWith("AT+LFCLASS=") || it.startsWith("AT+u") ||
                it.startsWith("AT+c") || it.startsWith("AT+x") || it.startsWith("AT+@")
        }.takeLast(4)
        assertEquals(
            listOf(
                "AT+LFCLASS=FAST\r",
                "AT+u11223344\r",
                "AT+c1,1\r",
                "AT+xA1B2C3D4,0102030405\r",
            ),
            cloneCommands,
        )
        assertEquals(1, harness.cloneWriteCount)
        assertFalse(harness.transport.writes.any { it.startsWith("AT+@") })

        advanceTimeBy(700)
        runCurrent()
        assertEquals(CloneStage.COMPLETE, harness.repository.state.value.cloneStage)
    }

    @Test
    fun emCloneWithoutNewPasswordUsesAtSignCommand() = runTest {
        val harness = connectedHarness(SimulatedCard("0102030405", CardTypes.EM_4100))
        harness.repository.beginClone(
            CloneRequest.EmMarine("0102030405", EmCoding.MANCHESTER, EmSpeed.RF_64),
        )
        harness.presentCard = null
        advanceTimeBy(3_000)
        runCurrent()
        harness.presentCard = SimulatedCard("A1A2A3A4A5", CardTypes.EM_4100)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("AT+@0102030405\r"), harness.commands("AT+@"))
        assertEquals(1, harness.cloneWriteCount)
    }

    @Test
    fun hidProxCannotBeClonedAsEmMarine() = runTest {
        val harness = connectedHarness(SimulatedCard("010203040506", CardTypes.HID_PROX, blockSize = 6))
        val result = runCatching {
            harness.repository.beginClone(
                CloneRequest.EmMarine("0102030405", EmCoding.MANCHESTER, EmSpeed.RF_64),
            )
        }
        assertTrue(result.isFailure)
        assertEquals(0, harness.cloneWriteCount)
    }

    @Test
    fun targetMustBeStructurallyConfirmedT55xxBeforeEmWrite() = runTest {
        val harness = connectedHarness(SimulatedCard("0102030405", CardTypes.EM_4100))
        harness.classificationChip = "EM4305"
        harness.classificationEvidence = "EM4X05_WORD0"
        harness.repository.beginClone(
            CloneRequest.EmMarine("0102030405", EmCoding.MANCHESTER, EmSpeed.RF_64),
        )
        harness.presentCard = null
        advanceTimeBy(3_000)
        runCurrent()
        harness.presentCard = SimulatedCard("A1A2A3A4A5", CardTypes.EM_4100)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(CloneStage.FAILED, harness.repository.state.value.cloneStage)
        assertEquals(0, harness.cloneWriteCount)
    }

    private suspend fun TestScope.connectedHarness(card: SimulatedCard): RepositoryHarness {
        val harness = RepositoryHarness(this)
        harness.presentCard = card
        harness.transport.connect()
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        check(harness.repository.state.value.card != null)
        return harness
    }
}
