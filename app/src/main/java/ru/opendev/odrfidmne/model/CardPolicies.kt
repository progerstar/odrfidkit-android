package ru.opendev.odrfidmne.model

internal object CardTypes {
    const val CLASSIC_1K = 0
    const val CLASSIC_4K = 1
    const val CLASSIC_MINI = 2
    const val ULTRALIGHT = 3
    const val ULTRALIGHT_C = 4
    const val ULTRALIGHT_EV1_80 = 5
    const val ULTRALIGHT_EV1_164 = 6
    const val PLUS_S_2K_SL1 = 7
    const val PLUS_S_4K_SL1 = 8
    const val DESFIRE = 9
    const val DESFIRE_C1 = 10
    const val DESFIRE_C2 = 11
    const val CLASSIC_2K = 12
    const val PLUS_X_2K_SL1 = 13
    const val PLUS_X_4K_SL1 = 14
    const val PLUS_X_SL0 = 15
    const val PLUS_X_2K_SL2 = 16
    const val PLUS_X_4K_SL2 = 17
    const val PLUS_X_SL3 = 18
    const val PLUS_S_SL0 = 19
    const val PLUS_S_2K_SL2 = 20
    const val PLUS_S_4K_SL2 = 21
    const val PLUS_S_SL3 = 22
    const val NTAG213 = 23
    const val NTAG215 = 24
    const val NTAG216 = 25
    const val ULTRALIGHT_NANO = 26
    const val EM_4100 = 27
    const val NTAG413 = 28
    const val NTAG424 = 29
    const val PLUS_X_SL3_4K = 30
    const val PLUS_S_SL3_4K = 31
    const val ISO_14443_4 = 32
    const val HID_PROX = 33
    const val UNKNOWN = 255

    private val names = mapOf(
        CLASSIC_1K to "MIFARE Classic 1K",
        CLASSIC_4K to "MIFARE Classic 4K",
        CLASSIC_MINI to "MIFARE Classic Mini",
        ULTRALIGHT to "MIFARE Ultralight",
        ULTRALIGHT_C to "MIFARE Ultralight C",
        ULTRALIGHT_EV1_80 to "MIFARE Ultralight EV1 (80B)",
        ULTRALIGHT_EV1_164 to "MIFARE Ultralight EV1 (164B)",
        PLUS_S_2K_SL1 to "MIFARE Plus S 2K SL1",
        PLUS_S_4K_SL1 to "MIFARE Plus S 4K SL1",
        DESFIRE to "MIFARE DESFire",
        DESFIRE_C1 to "MIFARE DESFire (C1)",
        DESFIRE_C2 to "MIFARE DESFire (C2)",
        CLASSIC_2K to "MIFARE Classic 2K",
        PLUS_X_2K_SL1 to "MIFARE Plus X 2K SL1",
        PLUS_X_4K_SL1 to "MIFARE Plus X 4K SL1",
        PLUS_X_SL0 to "MIFARE Plus X SL0",
        PLUS_X_2K_SL2 to "MIFARE Plus X 2K SL2",
        PLUS_X_4K_SL2 to "MIFARE Plus X 4K SL2",
        PLUS_X_SL3 to "MIFARE Plus X SL3",
        PLUS_S_SL0 to "MIFARE Plus S SL0",
        PLUS_S_2K_SL2 to "MIFARE Plus S 2K SL2",
        PLUS_S_4K_SL2 to "MIFARE Plus S 4K SL2",
        PLUS_S_SL3 to "MIFARE Plus S SL3",
        NTAG213 to "NTAG213",
        NTAG215 to "NTAG215",
        NTAG216 to "NTAG216",
        ULTRALIGHT_NANO to "MIFARE Ultralight Nano",
        EM_4100 to "EM‑Marine / EM4100",
        NTAG413 to "NTAG413",
        NTAG424 to "NTAG424",
        PLUS_X_SL3_4K to "MIFARE Plus X 4K SL3",
        PLUS_S_SL3_4K to "MIFARE Plus S 4K SL3",
        ISO_14443_4 to "ISO 14443-4 / ISO 7816-4",
        HID_PROX to "HID Prox",
        UNKNOWN to "Неизвестная метка",
    )

    private val classic = setOf(
        CLASSIC_1K, CLASSIC_4K, CLASSIC_MINI, CLASSIC_2K,
        PLUS_S_2K_SL1, PLUS_S_4K_SL1, PLUS_X_2K_SL1, PLUS_X_4K_SL1,
    )
    private val type2 = setOf(
        ULTRALIGHT, ULTRALIGHT_C, ULTRALIGHT_EV1_80, ULTRALIGHT_EV1_164,
        NTAG213, NTAG215, NTAG216, ULTRALIGHT_NANO,
    )
    private val plusAes = setOf(
        PLUS_X_2K_SL2, PLUS_X_4K_SL2, PLUS_X_SL3,
        PLUS_S_2K_SL2, PLUS_S_4K_SL2, PLUS_S_SL3,
        PLUS_X_SL3_4K, PLUS_S_SL3_4K,
    )

    fun name(type: Int): String = names[type] ?: "Неизвестная метка"

    fun family(type: Int): CardFamily = when {
        type in classic -> if (type >= PLUS_S_2K_SL1 && type != CLASSIC_2K) {
            CardFamily.MIFARE_PLUS
        } else {
            CardFamily.MIFARE_CLASSIC
        }
        type in type2 -> CardFamily.ULTRALIGHT_NTAG
        type in plusAes -> CardFamily.MIFARE_PLUS
        type == EM_4100 -> CardFamily.EM_MARINE
        type == HID_PROX -> CardFamily.HID_PROX
        type in setOf(DESFIRE, DESFIRE_C1, DESFIRE_C2, NTAG413, NTAG424, ISO_14443_4) ->
            CardFamily.UNSUPPORTED
        else -> CardFamily.UNKNOWN
    }

    fun isClassicCompatible(type: Int): Boolean = type in classic || type in plusAes
    fun isType2(type: Int): Boolean = type in type2
    fun usesAesBlockCommands(type: Int): Boolean = type in plusAes
    fun needsUltralightPassword(type: Int): Boolean = type in setOf(
        ULTRALIGHT_EV1_80, ULTRALIGHT_EV1_164, NTAG213, NTAG215, NTAG216,
    )
}

internal object MemoryPolicies {
    fun layout(card: CardInfo): MemoryLayout {
        val labels = MutableList(card.blockCount) { index ->
            if (CardTypes.isType2(card.rawType)) "P$index" else "B$index"
        }
        val groups = MutableList<String?>(card.blockCount) { null }
        if (CardTypes.isClassicCompatible(card.rawType)) {
            for (block in 0 until card.blockCount) {
                val sector = classicBlockToSector(block)
                labels[block] = "B$block"
                groups[block] = "Сектор $sector"
            }
        }
        return MemoryLayout(card.blockCount, card.blockSize, labels, groups)
    }

    fun lfLayout(classification: LfClassification): MemoryLayout = when (classification.family) {
        CardFamily.LF_T55XX -> MemoryLayout(
            blockCount = 16,
            blockSize = 4,
            labels = List(16) { "P${it / 8}/B${it % 8}" },
            groupLabels = List(16) { "Страница ${it / 8}" },
        )
        CardFamily.LF_EM4X50 -> MemoryLayout(6, 4, List(6) { "W$it" })
        else -> MemoryLayout(16, 4, List(16) { "W$it" })
    }

    fun hfRisk(card: CardInfo, block: Int): WriteRisk {
        if (!card.supportsMemory || block !in 0 until card.blockCount) {
            return blocked("Запись не поддерживается", "Для этого семейства редактор памяти недоступен.")
        }
        if (CardTypes.isClassicCompatible(card.rawType)) {
            return when {
                block == 0 -> blocked("Производитель и UID", "Block 0 никогда не записывается приложением.")
                isClassicTrailerBlock(block) -> blocked(
                    "Sector trailer",
                    "Ключи и биты доступа относятся к служебной области сектора.",
                )
                else -> WriteRisk(
                    RiskLevel.WARNING,
                    "Пользовательский блок",
                    "Будет отправлена одна команда записи с отдельным read-back.",
                )
            }
        }
        if (CardTypes.isType2(card.rawType)) {
            val lastUserPage = type2LastUserPage(card.rawType)
            return if (block in 4..minOf(lastUserPage, card.blockCount - 1)) {
                WriteRisk(
                    RiskLevel.WARNING,
                    "Пользовательская страница",
                    "UID, OTP, lock, config, password и PACK исключены.",
                )
            } else {
                blocked(
                    "Служебная страница",
                    "UID, OTP, lock, config, password и PACK не записываются.",
                )
            }
        }
        return blocked("Запись не поддерживается", "Нет безопасной политики записи для этого типа.")
    }

    fun lfRisk(page: Int, block: Int, lock: Boolean = false): WriteRisk {
        val address = "P$page/B$block"
        if (page !in 0..1 || block !in 0..7) {
            return blocked("Неверный адрес", "Допустимы страницы 0–1 и блоки 0–7.")
        }
        if (page == 1 && block == 0) {
            return blocked(
                "Запись запрещена",
                "Page 1 Block 0 — неоднозначный псевдоним Page 0 Block 0.",
            )
        }
        val (title, message, level) = when {
            page == 0 && block == 0 -> Triple(
                "Конфигурация метки",
                "Ошибка изменит скорость, модуляцию или password mode и может сделать метку нечитаемой.",
                RiskLevel.CRITICAL,
            )
            page == 0 && block == 7 -> Triple(
                "Пароль T55xx",
                "Потеря нового значения может закрыть доступ к защищённой метке.",
                RiskLevel.CRITICAL,
            )
            page == 1 && block in 1..2 -> Triple(
                "Traceability Data",
                "У оригинального ATA5577C эти блоки записаны и заблокированы заводом.",
                RiskLevel.CRITICAL,
            )
            page == 1 && block == 3 -> Triple(
                "Аналоговые настройки",
                "Неверное значение может нарушить работу аналогового тракта.",
                RiskLevel.CRITICAL,
            )
            else -> Triple(
                "Память T55xx",
                "Запись изменит 32 бита EEPROM. Автоматического повтора не будет.",
                RiskLevel.WARNING,
            )
        }
        val phrase = when {
            lock -> "LOCK $address"
            level == RiskLevel.CRITICAL -> "WRITE $address"
            else -> ""
        }
        return WriteRisk(level, title, message, phrase)
    }

    fun isWritable(risk: WriteRisk): Boolean = risk.level != RiskLevel.BLOCKED

    private fun blocked(title: String, message: String) =
        WriteRisk(RiskLevel.BLOCKED, title, message)

    private fun type2LastUserPage(type: Int): Int = when (type) {
        CardTypes.ULTRALIGHT -> 15
        CardTypes.ULTRALIGHT_C -> 39
        CardTypes.ULTRALIGHT_EV1_80 -> 15
        CardTypes.ULTRALIGHT_EV1_164 -> 35
        CardTypes.NTAG213 -> 39
        CardTypes.NTAG215 -> 129
        CardTypes.NTAG216 -> 225
        CardTypes.ULTRALIGHT_NANO -> 9
        else -> 3
    }
}

internal fun classicBlocksInSector(sector: Int): Int = if (sector < 32) 4 else 16

internal fun classicSectorFirstBlock(sector: Int): Int =
    if (sector < 32) sector * 4 else 128 + (sector - 32) * 16

internal fun classicBlockToSector(block: Int): Int =
    if (block < 128) block / 4 else 32 + (block - 128) / 16

internal fun isClassicTrailerBlock(block: Int): Boolean =
    if (block < 128) block % 4 == 3 else (block - 128) % 16 == 15

internal data class LfFormats(val decimal: String, val wiegand: String)

internal fun formatLfIdentifiers(card: CardInfo): LfFormats? {
    if (!card.isLowFrequency) return null
    val bytes = runCatching { card.uidHex.hexToBytes() }.getOrNull() ?: return null
    val parts = if (card.family == CardFamily.HID_PROX) {
        if (bytes.size < 6) return null
        msbBits(bytes, 19, 8) to msbBits(bytes, 27, 16)
    } else {
        if (bytes.size < 3) return null
        (bytes[bytes.lastIndex - 2].toInt() and 0xFF) to
            (((bytes[bytes.lastIndex - 1].toInt() and 0xFF) shl 8) or
                (bytes[bytes.lastIndex].toInt() and 0xFF))
    }
    val (facilityCode, cardNumber) = parts
    val decimal = ((facilityCode.toLong() shl 16) or cardNumber.toLong()).toString().padStart(10, '0')
    return LfFormats(decimal, "$facilityCode.${cardNumber.toString().padStart(5, '0')}")
}

private fun msbBits(bytes: ByteArray, start: Int, count: Int): Int {
    var result = 0
    repeat(count) { offset ->
        val bitIndex = start + offset
        val bit = ((bytes[bitIndex / 8].toInt() and 0xFF) shr (7 - bitIndex % 8)) and 1
        result = (result shl 1) or bit
    }
    return result
}
