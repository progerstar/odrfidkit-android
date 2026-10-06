package ru.opendev.odrfidmne.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.opendev.odrfidmne.data.LfWriteDraft
import ru.opendev.odrfidmne.data.PendingHfWrite
import ru.opendev.odrfidmne.data.PendingLfWrite
import ru.opendev.odrfidmne.model.CardFamily
import ru.opendev.odrfidmne.model.CardInfo
import ru.opendev.odrfidmne.model.CardTypes
import ru.opendev.odrfidmne.model.CloneRequest
import ru.opendev.odrfidmne.model.CloneStage
import ru.opendev.odrfidmne.model.CompactPage
import ru.opendev.odrfidmne.model.ConnectionState
import ru.opendev.odrfidmne.model.LfClassification
import ru.opendev.odrfidmne.model.MemoryBlock
import ru.opendev.odrfidmne.model.MemoryLayout
import ru.opendev.odrfidmne.model.ReaderDevice
import ru.opendev.odrfidmne.model.ReaderUiState
import ru.opendev.odrfidmne.model.RiskLevel
import ru.opendev.odrfidmne.model.WriteResult
import ru.opendev.odrfidmne.model.WriteRisk

class OdrfidAppTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchesAtExact600DpBreakpointAndSupports1280Dp() {
        var width by mutableStateOf(360.dp)
        val state = stateFor(classicCard()).copy(memoryLayout = classicLayout(), memory = classicMemory())
        setDynamicContent({ width }, { state })

        compose.onNodeWithTag("layout-compact").assertExists()
        compose.onNodeWithTag("nav-card").assertExists()
        compose.onNodeWithTag("memory-list").assertDoesNotExist()

        width = 600.dp
        compose.waitForIdle()
        compose.onNodeWithTag("layout-wide").assertExists()
        compose.onNodeWithTag("nav-card").assertDoesNotExist()
        compose.onNodeWithTag("card-panel").assertExists()
        compose.onNodeWithTag("memory-list").assertExists()

        width = 1280.dp
        compose.waitForIdle()
        compose.onNodeWithTag("layout-wide").assertExists()
        compose.onNodeWithTag("connection-panel").assertExists()
        compose.onNodeWithTag("memory-list").assertExists()
    }

    @Test
    fun showsDisconnectedAndPermissionDeniedUsbStates() {
        var state by mutableStateOf(ReaderUiState(connection = ConnectionState.Disconnected))
        setDynamicContent({ 360.dp }, { state })
        compose.onNodeWithText("USB не подключён").assertExists()

        val device = ReaderDevice(7, "ODRFID #7", false)
        state = state.copy(
            connection = ConnectionState.PermissionDenied(device),
            devices = listOf(device),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Доступ к USB отклонён").assertExists()
        compose.onNodeWithText("Повторить").assertExists()
    }

    @Test
    fun compactMemoryNavigationChangesPane() {
        var state by mutableStateOf(
            stateFor(classicCard()).copy(memoryLayout = classicLayout(), memory = classicMemory()),
        )
        val actions = RecordingActions().apply {
            onPage = { state = state.copy(compactPage = it) }
        }
        setDynamicContent({ 360.dp }, { state }, actions)
        compose.onNodeWithTag("memory-list").assertDoesNotExist()
        compose.onNodeWithTag("nav-memory").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("memory-list").assertExists()
        compose.onNodeWithTag("memory-block-4").assertExists()
    }

    @Test
    fun showsOnlyAuthenticationFieldsRelevantToCardFamily() {
        var state by mutableStateOf(stateFor(classicCard()))
        setDynamicContent({ 1280.dp }, { state })
        compose.onNodeWithTag("key-classic").assertExists()
        compose.onNodeWithTag("key-plus").assertDoesNotExist()

        state = stateFor(card(CardTypes.PLUS_X_2K_SL2, CardFamily.MIFARE_PLUS, 128, 16))
        compose.waitForIdle()
        compose.onNodeWithTag("key-plus").assertExists()
        compose.onNodeWithTag("key-classic").assertDoesNotExist()

        state = stateFor(card(CardTypes.NTAG213, CardFamily.ULTRALIGHT_NTAG, 45, 4))
        compose.waitForIdle()
        compose.onNodeWithTag("key-ultralight").assertExists()
        compose.onNodeWithTag("key-plus").assertDoesNotExist()

        state = stateFor(emCard()).copy(lfClassification = t55Classification())
        compose.waitForIdle()
        compose.onNodeWithTag("key-lf").assertExists()
        compose.onNodeWithText("LFCLASS FAST").assertExists()

        state = stateFor(card(CardTypes.HID_PROX, CardFamily.HID_PROX, 1, 6))
        compose.waitForIdle()
        compose.onNodeWithText("Клонировать UID").assertDoesNotExist()
    }

    @Test
    fun hfEditorShowsOldAndNewAndRequiresExplicitConfirmation() {
        val risk = WriteRisk(
            RiskLevel.WARNING,
            "Пользовательский блок",
            "Одна команда записи и отдельный read-back.",
        )
        val old = "00".repeat(16)
        val new = "11".repeat(16)
        val pending = PendingHfWrite("01020304", CardTypes.CLASSIC_1K, 4, old, new, risk)
        val actions = RecordingActions().apply { preparedHf = Result.success(pending) }
        val state = stateFor(classicCard()).copy(
            compactPage = CompactPage.MEMORY,
            memoryLayout = classicLayout(),
            memory = listOf(MemoryBlock(4, "B4", "Сектор 1", old, true, risk)),
        )
        setContent(360.dp, state, actions)

        compose.onNodeWithTag("memory-block-4").performClick()
        compose.onNodeWithTag("block-editor").assertExists()
        compose.onNodeWithTag("block-hex-input").performTextReplacement(new)
        compose.onNodeWithTag("prepare-write").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Проверка перед единственной записью").assertExists()
        compose.onNodeWithTag("write-old-value").assertExists()
        compose.onNodeWithTag("write-new-value").assertExists()
        compose.onNodeWithTag("commit-write").assertIsNotEnabled()
        compose.onNodeWithTag("confirm-write-checkbox").performScrollTo().performClick()
        compose.onNodeWithTag("commit-write").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, actions.hfCommitCalls)
    }

    @Test
    fun criticalLfEditorRequiresTypedPhraseAndLockControls() {
        val risk = WriteRisk(
            RiskLevel.CRITICAL,
            "Traceability Data",
            "Критическая область.",
            "WRITE P1/B1",
        )
        val draft = LfWriteDraft(1, 1, "DEADBEEF", false, 'N', null)
        val pending = PendingLfWrite(t55Classification(), draft, "01020304", risk, null)
        val actions = RecordingActions().apply { preparedLf = Result.success(pending) }
        val state = stateFor(emCard()).copy(
            compactPage = CompactPage.MEMORY,
            lfClassification = t55Classification(),
            memoryLayout = MemoryLayout(16, 4, List(16) { "P${it / 8}/B${it % 8}" }),
            memory = listOf(MemoryBlock(9, "P1/B1", "Страница 1", "01020304", true, risk)),
        )
        setContent(360.dp, state, actions)

        compose.onNodeWithTag("memory-block-9").performClick()
        compose.onNodeWithTag("lf-password-access").assertExists()
        compose.onNodeWithTag("lf-lock-bit").assertExists()
        compose.onNodeWithTag("block-hex-input").performTextReplacement("DEADBEEF")
        compose.onNodeWithTag("prepare-write").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("confirmation-phrase").performScrollTo().assertExists()
        compose.onNodeWithTag("confirm-write-checkbox").performScrollTo().performClick()
        compose.onNodeWithTag("commit-write").assertIsNotEnabled()
        compose.onNodeWithTag("confirmation-phrase").performTextReplacement("WRITE P1/B1")
        compose.onNodeWithTag("commit-write").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals("WRITE P1/B1", actions.lastLfPhrase)
        assertEquals(1, actions.lfCommitCalls)
    }

    @Test
    fun cloneDialogRequiresMagicTargetAndSwapStagesAreVisible() {
        var state by mutableStateOf(stateFor(classicCard()))
        val actions = RecordingActions()
        setDynamicContent({ 360.dp }, { state }, actions)
        compose.onNodeWithTag("clone-action").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("clone-editor").assertExists()
        compose.onNodeWithTag("begin-clone").assertIsNotEnabled()
        compose.onNodeWithTag("magic-target-confirmation").performClick()
        compose.onNodeWithTag("begin-clone").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(actions.cloneRequest is CloneRequest.Mifare)

        state = state.copy(cloneStage = CloneStage.WAITING_SOURCE_REMOVAL)
        compose.waitForIdle()
        compose.onNodeWithText("Уберите карту-источник").assertExists()
        state = state.copy(card = null, cloneStage = CloneStage.WAITING_TARGET)
        compose.waitForIdle()
        compose.onNodeWithText("Поднесите целевую карту").assertExists()
        state = state.copy(cloneStage = CloneStage.VERIFYING)
        compose.waitForIdle()
        compose.onNodeWithText("Независимая проверка UID").assertExists()
    }

    @Test
    fun diagnosticsRemainBehindOverflowMenu() {
        setContent(360.dp, ReaderUiState(), diagnosticLog = ">> ATI\n<< OK")
        compose.onNodeWithText("Диагностический журнал").assertDoesNotExist()
        compose.onNodeWithTag("overflow-menu").performClick()
        compose.onNodeWithText("Диагностический журнал").performClick()
        compose.onNodeWithTag("diagnostic-log").assertExists()
        compose.onNodeWithText("Копировать лог").assertExists()
    }

    private fun setContent(
        width: Dp,
        state: ReaderUiState,
        actions: RecordingActions = RecordingActions(),
        diagnosticLog: String = "",
    ) = setDynamicContent({ width }, { state }, actions, diagnosticLog)

    private fun setDynamicContent(
        width: () -> Dp,
        state: () -> ReaderUiState,
        actions: RecordingActions = RecordingActions(),
        diagnosticLog: String = "",
    ) {
        compose.setContent {
            OdrfidTheme {
                OdrfidApp(state(), diagnosticLog, actions, windowWidthOverride = width())
            }
        }
    }

    private fun stateFor(card: CardInfo) = ReaderUiState(
        connection = ConnectionState.Connected(ReaderDevice(1, "ODRFID #1", true)),
        firmwareVersion = "3.19m",
        card = card,
    )

    private fun classicCard() = card(CardTypes.CLASSIC_1K, CardFamily.MIFARE_CLASSIC, 64, 16)

    private fun emCard() = CardInfo(
        uidHex = "0102030405",
        sak = 0xFF,
        blockCount = 1,
        blockSize = 5,
        rawType = CardTypes.EM_4100,
        typeName = CardTypes.name(CardTypes.EM_4100),
        family = CardFamily.EM_MARINE,
    )

    private fun card(type: Int, family: CardFamily, blocks: Int, blockSize: Int) = CardInfo(
        uidHex = if (family == CardFamily.HID_PROX) "010203040506" else "01020304",
        sak = 8,
        blockCount = blocks,
        blockSize = blockSize,
        rawType = type,
        typeName = CardTypes.name(type),
        family = family,
    )

    private fun classicLayout() = MemoryLayout(64, 16, List(64) { "B$it" })

    private fun classicMemory(): List<MemoryBlock> {
        val risk = WriteRisk(RiskLevel.WARNING, "Пользовательский блок", "Можно записать")
        return listOf(MemoryBlock(4, "B4", "Сектор 1", "00".repeat(16), true, risk))
    }

    private fun t55Classification() = LfClassification(
        "OK",
        "EM4100_COMPAT",
        "T5577",
        "T55XX_CONFIG",
        "00148040",
        structured = true,
    )
}

private class RecordingActions : ReaderActions {
    var onPage: (CompactPage) -> Unit = {}
    var preparedHf: Result<PendingHfWrite> = Result.failure(IllegalStateException("not configured"))
    var preparedLf: Result<PendingLfWrite> = Result.failure(IllegalStateException("not configured"))
    var hfCommitCalls = 0
    var lfCommitCalls = 0
    var lastLfPhrase: String? = null
    var cloneRequest: CloneRequest? = null

    override fun refreshDevices() = Unit
    override fun connect(deviceId: Int) = Unit
    override fun disconnect() = Unit
    override fun showPage(page: CompactPage) = onPage(page)
    override fun clearMessages() = Unit
    override fun setClassicKey(type: Char, value: String) = Unit
    override fun setUltralightPassword(value: String) = Unit
    override fun setPlusKey(value: String, type: Char) = Unit
    override fun setLfPassword(value: String) = Unit
    override fun readMemory() = Unit
    override fun detectLf(mode: String) = Unit
    override suspend fun prepareHfWrite(block: Int, value: String) = preparedHf
    override suspend fun prepareLfWrite(draft: LfWriteDraft) = preparedLf
    override suspend fun commitHfWrite(pending: PendingHfWrite): Result<WriteResult> {
        hfCommitCalls++
        return Result.success(WriteResult(pending.block, "OK", pending.oldHex, pending.newHex, pending.newHex, true))
    }
    override suspend fun commitLfWrite(pending: PendingLfWrite, typedPhrase: String): Result<WriteResult> {
        lfCommitCalls++
        lastLfPhrase = typedPhrase
        return Result.success(
            WriteResult(
                pending.draft.page * 8 + pending.draft.block,
                "OK",
                pending.oldHex,
                pending.draft.dataHex,
                pending.draft.dataHex,
                true,
            ),
        )
    }
    override fun beginClone(request: CloneRequest): Result<Unit> {
        cloneRequest = request
        return Result.success(Unit)
    }
    override fun cancelClone() = Unit
}
