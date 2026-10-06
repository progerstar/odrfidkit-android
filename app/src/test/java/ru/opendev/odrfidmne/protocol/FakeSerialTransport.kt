package ru.opendev.odrfidmne.protocol

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import ru.opendev.odrfidmne.model.ConnectionState
import ru.opendev.odrfidmne.model.ReaderDevice

internal class FakeSerialTransport : SerialByteTransport {
    private val mutableConnection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val mutableIncoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    override val connection: StateFlow<ConnectionState> = mutableConnection
    override val incomingBytes: SharedFlow<ByteArray> = mutableIncoming
    override var isOpen: Boolean = false
        private set

    val writes = mutableListOf<String>()
    val closeReasons = mutableListOf<String?>()
    var flushCount = 0
    var flushFailure: Throwable? = null
    var responder: suspend (String) -> Unit = {}

    fun connect(deviceId: Int = 1) {
        isOpen = true
        mutableConnection.value = ConnectionState.Connected(
            ReaderDevice(deviceId, "Fake ODRFID #$deviceId", true),
        )
    }

    fun disconnect() {
        isOpen = false
        mutableConnection.value = ConnectionState.Disconnected
    }

    suspend fun receive(text: String) {
        mutableIncoming.emit(text.toByteArray(Charsets.US_ASCII))
    }

    suspend fun receive(bytes: ByteArray) {
        mutableIncoming.emit(bytes)
    }

    override suspend fun write(data: ByteArray) {
        check(isOpen) { "Fake transport is closed" }
        val wire = data.toString(Charsets.US_ASCII)
        writes += wire
        responder(wire)
    }

    override suspend fun flushInput() {
        flushCount++
        flushFailure?.let { throw it }
    }

    override suspend fun close(reason: String?) {
        closeReasons += reason
        isOpen = false
        mutableConnection.value = if (reason == null) {
            ConnectionState.Disconnected
        } else {
            ConnectionState.Error(reason)
        }
    }
}
