package ru.opendev.odrfidmne.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test
    fun masksAllSessionSecretsAndClonePasswords() {
        val log = DiagnosticLog()
        listOf(
            "AT+LFREAD=T,1,2,P,11223344\r",
            "AT+LFWRITE=T,0,4,01020304,1,P,11223344,CONFIRM_LOCK\r",
            "AT+KA001122334455\r",
            "AT+KB66778899AABB\r",
            "AT+KU12345678\r",
            "AT+KX00112233445566778899AABBCCDDEEFF\r",
            "AT+u89ABCDEF\r",
            "AT+x89ABCDEF,0102030405\r",
        ).forEach(log::command)

        val text = log.text.value
        listOf(
            "11223344", "001122334455", "66778899AABB", "12345678",
            "00112233445566778899AABBCCDDEEFF", "89ABCDEF",
        ).forEach { assertFalse("secret leaked: $it", text.contains(it)) }
        assertTrue(text.contains("********"))
        assertTrue(text.contains("0102030405"))
    }

    @Test
    fun keepsOnlyConfiguredNumberOfLines() {
        val log = DiagnosticLog(maxLines = 3)
        repeat(5) { log.event("line-$it") }
        assertFalse(log.text.value.contains("line-0"))
        assertTrue(log.text.value.contains("line-2"))
        assertTrue(log.text.value.contains("line-4"))
    }

    @Test
    fun freeTextRedactionMasksKeysAndPasswords() {
        val log = DiagnosticLog()
        val safe = log.redactFreeText("password=00112233 key A: 001122334455 pwd DEADBEEF")
        assertFalse(safe.contains("00112233"))
        assertFalse(safe.contains("001122334455"))
        assertFalse(safe.contains("DEADBEEF"))
    }

    @Test
    fun masksSecretsEvenIfFirmwareEchoesThemAsAResponseOrEvent() {
        val log = DiagnosticLog()
        log.response("AT+KA001122334455")
        log.event("password=11223344")
        assertFalse(log.text.value.contains("001122334455"))
        assertFalse(log.text.value.contains("11223344"))
    }
}
