package ru.opendev.odrfidmne.data

import kotlinx.coroutines.test.TestScope
import ru.opendev.odrfidmne.model.CardTypes
import ru.opendev.odrfidmne.protocol.AtCommandClient
import ru.opendev.odrfidmne.protocol.FakeSerialTransport

internal data class SimulatedCard(
    val uidHex: String,
    val type: Int,
    val blocks: Int = if (type == CardTypes.EM_4100 || type == CardTypes.HID_PROX) 1 else 64,
    val blockSize: Int = if (type == CardTypes.EM_4100 || type == CardTypes.HID_PROX) 5 else 16,
    val sak: Int = 0x08,
)

internal class RepositoryHarness(scope: TestScope) {
    val transport = FakeSerialTransport()
    val client = AtCommandClient(transport, scope.backgroundScope)
    val repository = ReaderRepository(transport, client, scope.backgroundScope)

    var presentCard: SimulatedCard? = null
    val hfBlocks = mutableMapOf<Int, String>()
    val lfBlocks = mutableMapOf<Pair<Int, Int>, String>()
    var classificationChip = "T5577"
    var classificationEvidence = "T55XX_CONFIG"
    var classificationInfo = "00148040"
    var lfClassSupported = true
    var lfInfoType = "T5577"
    var lfWriteStatus = "OK"
    var persistHfWrite = true
    var persistLfWrite = true
    var cloneChangesUid = true

    var hfWriteCount = 0
    var lfWriteCount = 0
    var cloneWriteCount = 0

    init {
        repeat(256) { hfBlocks[it] = "00".repeat(16) }
        repeat(2) { page -> repeat(8) { block -> lfBlocks[page to block] = "00000000" } }
        transport.responder = ::respond
    }

    fun commandCount(prefix: String): Int = transport.writes.count { it.startsWith(prefix) }

    fun commands(prefix: String): List<String> = transport.writes.filter { it.startsWith(prefix) }

    private suspend fun respond(wire: String) {
        when {
            wire == "\r" -> Unit
            wire == "ATI\r" -> ok("Open Development RFID Reader 3.19m Aug 07 2026")
            wire == "AT+SCAN0\r" || wire == "AT+RF=1\r" -> ok()
            wire == "AT+i\r" || wire == "AT+n\r" -> {
                val card = presentCard
                if (card == null) error() else ok("+UID=${pollUid(card)}")
            }
            wire == "AT+S\r" -> {
                val card = presentCard
                if (card == null) {
                    error()
                } else {
                    ok(
                        "+UID=${statusUid(card)},BC=${card.blocks},BS=${card.blockSize},T=${card.type}",
                    )
                }
            }
            wire.startsWith("AT+R") || wire.startsWith("AT+M") -> {
                val prefixLength = if (wire.startsWith("AT+R")) 4 else 4
                val block = wire.substring(prefixLength).removeSuffix("\r").toInt()
                ok("+DATA $block:${hfBlocks.getValue(block)}")
            }
            wire.startsWith("AT+W") || wire.startsWith("AT+E") -> {
                hfWriteCount++
                val body = wire.substring(4).removeSuffix("\r")
                val block = body.substringBefore(':').toInt()
                val data = body.substringAfter(':').uppercase()
                if (persistHfWrite) hfBlocks[block] = data
                ok()
            }
            wire.startsWith("AT+LFCLASS=") -> {
                if (lfClassSupported) {
                    ok(
                        "+LFCLASS=OK,EM4100_COMPAT,$classificationChip," +
                            "$classificationEvidence,$classificationInfo",
                    )
                } else {
                    error()
                }
            }
            wire == "AT+LFINFO?\r" -> ok("+LFINFO=$lfInfoType,$classificationInfo")
            wire.startsWith("AT+LFREAD=") -> {
                val parts = wire.removePrefix("AT+LFREAD=").removeSuffix("\r").split(',')
                val family = parts[0]
                val page = if (family == "T") parts[1].toInt() else 0
                val block = if (family == "T") parts[2].toInt() else parts[1].toInt()
                ok("+LFREAD=$family,$page,$block,${lfBlocks.getValue(page to block)}")
            }
            wire.startsWith("AT+LFWRITE=") -> {
                lfWriteCount++
                val parts = wire.removePrefix("AT+LFWRITE=").removeSuffix("\r").split(',')
                val page = parts[1].toInt()
                val block = parts[2].toInt()
                val data = parts[3].uppercase()
                val lock = parts[4]
                if (lfWriteStatus in setOf("OK", "OK_LOCK_UNVERIFIED") && persistLfWrite) {
                    lfBlocks[page to block] = data
                }
                val result = "+LFWRITE=$lfWriteStatus,T,$page,$block,$data,$lock"
                if (lfWriteStatus in setOf("OK", "OK_LOCK_UNVERIFIED")) {
                    ok(result)
                } else {
                    transport.receive("$result\r\n+CME ERROR: 2048\r\nERROR\r\n")
                }
            }
            wire.startsWith("AT+~") -> {
                cloneWriteCount++
                if (cloneChangesUid) replaceUid(wire.removePrefix("AT+~").removeSuffix("\r"))
                ok()
            }
            wire.startsWith("AT+@") -> {
                cloneWriteCount++
                if (cloneChangesUid) replaceUid(wire.removePrefix("AT+@").removeSuffix("\r"))
                ok()
            }
            wire.startsWith("AT+x") -> {
                cloneWriteCount++
                if (cloneChangesUid) replaceUid(wire.removeSuffix("\r").substringAfterLast(','))
                ok()
            }
            wire.startsWith("AT+u") || wire.startsWith("AT+c") || wire.startsWith("AT+N") ||
                wire.startsWith("AT+KA") || wire.startsWith("AT+KB") || wire.startsWith("AT+KU") ||
                wire.startsWith("AT+KX") -> ok()
            else -> ok()
        }
    }

    private fun replaceUid(uid: String) {
        presentCard = presentCard?.copy(uidHex = uid.uppercase())
    }

    private fun pollUid(card: SimulatedCard): String = if (isLf(card)) {
        card.uidHex
    } else {
        card.uidHex + "%02X".format(card.sak)
    }

    private fun statusUid(card: SimulatedCard): String = if (isLf(card)) {
        card.uidHex
    } else {
        card.uidHex + "%02X".format(card.sak)
    }

    private fun isLf(card: SimulatedCard): Boolean =
        card.type == CardTypes.EM_4100 || card.type == CardTypes.HID_PROX

    private suspend fun ok(vararg payload: String) {
        transport.receive((payload.toList() + "OK").joinToString("\r\n", postfix = "\r\n"))
    }

    private suspend fun error() {
        transport.receive("ERROR\r\n")
    }
}
