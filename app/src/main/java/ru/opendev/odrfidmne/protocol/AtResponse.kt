package ru.opendev.odrfidmne.protocol

import ru.opendev.odrfidmne.model.CardTypes
import ru.opendev.odrfidmne.model.hexToBytes

internal const val DEFAULT_TIMEOUT_MS = 900L
internal const val WRITE_TIMEOUT_MS = 3_000L
internal const val LF_READ_TIMEOUT_MS = 3_000L
internal const val LF_WRITE_TIMEOUT_MS = 6_000L

internal data class AtResponse(
    val command: String,
    val success: Boolean,
    val lines: List<String>,
    val payload: AtPayload? = null,
    val errorCode: Int? = null,
)

internal sealed interface AtPayload {
    data class Firmware(val version: String) : AtPayload
    data class Uid(val bytes: ByteArray) : AtPayload
    data class CardStatus(
        val uid: ByteArray,
        val sak: Int,
        val blocks: Int,
        val blockSize: Int,
        val type: Int,
    ) : AtPayload
    data class BlockData(val block: Int, val data: ByteArray) : AtPayload
    data class LfInfo(
        val type: String,
        val chipInfoHex: String,
        val chipInfoKind: String,
    ) : AtPayload
    data class LfClass(
        val status: String,
        val air: String,
        val chip: String,
        val evidence: String,
        val chipInfoHex: String,
    ) : AtPayload
    data class LfTrace(val block1: ByteArray, val block2: ByteArray) : AtPayload
    data class LfBlock(
        val family: Char,
        val page: Int,
        val address: Int,
        val data: ByteArray,
    ) : AtPayload
    data class LfWrite(
        val status: String,
        val page: Int,
        val address: Int,
        val dataHex: String,
        val lock: Boolean,
    ) : AtPayload
    data class LfStream(val words: List<ByteArray>) : AtPayload
}

internal object AtResponseParser {
    private val cardStatus = Regex("^\\+UID=([A-F0-9]+),BC=(\\d+),BS=(\\d+),T=(\\d+)$", RegexOption.IGNORE_CASE)
    private val blockData = Regex("^\\+DATA\\s+(\\d+):([A-F0-9]+)$", RegexOption.IGNORE_CASE)
    private val lfInfo = Regex(
        "^\\+LFINFO=(T5577|T5555|EM4205|EM4305|EM4369|EM4469|EM4X05|EM4X50),([A-F0-9]{8})$",
        RegexOption.IGNORE_CASE,
    )
    private val lfClass = Regex(
        "^\\+LFCLASS=(OK|AIR_ONLY|NO_SIGNAL|BUSY),(EM4100_COMPAT|HID_PROX|UNKNOWN|NONE),(T55XX|T5577|T5555|EM4205|EM4305|EM4369|EM4469|EM4X05|EM4X50|NONE),(T55XX_CONFIG|T5577_TRACE|T5555_TRACE|EM4X05_WORD0|EM4X50_STREAM|NONE),([A-F0-9]{8})$",
        RegexOption.IGNORE_CASE,
    )
    private val lfTrace = Regex("^\\+LFTRACE=([A-F0-9]{8}),([A-F0-9]{8})$", RegexOption.IGNORE_CASE)
    private val lfBlock = Regex("^\\+LFREAD=([TE]),([01]),(\\d{1,2}),([A-F0-9]{8})$", RegexOption.IGNORE_CASE)
    private val lfWrite = Regex(
        "^\\+LFWRITE=(OK|OK_LOCK_UNVERIFIED|NO_UID|TRANSPORT|VERIFY_NO_TAG|VERIFY_PROTOCOL|VERIFY_MISMATCH|INVALID_ARGUMENT|ACCESS_MISMATCH|PASSWORD_REQUIRED|UNKNOWN),T,([01]),([0-7]),([A-F0-9]{8}),([01])$",
        RegexOption.IGNORE_CASE,
    )
    private val lfStream = Regex("^\\+LFSTREAM=(\\d+)((?:,[A-F0-9]{8})*)$", RegexOption.IGNORE_CASE)

    fun parse(command: String, success: Boolean, lines: List<String>): AtResponse {
        val payload = when (command) {
            "I" -> parseFirmware(lines)
            "+i", "+n" -> parseUid(lines)
            "+S" -> parseCardStatus(lines)
            "+R", "+M" -> firstMatch(lines, blockData)?.let {
                AtPayload.BlockData(it.groupValues[1].toInt(), it.groupValues[2].hexToBytes())
            }
            "+LFINFO?" -> firstMatch(lines, lfInfo)?.let {
                val type = it.groupValues[1].uppercase()
                val info = it.groupValues[2].uppercase()
                val kind = when {
                    type == "T5555" || type == "T5577" && (info.startsWith("E015") || info.startsWith("E039")) -> "trace"
                    type == "T5577" -> "config"
                    else -> "word"
                }
                AtPayload.LfInfo(type, info, kind)
            }
            "+LFCLASS=", "+LFCLASS?" -> firstMatch(lines, lfClass)?.let {
                AtPayload.LfClass(
                    it.groupValues[1].uppercase(),
                    it.groupValues[2].uppercase(),
                    it.groupValues[3].uppercase(),
                    it.groupValues[4].uppercase(),
                    it.groupValues[5].uppercase(),
                )
            }
            "+LFTRACE?" -> firstMatch(lines, lfTrace)?.let {
                AtPayload.LfTrace(it.groupValues[1].hexToBytes(), it.groupValues[2].hexToBytes())
            }
            "+LFREAD=" -> firstMatch(lines, lfBlock)?.let {
                AtPayload.LfBlock(
                    it.groupValues[1].uppercase()[0],
                    it.groupValues[2].toInt(),
                    it.groupValues[3].toInt(),
                    it.groupValues[4].hexToBytes(),
                )
            }
            "+LFWRITE=" -> firstMatch(lines, lfWrite)?.let {
                AtPayload.LfWrite(
                    it.groupValues[1].uppercase(),
                    it.groupValues[2].toInt(),
                    it.groupValues[3].toInt(),
                    it.groupValues[4].uppercase(),
                    it.groupValues[5] == "1",
                )
            }
            "+LFSTREAM?" -> firstMatch(lines, lfStream)?.let {
                val expected = it.groupValues[1].toInt()
                val words = it.groupValues[2].removePrefix(",").takeIf(String::isNotEmpty)
                    ?.split(',')?.map(String::hexToBytes).orEmpty()
                if (words.size == expected) AtPayload.LfStream(words) else null
            }
            else -> null
        }
        val errorCode = lines.firstNotNullOfOrNull { line ->
            Regex("^\\+CME ERROR:\\s*(\\d+)$").matchEntire(line)?.groupValues?.get(1)?.toInt()
        }
        return AtResponse(command, success, lines, payload, errorCode)
    }

    private fun parseFirmware(lines: List<String>): AtPayload.Firmware? {
        val match = lines.firstNotNullOfOrNull { line ->
            Regex("\\b(\\d+\\.\\d+(?:[FMNE])?)(?=\\s|$)", RegexOption.IGNORE_CASE).find(line)
        }
        return match?.groupValues?.get(1)?.let { AtPayload.Firmware(it) }
    }

    private fun parseUid(lines: List<String>): AtPayload.Uid? {
        val line = lines.singleOrNull() ?: return null
        if (!line.startsWith("+UID=", ignoreCase = true)) return null
        return runCatching { AtPayload.Uid(line.substring(5).hexToBytes()) }.getOrNull()
    }

    private fun parseCardStatus(lines: List<String>): AtPayload.CardStatus? {
        val match = firstMatch(lines, cardStatus) ?: return null
        val uidWithSak = match.groupValues[1].hexToBytes()
        val type = match.groupValues[4].toInt()
        val hasTrailingSak = uidWithSak.isNotEmpty() &&
            (type != CardTypes.EM_4100 || uidWithSak.size > 5 && uidWithSak.last() == 0xFF.toByte())
        return AtPayload.CardStatus(
            uid = if (hasTrailingSak) uidWithSak.dropLast(1).toByteArray() else uidWithSak,
            sak = if (hasTrailingSak) uidWithSak.last().toInt() and 0xFF else 0xFF,
            blocks = match.groupValues[2].toInt(),
            blockSize = match.groupValues[3].toInt(),
            type = type,
        )
    }

    private fun firstMatch(lines: List<String>, regex: Regex): MatchResult? =
        lines.firstNotNullOfOrNull(regex::matchEntire)
}

