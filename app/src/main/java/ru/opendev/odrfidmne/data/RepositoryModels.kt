package ru.opendev.odrfidmne.data

import ru.opendev.odrfidmne.model.CardInfo
import ru.opendev.odrfidmne.model.CloneRequest
import ru.opendev.odrfidmne.model.CloneStage
import ru.opendev.odrfidmne.model.LfClassification
import ru.opendev.odrfidmne.model.MemoryBlock
import ru.opendev.odrfidmne.model.MemoryLayout
import ru.opendev.odrfidmne.model.SessionKeys
import ru.opendev.odrfidmne.model.WriteRisk

internal data class RepositoryState(
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
    val cloneStage: CloneStage = CloneStage.IDLE,
    val cloneRequest: CloneRequest? = null,
)

internal data class PendingHfWrite(
    val cardUidHex: String,
    val cardType: Int,
    val block: Int,
    val oldHex: String,
    val newHex: String,
    val risk: WriteRisk,
)

internal data class LfWriteDraft(
    val page: Int,
    val block: Int,
    val dataHex: String,
    val lock: Boolean,
    val access: Char,
    val password: String?,
)

internal data class PendingLfWrite(
    val classification: LfClassification,
    val draft: LfWriteDraft,
    val oldHex: String,
    val risk: WriteRisk,
    val verificationPassword: String?,
)
