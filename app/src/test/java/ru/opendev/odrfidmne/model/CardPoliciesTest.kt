package ru.opendev.odrfidmne.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPoliciesTest {
    @Test
    fun classicSectorGeometryCoversSmallAndLargeSectors() {
        assertEquals(4, classicBlocksInSector(0))
        assertEquals(4, classicBlocksInSector(31))
        assertEquals(16, classicBlocksInSector(32))
        assertEquals(124, classicSectorFirstBlock(31))
        assertEquals(128, classicSectorFirstBlock(32))
        assertEquals(240, classicSectorFirstBlock(39))
        assertEquals(31, classicBlockToSector(127))
        assertEquals(32, classicBlockToSector(128))
        assertEquals(39, classicBlockToSector(255))
        assertTrue(isClassicTrailerBlock(3))
        assertTrue(isClassicTrailerBlock(127))
        assertTrue(isClassicTrailerBlock(143))
        assertTrue(isClassicTrailerBlock(255))
        assertFalse(isClassicTrailerBlock(142))
    }

    @Test
    fun classicPolicyExcludesManufacturerAndEverySectorTrailer() {
        val card = card(CardTypes.CLASSIC_4K, 256, 16)
        assertEquals(RiskLevel.BLOCKED, MemoryPolicies.hfRisk(card, 0).level)
        for (block in 0 until card.blockCount) {
            val level = MemoryPolicies.hfRisk(card, block).level
            if (isClassicTrailerBlock(block)) {
                assertEquals("block $block", RiskLevel.BLOCKED, level)
            } else if (block > 0) {
                assertEquals("block $block", RiskLevel.WARNING, level)
            }
        }
    }

    @Test
    fun type2PolicyAllowsOnlyConservativeUserPages() {
        val cases = listOf(
            Triple(CardTypes.ULTRALIGHT, 16, 15),
            Triple(CardTypes.ULTRALIGHT_C, 48, 39),
            Triple(CardTypes.ULTRALIGHT_EV1_80, 20, 15),
            Triple(CardTypes.ULTRALIGHT_EV1_164, 41, 35),
            Triple(CardTypes.NTAG213, 45, 39),
            Triple(CardTypes.NTAG215, 135, 129),
            Triple(CardTypes.NTAG216, 231, 225),
            Triple(CardTypes.ULTRALIGHT_NANO, 14, 9),
        )
        cases.forEach { (type, blocks, lastUserPage) ->
            val card = card(type, blocks, 4)
            assertEquals("type $type page 3", RiskLevel.BLOCKED, MemoryPolicies.hfRisk(card, 3).level)
            assertEquals("type $type page 4", RiskLevel.WARNING, MemoryPolicies.hfRisk(card, 4).level)
            assertEquals(
                "type $type page $lastUserPage",
                RiskLevel.WARNING,
                MemoryPolicies.hfRisk(card, lastUserPage).level,
            )
            if (lastUserPage + 1 < blocks) {
                assertEquals(
                    "type $type service page",
                    RiskLevel.BLOCKED,
                    MemoryPolicies.hfRisk(card, lastUserPage + 1).level,
                )
            }
        }
    }

    @Test
    fun t55xxPolicyBlocksAliasAndRequiresCriticalPhrases() {
        assertEquals(RiskLevel.BLOCKED, MemoryPolicies.lfRisk(1, 0).level)
        assertEquals("WRITE P0/B0", MemoryPolicies.lfRisk(0, 0).confirmationPhrase)
        assertEquals("WRITE P0/B7", MemoryPolicies.lfRisk(0, 7).confirmationPhrase)
        assertEquals("WRITE P1/B1", MemoryPolicies.lfRisk(1, 1).confirmationPhrase)
        assertEquals("LOCK P1/B1", MemoryPolicies.lfRisk(1, 1, lock = true).confirmationPhrase)
        assertEquals("LOCK P0/B4", MemoryPolicies.lfRisk(0, 4, lock = true).confirmationPhrase)
        assertEquals("", MemoryPolicies.lfRisk(0, 4).confirmationPhrase)
    }

    @Test
    fun lfLayoutsAndClassificationFamiliesAreTyped() {
        val t55 = classification("T5577", structured = true)
        val layout = MemoryPolicies.lfLayout(t55)
        assertEquals(16, layout.blockCount)
        assertEquals("P1/B7", layout.labels.last())
        assertEquals(CardFamily.LF_T55XX, t55.family)
        assertEquals(CardFamily.LF_EM4X05, classification("EM4305", true).family)
        assertEquals(CardFamily.LF_EM4X50, classification("EM4X50", true).family)
    }

    @Test
    fun decimalAndWiegandAppearOnlyForSuitableLfCards() {
        val em = CardInfo(
            uidHex = "0102030405",
            sak = 0xFF,
            blockCount = 1,
            blockSize = 5,
            rawType = CardTypes.EM_4100,
            typeName = CardTypes.name(CardTypes.EM_4100),
            family = CardFamily.EM_MARINE,
        )
        val formatted = formatLfIdentifiers(em)!!
        assertEquals("0000197637", formatted.decimal)
        assertEquals("3.01029", formatted.wiegand)
        assertNull(formatLfIdentifiers(card(CardTypes.CLASSIC_1K, 64, 16)))
    }

    @Test
    fun uidAndHexFormattingAreStrictAndAsciiPreviewIsSafe() {
        assertEquals("00A5FF", byteArrayOf(0, 0xA5.toByte(), 0xFF.toByte()).toHex())
        assertTrue("00 a5 FF".hexToBytes().contentEquals(byteArrayOf(0, 0xA5.toByte(), 0xFF.toByte())))
        assertEquals("AABBCCDD", normalizeHex("aa bb cc dd", 4, "value"))
        assertThrows(IllegalArgumentException::class.java) { normalizeHex("ABC", 2, "value") }
        assertThrows(IllegalArgumentException::class.java) { "GG".hexToBytes() }
        assertEquals("A.. ", MemoryBlock(1, "B1", dataHex = "41001F20").asciiPreview)
    }

    @Test
    fun unsupportedProtectedFamiliesHaveNoMemoryEditor() {
        listOf(CardTypes.DESFIRE, CardTypes.NTAG424, CardTypes.ISO_14443_4).forEach { type ->
            val card = card(type, 0, 0)
            assertEquals(CardFamily.UNSUPPORTED, card.family)
            assertFalse(card.supportsMemory)
            assertEquals(RiskLevel.BLOCKED, MemoryPolicies.hfRisk(card, 0).level)
        }
    }

    private fun card(type: Int, blocks: Int, size: Int) = CardInfo(
        uidHex = "01020304",
        sak = 8,
        blockCount = blocks,
        blockSize = size,
        rawType = type,
        typeName = CardTypes.name(type),
        family = CardTypes.family(type),
    )

    private fun classification(chip: String, structured: Boolean) = LfClassification(
        status = "OK",
        airProtocol = "EM4100_COMPAT",
        chip = chip,
        evidence = "T55XX_CONFIG",
        chipInfoHex = "00148040",
        structured = structured,
    )
}
