package ru.opendev.odrfidmne.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ru.opendev.odrfidmne.model.CardFamily
import ru.opendev.odrfidmne.model.CardInfo
import ru.opendev.odrfidmne.model.CardTypes
import ru.opendev.odrfidmne.model.CloneRequest
import ru.opendev.odrfidmne.model.CloneStage
import ru.opendev.odrfidmne.model.ConnectionState
import ru.opendev.odrfidmne.model.LfClassification
import ru.opendev.odrfidmne.model.MemoryBlock
import ru.opendev.odrfidmne.model.MemoryPolicies
import ru.opendev.odrfidmne.model.RiskLevel
import ru.opendev.odrfidmne.model.SessionKeys
import ru.opendev.odrfidmne.model.WriteResult
import ru.opendev.odrfidmne.model.classicBlockToSector
import ru.opendev.odrfidmne.model.hexToBytes
import ru.opendev.odrfidmne.model.normalizeHex
import ru.opendev.odrfidmne.model.toHex
import ru.opendev.odrfidmne.protocol.AtCommandClient
import ru.opendev.odrfidmne.protocol.AtCommandException
import ru.opendev.odrfidmne.protocol.AtDisconnectedException
import ru.opendev.odrfidmne.protocol.AtPayload
import ru.opendev.odrfidmne.protocol.AtResponse
import ru.opendev.odrfidmne.protocol.DEFAULT_TIMEOUT_MS
import ru.opendev.odrfidmne.protocol.LF_READ_TIMEOUT_MS
import ru.opendev.odrfidmne.protocol.LF_WRITE_TIMEOUT_MS
import ru.opendev.odrfidmne.protocol.SerialByteTransport
import ru.opendev.odrfidmne.protocol.WRITE_TIMEOUT_MS

internal class ReaderRepository(
    private val transport: SerialByteTransport,
    private val client: AtCommandClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RepositoryState())
    val state: StateFlow<RepositoryState> = _state.asStateFlow()

    private val operationMutex = Mutex()
    private var sessionJob: Job? = null
    private var pollMisses = 0

    init {
        scope.launch {
            transport.connection.collectLatest { connection ->
                sessionJob?.cancel()
                sessionJob = null
                if (connection is ConnectionState.Connected) {
                    clearSessionState()
                    sessionJob = scope.launch { runConnectedSession() }
                } else {
                    clearSessionState()
                }
            }
        }
    }

    suspend fun disconnect() {
        if (transport.isOpen) {
            withTimeoutOrNull(300) {
                runCatching { transport.write("AT+Q\r".toByteArray(Charsets.US_ASCII)) }
            }
        }
        transport.close(null)
    }

    fun clearMessages() = _state.update { it.copy(errorMessage = null, infoMessage = null) }

    suspend fun setClassicKey(keyType: Char, value: String) {
        require(keyType == 'A' || keyType == 'B') { "Тип ключа должен быть A или B" }
        val key = normalizeHex(value, 6, "Ключ $keyType")
        operation("Передача Key $keyType") {
            client.send(if (keyType == 'A') "+KA" else "+KB", key)
            _state.update {
                val keys = if (keyType == 'A') it.keys.copy(classicKeyA = key)
                else it.keys.copy(classicKeyB = key)
                it.copy(keys = keys, infoMessage = "Key $keyType принят считывателем")
            }
        }
    }

    suspend fun setUltralightPassword(value: String) {
        val password = normalizeHex(value, 4, "Пароль Ultralight")
        operation("Передача пароля") {
            client.send("+KU", password)
            _state.update {
                it.copy(
                    keys = it.keys.copy(ultralightPassword = password),
                    infoMessage = "Сеансовый пароль принят",
                )
            }
        }
    }

    suspend fun setPlusKey(value: String, keyType: Char) {
        require(keyType == 'A' || keyType == 'B') { "Тип AES-ключа должен быть A или B" }
        val key = normalizeHex(value, 16, "AES-ключ MIFARE Plus")
        operation("Передача AES-ключа") {
            client.send("+KX", key)
            _state.update {
                it.copy(
                    keys = it.keys.copy(plusAesKey = key, plusKeyType = keyType),
                    infoMessage = "Сеансовый AES-ключ принят",
                )
            }
        }
    }

    fun setLfSessionPassword(value: String) {
        val password = if (value.isBlank()) "" else normalizeHex(value, 4, "Пароль T55xx")
        _state.update { it.copy(keys = it.keys.copy(lfPassword = password)) }
    }

    suspend fun readAllMemory() {
        val classification = _state.value.lfClassification
        if (classification != null) {
            readAllLfMemory()
            return
        }
        val card = requireCard()
        require(card.supportsMemory) { "Память этого семейства не поддерживается" }
        operation("Чтение памяти", card.blockCount) {
            val layout = MemoryPolicies.layout(card)
            setLayout(layout, card)
            for (block in 0 until card.blockCount) {
                if (!transport.isOpen) throw AtDisconnectedException("Соединение закрыто")
                try {
                    val data = readHfBlockWire(card, block)
                    updateMemoryBlock(block, dataHex = data.toHex(), error = null)
                } catch (error: AtCommandException) {
                    updateMemoryBlock(block, dataHex = null, error = error.message)
                }
                setProgress(block + 1, card.blockCount)
            }
            _state.update { it.copy(infoMessage = "Чтение памяти завершено") }
        }
    }

    suspend fun prepareHfWrite(block: Int, newValue: String): PendingHfWrite {
        val card = requireCard()
        val risk = MemoryPolicies.hfRisk(card, block)
        require(risk.level != RiskLevel.BLOCKED) { risk.message }
        val newHex = normalizeHex(newValue, card.blockSize, "Данные блока")
        return operation("Проверка блока") {
            val old = readHfBlockWire(card, block).toHex()
            val checked = readCardStatusWire()
            requireSameCard(card, checked)
            PendingHfWrite(card.uidHex, card.rawType, block, old, newHex, risk)
        }
    }

    suspend fun commitHfWrite(pending: PendingHfWrite, confirmed: Boolean): WriteResult {
        require(confirmed) { "Запись должна быть подтверждена пользователем" }
        return operation("Запись блока") {
            val current = readCardStatusWire()
            require(current.uidHex == pending.cardUidHex && current.rawType == pending.cardType) {
                "Карта была заменена после подтверждения"
            }
            val risk = MemoryPolicies.hfRisk(current, pending.block)
            require(risk.level != RiskLevel.BLOCKED) { risk.message }
            val latest = readHfBlockWire(current, pending.block).toHex()
            require(latest == pending.oldHex) {
                "Блок изменился после предварительного чтения; запись отменена"
            }

            authenticatePlusBlockIfNeeded(current, pending.block)
            val command = if (CardTypes.usesAesBlockCommands(current.rawType)) "+E" else "+W"
            // Deliberately exactly one write command. Never retry this call.
            client.send(command, "${pending.block}:${pending.newHex}", WRITE_TIMEOUT_MS)

            val afterWriteCard = readCardStatusWire()
            requireSameCard(current, afterWriteCard)
            val readBack = readHfBlockWire(current, pending.block).toHex()
            val verified = readBack == pending.newHex
            updateMemoryBlock(pending.block, dataHex = readBack, error = null)
            if (!verified) {
                throw IllegalStateException(
                    "Независимый read-back не совпал: ожидалось ${pending.newHex}, прочитано $readBack",
                )
            }
            WriteResult(pending.block, "OK", pending.oldHex, pending.newHex, readBack, true)
                .also { _state.update { state -> state.copy(infoMessage = "Блок проверен после записи") } }
        }
    }

    suspend fun detectLfMemory(mode: String = "FAST"): LfClassification {
        val normalizedMode = mode.uppercase()
        require(normalizedMode == "FAST" || normalizedMode == "FULL") {
            "Режим LFCLASS должен быть FAST или FULL"
        }
        return operation("Классификация LF") {
            val classification = classifyLfWire(normalizedMode)
            applyLfClassification(classification)
            classification
        }
    }

    suspend fun readAllLfMemory() {
        operation("Чтение LF-памяти") {
            val classification = _state.value.lfClassification ?: classifyLfWire("FAST").also {
                applyLfClassification(it)
            }
            val layout = MemoryPolicies.lfLayout(classification)
            setLfLayout(layout, classification)
            when (classification.family) {
                CardFamily.LF_T55XX -> readT55xxMemory(classification)
                CardFamily.LF_EM4X05 -> readEm4x05Memory(layout)
                CardFamily.LF_EM4X50 -> readEm4x50Memory()
                else -> throw IllegalStateException("Поддерживаемое R/W-семейство LF-метки не подтверждено")
            }
            _state.update { it.copy(infoMessage = "Чтение LF-памяти завершено") }
        }
    }

    suspend fun prepareLfWrite(draft: LfWriteDraft): PendingLfWrite {
        val normalized = validateLfDraft(draft)
        val classification = _state.value.lfClassification
            ?: throw IllegalStateException("Сначала выполните LFCLASS")
        require(classification.structured && classification.family == CardFamily.LF_T55XX) {
            "Запись доступна только после структурированного подтверждения T55xx через LFCLASS"
        }
        val risk = MemoryPolicies.lfRisk(normalized.page, normalized.block, normalized.lock)
        require(risk.level != RiskLevel.BLOCKED) { risk.message }
        return operation("Проверка LF-блока") {
            val old = readLfBlockWire(
                family = 'T',
                page = normalized.page,
                address = normalized.block,
                password = normalized.password.takeIf { normalized.access == 'P' },
            ).data.toHex()
            PendingLfWrite(
                classification,
                normalized,
                old,
                risk,
                verificationPassword(normalized),
            )
        }
    }

    suspend fun commitLfWrite(
        pending: PendingLfWrite,
        confirmed: Boolean,
        typedPhrase: String,
    ): WriteResult {
        require(confirmed) { "Подтвердите единственную попытку записи" }
        if (pending.risk.confirmationPhrase.isNotEmpty()) {
            require(typedPhrase.trim() == pending.risk.confirmationPhrase) {
                "Введите фразу ${pending.risk.confirmationPhrase} без изменений"
            }
        }
        return operation("Запись LF-блока") {
            val actualClass = readStructuredLfClass("FAST")
            require(
                actualClass.family == CardFamily.LF_T55XX &&
                    actualClass.chip == pending.classification.chip,
            ) { "Перед записью обнаружена другая LF-метка" }

            val draft = pending.draft
            val currentPassword = draft.password.takeIf { draft.access == 'P' }
            val latest = readLfBlockWire('T', draft.page, draft.block, currentPassword).data.toHex()
            require(latest == pending.oldHex) {
                "LF-блок изменился после подтверждения; запись отменена"
            }

            val confirmation = if (draft.lock) "CONFIRM_LOCK" else "CONFIRM"
            val parameter = buildString {
                append("T,${draft.page},${draft.block},${draft.dataHex},")
                append(if (draft.lock) "1" else "0")
                append(",${draft.access},${draft.password ?: "NONE"},$confirmation")
            }
            // Exactly one programming command. It is intentionally outside all retry logic.
            val response = client.send("+LFWRITE=", parameter, LF_WRITE_TIMEOUT_MS)
            val write = response.payload as? AtPayload.LfWrite
                ?: protocolViolation("Некорректный ответ LFWRITE")
            require(
                write.page == draft.page && write.address == draft.block &&
                    write.dataHex == draft.dataHex && write.lock == draft.lock &&
                    write.status in setOf("OK", "OK_LOCK_UNVERIFIED"),
            ) { "Параметры ответа LFWRITE не совпали с запросом" }

            val readBack = try {
                readLfBlockWire('T', draft.page, draft.block, pending.verificationPassword).data.toHex()
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "Прошивка сообщила ${write.status}, но независимый read-back UI не выполнен: ${error.message}",
                )
            }
            require(readBack == draft.dataHex) {
                "Независимый read-back UI не совпал: ожидалось ${draft.dataHex}, прочитано $readBack"
            }
            val displayBlock = draft.page * 8 + draft.block
            updateMemoryBlock(displayBlock, dataHex = readBack, error = null)
            WriteResult(displayBlock, write.status, pending.oldHex, draft.dataHex, readBack, true)
                .also { _state.update { state -> state.copy(infoMessage = "LF-блок записан и проверен") } }
        }
    }

    fun beginClone(request: CloneRequest) {
        val card = requireCard()
        require(card.uidHex == request.sourceUidHex.uppercase()) { "UID источника изменился" }
        when (request) {
            is CloneRequest.Mifare -> {
                require(request.magicTargetConfirmed) {
                    "Подтвердите, что цель — карта со сменным UID (magic)"
                }
                require(card.family in setOf(CardFamily.MIFARE_CLASSIC, CardFamily.MIFARE_PLUS)) {
                    "MIFARE-клонирование недоступно для этого семейства"
                }
                require(request.sourceUidHex.matches(Regex("^[A-Fa-f0-9]{8}$|^[A-Fa-f0-9]{14}$"))) {
                    "UID MIFARE должен содержать 8 или 14 HEX-символов"
                }
            }
            is CloneRequest.EmMarine -> {
                require(card.family == CardFamily.EM_MARINE) { "Клонируется только EM‑Marine; HID Prox исключён" }
                require(request.sourceUidHex.matches(Regex("^[A-Fa-f0-9]{10}$"))) {
                    "UID EM‑Marine должен содержать 10 HEX-символов"
                }
                request.currentPassword?.let { normalizeHex(it, 4, "Текущий пароль") }
                request.newPassword?.let { normalizeHex(it, 4, "Новый пароль") }
            }
        }
        _state.update {
            it.copy(
                cloneRequest = request,
                cloneStage = CloneStage.WAITING_SOURCE_REMOVAL,
                infoMessage = "Уберите источник со считывателя",
                errorMessage = null,
            )
        }
    }

    fun cancelClone() {
        _state.update {
            it.copy(cloneRequest = null, cloneStage = CloneStage.IDLE, infoMessage = "Клонирование отменено")
        }
    }

    private suspend fun runConnectedSession() {
        try {
            client.resetInput()
            transport.write(byteArrayOf('\r'.code.toByte()))
            delay(100)
            client.resetInput()
            val firmware = expect<AtPayload.Firmware>(client.send("I"), "ATI")
            client.send("+SCAN0")
            client.send("+RF=1")
            _state.update { it.copy(firmwareVersion = firmware.version, infoMessage = "Считыватель готов") }
            pollingLoop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            client.log.event("Ошибка сеанса: ${error.javaClass.simpleName}: $detail")
            _state.update { it.copy(errorMessage = detail) }
            if (transport.isOpen) transport.close("Ошибка handshake: $detail")
        }
    }

    private suspend fun pollingLoop() {
        while (scope.isActive && transport.isOpen) {
            if (operationMutex.tryLock()) {
                try {
                    pollOnce()
                } finally {
                    operationMutex.unlock()
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollOnce() {
        try {
            val uid = client.send("+i").payload as? AtPayload.Uid
            if (uid == null || uid.bytes.size < 5) {
                handlePollMiss()
                return
            }
            pollMisses = 0
            val current = _state.value.card
            val detectedHex = detectedUidHex(uid.bytes, current)
            if (current == null || detectedHex != current.uidHex) {
                applyCard(readCardStatusWire())
            }
        } catch (error: AtCommandException) {
            handlePollMiss()
        }
    }

    private suspend fun readCardStatusWire(): CardInfo {
        val status = expect<AtPayload.CardStatus>(client.send("+S"), "AT+S")
        val family = CardTypes.family(status.type)
        val uidHex = status.uid.toHex()
        return CardInfo(
            uidHex = uidHex,
            sak = status.sak,
            blockCount = if (family in setOf(CardFamily.EM_MARINE, CardFamily.HID_PROX)) 1 else status.blocks,
            blockSize = if (family in setOf(CardFamily.EM_MARINE, CardFamily.HID_PROX)) status.uid.size else status.blockSize,
            rawType = status.type,
            typeName = CardTypes.name(status.type),
            family = family,
        )
    }

    private fun applyCard(card: CardInfo) {
        val old = _state.value
        val sameCard = old.card?.let { it.uidHex == card.uidHex && it.rawType == card.rawType } == true
        val layout = if (card.supportsMemory) MemoryPolicies.layout(card) else null
        val memory = if (sameCard) old.memory else layout?.let { emptyMemory(it, card) }.orEmpty()
        val waitingForTarget = old.cloneStage == CloneStage.WAITING_TARGET
        _state.update {
            it.copy(
                card = card,
                lfClassification = if (sameCard) it.lfClassification else null,
                memoryLayout = if (sameCard && it.lfClassification != null) it.memoryLayout else layout,
                memory = memory,
                cloneStage = if (waitingForTarget) CloneStage.WRITING else it.cloneStage,
                infoMessage = if (waitingForTarget) "Целевая карта обнаружена" else it.infoMessage,
            )
        }
        if (waitingForTarget) scope.launch { performCloneAfterSwap() }
    }

    private fun handlePollMiss() {
        val card = _state.value.card ?: return
        pollMisses++
        val threshold = if (card.isLowFrequency) 3 else 1
        if (pollMisses < threshold) return
        pollMisses = 0
        val cloneTransition = _state.value.cloneStage == CloneStage.WAITING_SOURCE_REMOVAL
        _state.update {
            it.copy(
                card = null,
                lfClassification = null,
                memoryLayout = null,
                memory = emptyList(),
                cloneStage = if (cloneTransition) CloneStage.WAITING_TARGET else it.cloneStage,
                infoMessage = if (cloneTransition) "Поднесите целевую карту" else it.infoMessage,
            )
        }
    }

    private suspend fun performCloneAfterSwap() {
        val request = _state.value.cloneRequest ?: return
        try {
            operation("Клонирование") {
                require(_state.value.cloneStage == CloneStage.WRITING) { "Сценарий клонирования уже завершён" }
                when (request) {
                    is CloneRequest.Mifare -> {
                        // Exactly one magic-card UID write; no automatic retry.
                        client.send("+~", request.sourceUidHex.uppercase(), WRITE_TIMEOUT_MS)
                    }
                    is CloneRequest.EmMarine -> {
                        val classification = readStructuredLfClass("FAST")
                        require(classification.family == CardFamily.LF_T55XX) {
                            "Цель не подтверждена как T55xx/T5577"
                        }
                        request.currentPassword?.takeIf(String::isNotBlank)?.let {
                            client.send("+u", normalizeHex(it, 4, "Текущий пароль"), WRITE_TIMEOUT_MS)
                        }
                        client.send("+c", "${request.coding.wireValue},${request.speed.wireValue}")
                        if (request.newPassword.isNullOrBlank()) {
                            // The only UID programming command in this branch.
                            client.send("+@", request.sourceUidHex.uppercase(), WRITE_TIMEOUT_MS)
                        } else {
                            client.send(
                                "+x",
                                "${normalizeHex(request.newPassword, 4, "Новый пароль")},${request.sourceUidHex.uppercase()}",
                                WRITE_TIMEOUT_MS,
                            )
                        }
                    }
                }

                _state.update { it.copy(cloneStage = CloneStage.VERIFYING, infoMessage = "Проверка UID цели") }
                val verified = verifyUidByScanning(request.sourceUidHex.uppercase())
                if (verified) {
                    _state.update {
                        it.copy(
                            cloneStage = CloneStage.COMPLETE,
                            cloneRequest = null,
                            infoMessage = "Клонирование завершено; UID подтверждён",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            cloneStage = CloneStage.FAILED,
                            cloneRequest = null,
                            errorMessage = "Запись отправлена один раз, но независимое сканирование не подтвердило UID",
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _state.update {
                it.copy(
                    cloneStage = CloneStage.FAILED,
                    cloneRequest = null,
                    errorMessage = "Клонирование остановлено: ${error.message}. Автоповтора не было",
                )
            }
        }
    }

    private suspend fun verifyUidByScanning(expected: String): Boolean {
        repeat(CLONE_VERIFY_ATTEMPTS) {
            delay(CLONE_VERIFY_DELAY_MS)
            val status = runCatching { readCardStatusWire() }.getOrNull()
            if (status?.uidHex == expected) {
                applyCard(status)
                return true
            }
        }
        return false
    }

    private suspend fun classifyLfWire(mode: String): LfClassification {
        return try {
            readStructuredLfClass(mode)
        } catch (unsupported: AtCommandException) {
            val info = expect<AtPayload.LfInfo>(
                client.send("+LFINFO?", timeoutMs = LF_READ_TIMEOUT_MS),
                "LFINFO",
            )
            LfClassification(
                status = "OK",
                airProtocol = "UNKNOWN",
                chip = info.type,
                evidence = when (info.chipInfoKind) {
                    "trace" -> if (info.type == "T5555") "T5555_TRACE" else "T5577_TRACE"
                    "config" -> "T55XX_CONFIG"
                    else -> "EM4X05_WORD0"
                },
                chipInfoHex = info.chipInfoHex,
                structured = false,
            )
        }
    }

    private suspend fun readStructuredLfClass(mode: String): LfClassification {
        val payload = expect<AtPayload.LfClass>(
            client.send("+LFCLASS=", mode, LF_READ_TIMEOUT_MS),
            "LFCLASS",
        )
        return LfClassification(
            payload.status,
            payload.air,
            payload.chip,
            payload.evidence,
            payload.chipInfoHex,
            structured = true,
        )
    }

    private fun applyLfClassification(classification: LfClassification) {
        val layout = MemoryPolicies.lfLayout(classification)
        val memory = emptyMemory(layout, null, classification).toMutableList()
        val seedIndex = when (classification.evidence) {
            "T5577_TRACE", "T5555_TRACE" -> 9
            "T55XX_CONFIG", "EM4X05_WORD0" -> 0
            else -> -1
        }
        if (seedIndex in memory.indices) {
            memory[seedIndex] = memory[seedIndex].copy(dataHex = classification.chipInfoHex)
        }
        _state.update {
            it.copy(
                lfClassification = classification,
                memoryLayout = layout,
                memory = memory,
                infoMessage = if (classification.structured) {
                    "LFCLASS: ${classification.chip}"
                } else {
                    "LFINFO fallback: только чтение"
                },
            )
        }
    }

    private suspend fun readT55xxMemory(classification: LfClassification) {
        val password = _state.value.keys.lfPassword.takeIf(String::isNotBlank)
        val total = if (classification.chip in setOf("T5577", "T5555")) 10 else 8
        setProgress(0, total)
        for (block in 0..7) {
            readLfIntoMemory('T', 0, block, block, password)
            setProgress(block + 1, total)
        }
        if (classification.chip in setOf("T5577", "T5555")) {
            try {
                val trace = expect<AtPayload.LfTrace>(
                    client.send("+LFTRACE?", timeoutMs = LF_READ_TIMEOUT_MS),
                    "LFTRACE",
                )
                updateMemoryBlock(9, trace.block1.toHex(), null)
                updateMemoryBlock(10, trace.block2.toHex(), null)
            } catch (error: AtCommandException) {
                updateMemoryBlock(9, null, error.message)
                updateMemoryBlock(10, null, error.message)
            }
            setProgress(total, total)
        }
    }

    private suspend fun readEm4x05Memory(layout: ru.opendev.odrfidmne.model.MemoryLayout) {
        setProgress(0, layout.blockCount)
        for (word in 0 until layout.blockCount) {
            readLfIntoMemory('E', 0, word, word, null)
            setProgress(word + 1, layout.blockCount)
        }
    }

    private suspend fun readEm4x50Memory() {
        val stream = expect<AtPayload.LfStream>(
            client.send("+LFSTREAM?", timeoutMs = LF_READ_TIMEOUT_MS),
            "LFSTREAM",
        )
        setProgress(0, stream.words.size)
        stream.words.forEachIndexed { index, data ->
            updateMemoryBlock(index, data.toHex(), null)
            setProgress(index + 1, stream.words.size)
        }
    }

    private suspend fun readLfIntoMemory(
        family: Char,
        page: Int,
        address: Int,
        displayBlock: Int,
        password: String?,
    ) {
        try {
            val data = readLfBlockWire(family, page, address, password).data.toHex()
            updateMemoryBlock(displayBlock, data, null)
        } catch (error: AtCommandException) {
            updateMemoryBlock(displayBlock, null, error.message)
        }
    }

    private suspend fun readLfBlockWire(
        family: Char,
        page: Int,
        address: Int,
        password: String?,
    ): AtPayload.LfBlock {
        require(family == 'T' || family == 'E') { "Неизвестное LF-семейство" }
        require(page in 0..1) { "Страница T55xx должна быть 0 или 1" }
        require(address in 0..if (family == 'T') 7 else 15) { "Неверный адрес LF" }
        val normalizedPassword = password?.takeIf(String::isNotBlank)?.let {
            require(family == 'T') { "Пароль поддерживается только для T55xx" }
            normalizeHex(it, 4, "Пароль T55xx")
        }
        val parameter = if (family == 'T') {
            "T,$page,$address${normalizedPassword?.let { ",P,$it" }.orEmpty()}"
        } else {
            "E,$address"
        }
        val block = expect<AtPayload.LfBlock>(
            client.send("+LFREAD=", parameter, LF_READ_TIMEOUT_MS),
            "LFREAD",
        )
        require(
            block.family == family &&
                block.page == (if (family == 'T') page else 0) &&
                block.address == address,
        ) {
            "Адрес в ответе LFREAD не совпал с запросом"
        }
        return block
    }

    private suspend fun readHfBlockWire(card: CardInfo, block: Int): ByteArray {
        require(block in 0 until card.blockCount) { "Блок вне диапазона" }
        authenticatePlusBlockIfNeeded(card, block)
        val command = if (CardTypes.usesAesBlockCommands(card.rawType)) "+M" else "+R"
        val payload = expect<AtPayload.BlockData>(client.send(command, block.toString()), "чтение блока")
        require(payload.block == block) { "Считыватель вернул другой номер блока" }
        require(payload.data.size == card.blockSize) {
            "Размер блока ${payload.data.size}, ожидалось ${card.blockSize}"
        }
        return payload.data
    }

    private suspend fun authenticatePlusBlockIfNeeded(card: CardInfo, block: Int) {
        if (!CardTypes.usesAesBlockCommands(card.rawType)) return
        val keyTypeOffset = if (_state.value.keys.plusKeyType == 'B') 1 else 0
        val keyNumber = 0x4000 + classicBlockToSector(block) * 2 + keyTypeOffset
        client.send("+N", keyNumber.toString())
    }

    private fun validateLfDraft(draft: LfWriteDraft): LfWriteDraft {
        require(draft.page in 0..1 && draft.block in 0..7) {
            "Адрес T55xx должен содержать page 0–1 и block 0–7"
        }
        require(!(draft.page == 1 && draft.block == 0)) {
            "Page 1 Block 0 нельзя записывать: адрес неоднозначен"
        }
        val data = normalizeHex(draft.dataHex, 4, "Данные")
        val access = draft.access.uppercaseChar()
        require(access == 'N' || access == 'P') { "Режим доступа должен быть N или P" }
        val enablesPassword = draft.page == 0 && draft.block == 0 &&
            (data.toLong(16) and 0x10L) != 0L
        val password = if (access == 'P' || enablesPassword) {
            normalizeHex(
                draft.password.orEmpty(),
                4,
                if (access == 'P') "Текущий пароль" else "Будущий пароль",
            )
        } else null
        return draft.copy(dataHex = data, access = access, password = password)
    }

    private fun verificationPassword(draft: LfWriteDraft): String? {
        val enablesPassword = draft.page == 0 && draft.block == 0 &&
            (draft.dataHex.toLong(16) and 0x10L) != 0L
        return when {
            draft.page == 0 && draft.block == 0 -> if (enablesPassword) draft.password else null
            draft.page == 0 && draft.block == 7 && draft.access == 'P' -> draft.dataHex
            draft.access == 'P' -> draft.password
            else -> null
        }
    }

    private fun setLayout(layout: ru.opendev.odrfidmne.model.MemoryLayout, card: CardInfo) {
        _state.update { it.copy(memoryLayout = layout, memory = emptyMemory(layout, card)) }
    }

    private fun setLfLayout(
        layout: ru.opendev.odrfidmne.model.MemoryLayout,
        classification: LfClassification,
    ) {
        _state.update {
            it.copy(memoryLayout = layout, memory = emptyMemory(layout, null, classification))
        }
    }

    private fun emptyMemory(
        layout: ru.opendev.odrfidmne.model.MemoryLayout,
        card: CardInfo?,
        classification: LfClassification? = null,
    ): List<MemoryBlock> = List(layout.blockCount) { index ->
        val risk = when {
            card != null -> MemoryPolicies.hfRisk(card, index)
            classification?.family == CardFamily.LF_T55XX -> MemoryPolicies.lfRisk(index / 8, index % 8)
            else -> null
        }
        MemoryBlock(
            index = index,
            label = layout.labels[index],
            groupLabel = layout.groupLabels.getOrNull(index),
            writable = risk?.let(MemoryPolicies::isWritable) == true &&
                (classification == null || classification.structured),
            risk = risk,
        )
    }

    private fun updateMemoryBlock(block: Int, dataHex: String?, error: String?) {
        _state.update { state ->
            if (block !in state.memory.indices) state else state.copy(
                memory = state.memory.toMutableList().also { list ->
                    list[block] = list[block].copy(dataHex = dataHex, error = error)
                },
            )
        }
    }

    private fun setProgress(current: Int, total: Int) =
        _state.update { it.copy(progressCurrent = current, progressTotal = total) }

    private suspend fun <T> operation(
        label: String,
        total: Int = 0,
        block: suspend () -> T,
    ): T = operationMutex.withLock {
        _state.update {
            it.copy(
                busy = true,
                operationLabel = label,
                progressCurrent = 0,
                progressTotal = total,
                errorMessage = null,
                infoMessage = null,
            )
        }
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _state.update { it.copy(errorMessage = error.message ?: "Неизвестная ошибка") }
            throw error
        } finally {
            _state.update { it.copy(busy = false, operationLabel = null) }
        }
    }

    private suspend inline fun <reified T : AtPayload> expect(response: AtResponse, context: String): T =
        response.payload as? T ?: protocolViolation("Некорректный ответ $context")

    private suspend fun protocolViolation(message: String): Nothing {
        transport.close(message)
        throw AtDisconnectedException("$message; требуется переподключение")
    }

    private fun requireCard(): CardInfo =
        _state.value.card ?: throw IllegalStateException("Карта не обнаружена")

    private fun requireSameCard(expected: CardInfo, actual: CardInfo) {
        require(expected.uidHex == actual.uidHex && expected.rawType == actual.rawType) {
            "UID или тип карты изменился; запись отменена"
        }
    }

    private fun detectedUidHex(bytes: ByteArray, current: CardInfo?): String {
        val currentSize = current?.uidHex?.length?.div(2)
        val hasTrailingStatusByte = currentSize != null && bytes.size == currentSize + 1 &&
            (!current.isLowFrequency || bytes.last() == 0xFF.toByte())
        val uid = if (hasTrailingStatusByte) bytes.dropLast(1).toByteArray() else bytes
        return uid.toHex()
    }

    private fun clearSessionState() {
        pollMisses = 0
        _state.value = RepositoryState()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val CLONE_VERIFY_ATTEMPTS = 5
        const val CLONE_VERIFY_DELAY_MS = 700L
    }
}
