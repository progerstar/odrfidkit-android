package ru.opendev.odrfidmne.protocol

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import ru.opendev.odrfidmne.model.ConnectionState

internal class AtCommandException(message: String) : IOException(message)
internal class AtDisconnectedException(message: String) : IOException(message)

internal class AtCommandClient(
    private val transport: SerialByteTransport,
    scope: CoroutineScope,
    val log: DiagnosticLog = DiagnosticLog(),
) {
    private sealed interface Event {
        data class Line(val value: String) : Event
        data class Failure(val error: Throwable) : Event
        data object Disconnected : Event
    }

    private val framer = CrlfFramer()
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val commandMutex = Mutex()

    init {
        scope.launch {
            transport.incomingBytes.collect { chunk ->
                try {
                    framer.feed(chunk).forEach { line ->
                        if (line.isNotEmpty()) {
                            log.response(line)
                            events.send(Event.Line(line))
                        }
                    }
                } catch (error: FramingException) {
                    events.send(Event.Failure(error))
                    transport.close("Нарушение CRLF-фрейминга")
                }
            }
        }
        scope.launch {
            transport.connection.drop(1).collect { state ->
                if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting) {
                    events.send(Event.Disconnected)
                }
            }
        }
    }

    suspend fun resetInput() {
        commandMutex.withLock {
            transport.flushInput()
            framer.reset()
            drainEvents()
        }
    }

    suspend fun send(
        command: String,
        parameter: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): AtResponse = commandMutex.withLock {
        if (!transport.isOpen) throw AtDisconnectedException("Считыватель не подключён")
        drainEvents()

        val wireCommand = "AT$command$parameter\r"
        log.command(wireCommand)
        try {
            transport.write(wireCommand.toByteArray(Charsets.US_ASCII))
        } catch (error: Throwable) {
            transport.close("Ошибка записи в CDC")
            throw AtDisconnectedException("Не удалось отправить команду: ${error.message}")
        }

        val lines = mutableListOf<String>()
        val success = try {
            withTimeout(timeoutMs) {
                while (true) {
                    when (val event = events.receive()) {
                        is Event.Line -> when {
                            event.value.startsWith("SCAN:") -> Unit
                            event.value == "OK" -> return@withTimeout true
                            event.value == "ERROR" -> return@withTimeout false
                            else -> lines += event.value
                        }
                        is Event.Failure -> throw event.error
                        Event.Disconnected -> throw AtDisconnectedException("Устройство отключено")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        } catch (timeout: TimeoutCancellationException) {
            transport.close("Таймаут команды AT$command")
            throw AtDisconnectedException(
                "Таймаут команды AT$command; соединение закрыто, чтобы исключить поздний ответ",
            )
        } catch (framing: FramingException) {
            transport.close("Нарушение CRLF-фрейминга")
            throw AtDisconnectedException("Нарушение фрейминга; требуется переподключение")
        }

        val response = AtResponseParser.parse(command, success, lines)
        if (!success) throw AtCommandException(formatError(command, parameter, response))
        response
    }

    private fun drainEvents() {
        while (events.tryReceive().isSuccess) Unit
    }

    private fun formatError(command: String, parameter: String, response: AtResponse): String {
        if (command == "+LFREAD=" && response.errorCode == 2048) {
            val safeCommand = log.redact("AT$command$parameter")
            return "$safeCommand: ошибка обмена с LF-меткой (+CME ERROR: 2048)"
        }
        val write = response.payload as? AtPayload.LfWrite
        if (command == "+LFWRITE=" && write != null) {
            val reason = mapOf(
                "NO_UID" to "метка не обнаружена",
                "TRANSPORT" to "ошибка передачи; состояние блока неопределено",
                "VERIFY_NO_TAG" to "метка не ответила при контрольном чтении",
                "VERIFY_PROTOCOL" to "после записи обнаружен неожиданный протокол",
                "VERIFY_MISMATCH" to "32-битное контрольное чтение не совпало; блок может быть заблокирован",
                "INVALID_ARGUMENT" to "прошивка отклонила параметры",
                "ACCESS_MISMATCH" to "normal/password access не совпал с PWD-состоянием метки",
                "PASSWORD_REQUIRED" to "для нового password mode нужен будущий пароль",
                "UNKNOWN" to "неизвестная ошибка прошивки",
            )[write.status] ?: write.status
            return "AT+LFWRITE=${parameter.split(',').take(3).joinToString(",")},…: $reason. " +
                "Запись автоматически не повторялась"
        }
        val reply = response.lines.joinToString("; ") { line ->
            log.redactFreeText(log.redact(line))
        }
        return "Команда AT$command завершилась с ошибкой${if (reply.isBlank()) "" else ": $reply"}"
    }
}
