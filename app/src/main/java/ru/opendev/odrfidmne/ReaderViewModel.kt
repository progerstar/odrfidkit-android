package ru.opendev.odrfidmne

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.opendev.odrfidmne.data.LfWriteDraft
import ru.opendev.odrfidmne.data.PendingHfWrite
import ru.opendev.odrfidmne.data.PendingLfWrite
import ru.opendev.odrfidmne.data.ReaderRepository
import ru.opendev.odrfidmne.model.CloneRequest
import ru.opendev.odrfidmne.model.CompactPage
import ru.opendev.odrfidmne.model.ConnectionState
import ru.opendev.odrfidmne.model.ReaderUiState
import ru.opendev.odrfidmne.model.WriteResult
import ru.opendev.odrfidmne.protocol.DiagnosticLog
import ru.opendev.odrfidmne.usb.CdcTransport

internal class ReaderViewModel(
    private val transport: CdcTransport,
    private val repository: ReaderRepository,
    diagnosticLog: DiagnosticLog,
) : ViewModel() {
    private val compactPage = MutableStateFlow(CompactPage.CARD)
    val diagnosticText: StateFlow<String> = diagnosticLog.text

    val uiState: StateFlow<ReaderUiState> = combine(
        transport.connection,
        transport.devices,
        repository.state,
        compactPage,
    ) { connection, devices, repositoryState, page ->
        val selected = when (connection) {
            is ConnectionState.Connected -> connection.device.deviceId
            is ConnectionState.Connecting -> connection.device.deviceId
            is ConnectionState.PermissionRequired -> connection.device.deviceId
            is ConnectionState.PermissionDenied -> connection.device.deviceId
            else -> null
        }
        ReaderUiState(
            connection = connection,
            devices = devices,
            selectedDeviceId = selected,
            firmwareVersion = repositoryState.firmwareVersion,
            card = repositoryState.card,
            lfClassification = repositoryState.lfClassification,
            memoryLayout = repositoryState.memoryLayout,
            memory = repositoryState.memory,
            keys = repositoryState.keys,
            busy = repositoryState.busy,
            operationLabel = repositoryState.operationLabel,
            progressCurrent = repositoryState.progressCurrent,
            progressTotal = repositoryState.progressTotal,
            errorMessage = repositoryState.errorMessage,
            infoMessage = repositoryState.infoMessage,
            compactPage = page,
            cloneStage = repositoryState.cloneStage,
            cloneRequest = repositoryState.cloneRequest,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ReaderUiState(),
    )

    fun refreshDevices() = transport.refreshDevices()

    fun connect(deviceId: Int) {
        viewModelScope.launch { transport.connect(deviceId) }
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }

    fun showPage(page: CompactPage) {
        compactPage.value = page
    }

    fun clearMessages() = repository.clearMessages()

    fun setClassicKey(type: Char, value: String) = launch { repository.setClassicKey(type, value) }
    fun setUltralightPassword(value: String) = launch { repository.setUltralightPassword(value) }
    fun setPlusKey(value: String, type: Char) = launch { repository.setPlusKey(value, type) }

    fun setLfPassword(value: String) {
        runCatching { repository.setLfSessionPassword(value) }
    }

    fun readMemory() = launch { repository.readAllMemory() }
    fun detectLf(mode: String) = launch { repository.detectLfMemory(mode) }

    suspend fun prepareHfWrite(block: Int, value: String): Result<PendingHfWrite> =
        runCatching { repository.prepareHfWrite(block, value) }

    suspend fun commitHfWrite(pending: PendingHfWrite): Result<WriteResult> =
        runCatching { repository.commitHfWrite(pending, confirmed = true) }

    suspend fun prepareLfWrite(draft: LfWriteDraft): Result<PendingLfWrite> =
        runCatching { repository.prepareLfWrite(draft) }

    suspend fun commitLfWrite(
        pending: PendingLfWrite,
        typedPhrase: String,
    ): Result<WriteResult> = runCatching {
        repository.commitLfWrite(pending, confirmed = true, typedPhrase = typedPhrase)
    }

    fun beginClone(request: CloneRequest): Result<Unit> = runCatching { repository.beginClone(request) }
    fun cancelClone() = repository.cancelClone()

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ReaderViewModel::class.java))
            return ReaderViewModel(
                container.transport,
                container.repository,
                container.diagnosticLog,
            ) as T
        }
    }
}

