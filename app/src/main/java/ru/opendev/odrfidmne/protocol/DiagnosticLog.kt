package ru.opendev.odrfidmne.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DiagnosticLog(private val maxLines: Int = 100) {
    private val lines = ArrayDeque<String>()
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    @Synchronized
    fun command(command: String) = append(">> ${redact(command.trim())}")

    @Synchronized
    fun response(line: String) = append("<< ${redactFreeText(redact(line.trim()))}")

    @Synchronized
    fun event(message: String) = append("— ${redactFreeText(message)}")

    @Synchronized
    fun clear() {
        lines.clear()
        _text.value = ""
    }

    @Synchronized
    private fun append(line: String) {
        lines += line
        while (lines.size > maxLines) lines.removeFirst()
        _text.value = lines.joinToString("\n")
    }

    internal fun redact(command: String): String = command
        .replace(
            Regex("^(AT\\+LFREAD=T,[01],[0-7],P,)[A-F0-9]{8}$", RegexOption.IGNORE_CASE),
            "$1********",
        )
        .replace(
            Regex(
                "^(AT\\+LFWRITE=T,[01],[0-7],[A-F0-9]{8},[01],[NP],)([A-F0-9]{8})(,(?:CONFIRM|CONFIRM_LOCK))$",
                RegexOption.IGNORE_CASE,
            ),
            "$1********$3",
        )
        .replace(
            Regex("^(AT\\+(?:KA|KB|KU|KX|u))[A-F0-9]+$", RegexOption.IGNORE_CASE),
            "$1********",
        )
        .replace(
            Regex("^(AT\\+x)[A-F0-9]{8}(,[A-F0-9]{10})$", RegexOption.IGNORE_CASE),
            "$1********$2",
        )

    internal fun redactFreeText(text: String): String = text
        .replace(Regex("(?i)(парол(?:ь|я)|password|key(?:[ _-]?[abx])?|pwd)([=: ]+)[A-F0-9]{8,32}")) {
            "${it.groupValues[1]}${it.groupValues[2]}********"
        }
}
