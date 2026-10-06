package ru.opendev.odrfidmne.protocol

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import ru.opendev.odrfidmne.model.ConnectionState

internal interface SerialByteTransport {
    val connection: StateFlow<ConnectionState>
    val incomingBytes: SharedFlow<ByteArray>
    val isOpen: Boolean

    suspend fun write(data: ByteArray)
    suspend fun flushInput()
    suspend fun close(reason: String? = null)
}

