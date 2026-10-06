package ru.opendev.odrfidmne.ui

import ru.opendev.odrfidmne.ReaderViewModel
import ru.opendev.odrfidmne.data.LfWriteDraft
import ru.opendev.odrfidmne.data.PendingHfWrite
import ru.opendev.odrfidmne.data.PendingLfWrite
import ru.opendev.odrfidmne.model.CloneRequest
import ru.opendev.odrfidmne.model.CompactPage
import ru.opendev.odrfidmne.model.WriteResult

internal interface ReaderActions {
    fun refreshDevices()
    fun connect(deviceId: Int)
    fun disconnect()
    fun showPage(page: CompactPage)
    fun clearMessages()
    fun setClassicKey(type: Char, value: String)
    fun setUltralightPassword(value: String)
    fun setPlusKey(value: String, type: Char)
    fun setLfPassword(value: String)
    fun readMemory()
    fun detectLf(mode: String)
    suspend fun prepareHfWrite(block: Int, value: String): Result<PendingHfWrite>
    suspend fun commitHfWrite(pending: PendingHfWrite): Result<WriteResult>
    suspend fun prepareLfWrite(draft: LfWriteDraft): Result<PendingLfWrite>
    suspend fun commitLfWrite(pending: PendingLfWrite, typedPhrase: String): Result<WriteResult>
    fun beginClone(request: CloneRequest): Result<Unit>
    fun cancelClone()
}

internal class ViewModelReaderActions(
    private val viewModel: ReaderViewModel,
) : ReaderActions {
    override fun refreshDevices() = viewModel.refreshDevices()
    override fun connect(deviceId: Int) = viewModel.connect(deviceId)
    override fun disconnect() = viewModel.disconnect()
    override fun showPage(page: CompactPage) = viewModel.showPage(page)
    override fun clearMessages() = viewModel.clearMessages()
    override fun setClassicKey(type: Char, value: String) = viewModel.setClassicKey(type, value)
    override fun setUltralightPassword(value: String) = viewModel.setUltralightPassword(value)
    override fun setPlusKey(value: String, type: Char) = viewModel.setPlusKey(value, type)
    override fun setLfPassword(value: String) = viewModel.setLfPassword(value)
    override fun readMemory() = viewModel.readMemory()
    override fun detectLf(mode: String) = viewModel.detectLf(mode)
    override suspend fun prepareHfWrite(block: Int, value: String) =
        viewModel.prepareHfWrite(block, value)
    override suspend fun commitHfWrite(pending: PendingHfWrite) = viewModel.commitHfWrite(pending)
    override suspend fun prepareLfWrite(draft: LfWriteDraft) = viewModel.prepareLfWrite(draft)
    override suspend fun commitLfWrite(pending: PendingLfWrite, typedPhrase: String) =
        viewModel.commitLfWrite(pending, typedPhrase)
    override fun beginClone(request: CloneRequest) = viewModel.beginClone(request)
    override fun cancelClone() = viewModel.cancelClone()
}
