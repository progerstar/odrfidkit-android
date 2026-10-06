package ru.opendev.odrfidmne.model

internal const val ODRFID_VENDOR_ID = 0x0483
internal const val ODRFID_PRODUCT_ID = 0xA26A

internal sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Searching : ConnectionState
    data class PermissionRequired(val device: ReaderDevice) : ConnectionState
    data class PermissionDenied(val device: ReaderDevice) : ConnectionState
    data class Connecting(val device: ReaderDevice) : ConnectionState
    data class Connected(val device: ReaderDevice) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

internal data class ReaderDevice(
    val deviceId: Int,
    val displayName: String,
    val hasPermission: Boolean,
)

internal enum class CardFamily {
    MIFARE_CLASSIC,
    MIFARE_PLUS,
    ULTRALIGHT_NTAG,
    EM_MARINE,
    HID_PROX,
    LF_T55XX,
    LF_EM4X05,
    LF_EM4X50,
    UNSUPPORTED,
    UNKNOWN,
}

internal data class CardInfo(
    val uidHex: String,
    val sak: Int,
    val blockCount: Int,
    val blockSize: Int,
    val rawType: Int,
    val typeName: String,
    val family: CardFamily,
) {
    val isLowFrequency: Boolean
        get() = family == CardFamily.EM_MARINE || family == CardFamily.HID_PROX

    val supportsMemory: Boolean
        get() = family in setOf(
            CardFamily.MIFARE_CLASSIC,
            CardFamily.MIFARE_PLUS,
            CardFamily.ULTRALIGHT_NTAG,
        )
}

internal data class MemoryLayout(
    val blockCount: Int,
    val blockSize: Int,
    val labels: List<String>,
    val groupLabels: List<String?> = List(blockCount) { null },
)

internal data class MemoryBlock(
    val index: Int,
    val label: String,
    val groupLabel: String? = null,
    val dataHex: String? = null,
    val writable: Boolean = false,
    val risk: WriteRisk? = null,
    val error: String? = null,
) {
    val asciiPreview: String
        get() = dataHex?.hexToBytes()?.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) value.toChar().toString() else "."
        }.orEmpty()
}

internal data class LfClassification(
    val status: String,
    val airProtocol: String,
    val chip: String,
    val evidence: String,
    val chipInfoHex: String,
    val structured: Boolean,
) {
    val family: CardFamily
        get() = when {
            chip in setOf("T55XX", "T5577", "T5555") -> CardFamily.LF_T55XX
            chip in setOf("EM4205", "EM4305", "EM4369", "EM4469", "EM4X05") ->
                CardFamily.LF_EM4X05
            chip == "EM4X50" -> CardFamily.LF_EM4X50
            else -> CardFamily.UNKNOWN
        }
}

internal enum class RiskLevel { SAFE, WARNING, CRITICAL, BLOCKED }

internal data class WriteRisk(
    val level: RiskLevel,
    val title: String,
    val message: String,
    val confirmationPhrase: String = "",
)

internal data class WriteResult(
    val block: Int,
    val status: String,
    val oldHex: String,
    val newHex: String,
    val readBackHex: String?,
    val verified: Boolean,
)

internal sealed interface CloneRequest {
    val sourceUidHex: String

    data class Mifare(
        override val sourceUidHex: String,
        val magicTargetConfirmed: Boolean,
    ) : CloneRequest

    data class EmMarine(
        override val sourceUidHex: String,
        val coding: EmCoding,
        val speed: EmSpeed,
        val currentPassword: String? = null,
        val newPassword: String? = null,
    ) : CloneRequest
}

internal enum class EmCoding(val wireValue: Int, val title: String) {
    MANCHESTER(0, "Manchester"),
    BIPHASE(1, "BiPhase"),
}

internal enum class EmSpeed(val wireValue: Int, val title: String) {
    RF_64(0, "RF/64"),
    RF_32(1, "RF/32"),
}

internal enum class CloneStage {
    IDLE,
    WAITING_SOURCE_REMOVAL,
    WAITING_TARGET,
    WRITING,
    VERIFYING,
    COMPLETE,
    FAILED,
}

internal data class SessionKeys(
    val classicKeyA: String = "",
    val classicKeyB: String = "",
    val ultralightPassword: String = "",
    val plusAesKey: String = "",
    val plusKeyType: Char = 'A',
    val lfPassword: String = "",
)

internal enum class CompactPage { CARD, MEMORY }

internal data class ReaderUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val devices: List<ReaderDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val firmwareVersion: String? = null,
    val card: CardInfo? = null,
    val lfClassification: LfClassification? = null,
    val memoryLayout: MemoryLayout? = null,
    val memory: List<MemoryBlock> = emptyList(),
    val keys: SessionKeys = SessionKeys(),
    val busy: Boolean = false,
    val operationLabel: String? = null,
    val progressCurrent: Int = 0,
    val progressTotal: Int = 0,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    val compactPage: CompactPage = CompactPage.CARD,
    val cloneStage: CloneStage = CloneStage.IDLE,
    val cloneRequest: CloneRequest? = null,
)

internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

internal fun String.hexToBytes(): ByteArray {
    val normalized = trim().replace(" ", "").uppercase()
    require(normalized.length % 2 == 0 && normalized.all { it in "0123456789ABCDEF" }) {
        "Ожидалась HEX-строка чётной длины"
    }
    return ByteArray(normalized.length / 2) { index ->
        normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun normalizeHex(value: String, bytes: Int, label: String): String {
    val normalized = value.filterNot(Char::isWhitespace).uppercase()
    require(normalized.length == bytes * 2 && normalized.all { it in "0123456789ABCDEF" }) {
        "$label: требуется ровно ${bytes * 2} HEX-символов"
    }
    return normalized
}
