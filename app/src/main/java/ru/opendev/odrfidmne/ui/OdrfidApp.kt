package ru.opendev.odrfidmne.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
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
import ru.opendev.odrfidmne.model.EmCoding
import ru.opendev.odrfidmne.model.EmSpeed
import ru.opendev.odrfidmne.model.LfClassification
import ru.opendev.odrfidmne.model.MemoryBlock
import ru.opendev.odrfidmne.model.ReaderUiState
import ru.opendev.odrfidmne.model.RiskLevel
import ru.opendev.odrfidmne.model.WriteRisk
import ru.opendev.odrfidmne.model.formatLfIdentifiers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OdrfidApp(
    state: ReaderUiState,
    diagnosticLog: String,
    actions: ReaderActions,
    windowWidthOverride: Dp? = null,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var selectedBlock by remember { mutableStateOf<MemoryBlock?>(null) }
    var showClone by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = (windowWidthOverride ?: maxWidth) >= 600.dp
        Scaffold(
            modifier = Modifier.testTag(if (wide) "layout-wide" else "layout-compact"),
            topBar = {
                TopAppBar(
                    title = { Text("ODRFID MNE") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    actions = {
                        Box {
                            TextButton(
                                onClick = { overflowOpen = true },
                                modifier = Modifier.testTag("overflow-menu"),
                            ) { Text("⋮", style = MaterialTheme.typography.headlineSmall) }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Диагностический журнал") },
                                    onClick = {
                                        overflowOpen = false
                                        showDiagnostics = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Обновить список USB") },
                                    onClick = {
                                        overflowOpen = false
                                        actions.refreshDevices()
                                    },
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = state.compactPage == CompactPage.CARD,
                            onClick = { actions.showPage(CompactPage.CARD) },
                            icon = { Text("◉") },
                            label = { Text("Карта") },
                            modifier = Modifier.testTag("nav-card"),
                        )
                        NavigationBarItem(
                            selected = state.compactPage == CompactPage.MEMORY,
                            onClick = { actions.showPage(CompactPage.MEMORY) },
                            icon = { Text("▦") },
                            label = { Text("Память") },
                            modifier = Modifier.testTag("nav-memory"),
                        )
                    }
                }
            },
        ) { padding ->
            if (wide) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    CardPane(
                        state = state,
                        actions = actions,
                        onChooseDevice = { showDevices = true },
                        onClone = { showClone = true },
                        modifier = Modifier
                            .widthIn(min = 320.dp, max = 400.dp)
                            .fillMaxHeight(),
                    )
                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                    MemoryPane(
                        state = state,
                        onBlock = { selectedBlock = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (state.compactPage == CompactPage.CARD) {
                        CardPane(
                            state = state,
                            actions = actions,
                            onChooseDevice = { showDevices = true },
                            onClone = { showClone = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        MemoryPane(
                            state = state,
                            onBlock = { selectedBlock = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        selectedBlock?.let { block ->
            BlockEditor(
                block = block,
                state = state,
                wide = wide,
                actions = actions,
                onDismiss = { selectedBlock = null },
            )
        }
        if (showClone) {
            CloneEditor(
                card = state.card,
                wide = wide,
                actions = actions,
                onDismiss = { showClone = false },
            )
        }
    }

    if (showDevices) {
        DeviceDialog(state, actions) { showDevices = false }
    }
    if (showDiagnostics) {
        DiagnosticDialog(diagnosticLog) { showDiagnostics = false }
    }
}

@Composable
private fun CardPane(
    state: ReaderUiState,
    actions: ReaderActions,
    onChooseDevice: () -> Unit,
    onClone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConnectionPanel(state, actions, onChooseDevice)
        StatusMessages(state, actions)
        state.card?.let { CardPanel(it) }
        CloneStatus(state, actions)
        AuthenticationPanel(state, actions)
        ActionPanel(state, actions, onClone)
        if (state.busy) OperationProgress(state)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ConnectionPanel(
    state: ReaderUiState,
    actions: ReaderActions,
    onChooseDevice: () -> Unit,
) {
    val (title, detail) = when (val connection = state.connection) {
        ConnectionState.Disconnected -> "USB не подключён" to "Подключите считыватель ODRFID"
        ConnectionState.Searching -> "Выберите считыватель" to "Найдено устройств: ${state.devices.size}"
        is ConnectionState.PermissionRequired -> "Нужен доступ к USB" to connection.device.displayName
        is ConnectionState.PermissionDenied -> "Доступ к USB отклонён" to connection.device.displayName
        is ConnectionState.Connecting -> "Подключение…" to connection.device.displayName
        is ConnectionState.Connected -> "CDC подключён" to connection.device.displayName
        is ConnectionState.Error -> "Ошибка соединения" to connection.message
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("connection-panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            state.firmwareVersion?.let { Text("Прошивка: $it", style = MaterialTheme.typography.labelLarge) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val connection = state.connection) {
                    is ConnectionState.Connected -> OutlinedButton(
                        onClick = actions::disconnect,
                        enabled = !state.busy,
                    ) { Text("Отключить") }
                    is ConnectionState.PermissionRequired -> Button(
                        onClick = { actions.connect(connection.device.deviceId) },
                    ) { Text("Разрешить") }
                    is ConnectionState.PermissionDenied -> Button(
                        onClick = { actions.connect(connection.device.deviceId) },
                    ) { Text("Повторить") }
                    else -> {
                        if (state.devices.size == 1) {
                            Button(onClick = { actions.connect(state.devices.single().deviceId) }) {
                                Text("Подключить")
                            }
                        } else {
                            Button(onClick = onChooseDevice, enabled = state.devices.isNotEmpty()) {
                                Text("Выбрать USB")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMessages(state: ReaderUiState, actions: ReaderActions) {
    state.errorMessage?.let {
        MessageCard(
            text = it,
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            onDismiss = actions::clearMessages,
            tag = "error-message",
        )
    }
    state.infoMessage?.let {
        MessageCard(
            text = it,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            onDismiss = actions::clearMessages,
            tag = "info-message",
        )
    }
}

@Composable
private fun MessageCard(
    text: String,
    container: Color,
    content: Color,
    onDismiss: () -> Unit,
    tag: String,
) {
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(
            Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, color = content, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Закрыть", color = content) }
        }
    }
}

@Composable
private fun CardPanel(card: CardInfo) {
    val context = LocalContext.current
    var expanded by rememberSaveable(card.uidHex) { mutableStateOf(false) }
    val lf = formatLfIdentifiers(card)
    OutlinedCard(Modifier.fillMaxWidth().testTag("card-panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Карта", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(card.typeName, style = MaterialTheme.typography.bodyLarge)
            Text("UID HEX", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.uidHex.chunked(2).joinToString(" "),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).testTag("card-uid"),
                )
                TextButton(onClick = { copyText(context, "UID", card.uidHex) }) { Text("Копировать") }
            }
            if (lf != null) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("lf-details-toggle")) {
                    Text(if (expanded) "Скрыть подробности" else "Decimal и Wiegand")
                }
                if (expanded) {
                    KeyValue("Decimal", lf.decimal)
                    KeyValue("Wiegand 26", lf.wiegand)
                }
            }
        }
    }
}

@Composable
private fun AuthenticationPanel(state: ReaderUiState, actions: ReaderActions) {
    val card = state.card ?: return
    when {
        CardTypes.usesAesBlockCommands(card.rawType) -> PlusKeyPanel(state, actions)
        CardTypes.isClassicCompatible(card.rawType) -> ClassicKeyPanel(state, actions)
        CardTypes.needsUltralightPassword(card.rawType) -> UltralightPasswordPanel(state, actions)
    }
    if (state.lfClassification?.family == CardFamily.LF_T55XX) {
        LfPasswordPanel(state, actions)
    }
}

@Composable
private fun ClassicKeyPanel(state: ReaderUiState, actions: ReaderActions) {
    var keyA by rememberSaveable { mutableStateOf(state.keys.classicKeyA) }
    var keyB by rememberSaveable { mutableStateOf(state.keys.classicKeyB) }
    AuthCard("Ключи MIFARE Classic", "key-classic") {
        SecretField("Key A · 12 HEX", keyA, { keyA = it.hexInput() })
        Button(onClick = { actions.setClassicKey('A', keyA) }, enabled = keyA.length == 12 && !state.busy) {
            Text("Применить A")
        }
        SecretField("Key B · 12 HEX", keyB, { keyB = it.hexInput() })
        Button(onClick = { actions.setClassicKey('B', keyB) }, enabled = keyB.length == 12 && !state.busy) {
            Text("Применить B")
        }
    }
}

@Composable
private fun UltralightPasswordPanel(state: ReaderUiState, actions: ReaderActions) {
    var password by rememberSaveable { mutableStateOf(state.keys.ultralightPassword) }
    AuthCard("Пароль Ultralight / NTAG", "key-ultralight") {
        SecretField("PWD · 8 HEX", password, { password = it.hexInput() })
        Button(
            onClick = { actions.setUltralightPassword(password) },
            enabled = password.length == 8 && !state.busy,
        ) { Text("Применить") }
    }
}

@Composable
private fun PlusKeyPanel(state: ReaderUiState, actions: ReaderActions) {
    var key by rememberSaveable { mutableStateOf(state.keys.plusAesKey) }
    var type by rememberSaveable { mutableStateOf(state.keys.plusKeyType) }
    AuthCard("AES-ключ MIFARE Plus", "key-plus") {
        SecretField("AES-128 · 32 HEX", key, { key = it.hexInput() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == 'A', onClick = { type = 'A' }, label = { Text("Key A") })
            FilterChip(selected = type == 'B', onClick = { type = 'B' }, label = { Text("Key B") })
        }
        Button(onClick = { actions.setPlusKey(key, type) }, enabled = key.length == 32 && !state.busy) {
            Text("Применить")
        }
    }
}

@Composable
private fun LfPasswordPanel(state: ReaderUiState, actions: ReaderActions) {
    var password by rememberSaveable { mutableStateOf(state.keys.lfPassword) }
    AuthCard("Сеансовый пароль T55xx", "key-lf") {
        SecretField("Пароль · 8 HEX", password, {
            password = it.hexInput()
            if (password.isEmpty() || password.length == 8) actions.setLfPassword(password)
        })
        Text("Хранится только в памяти процесса", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AuthCard(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            },
        )
    }
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.Characters,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionPanel(state: ReaderUiState, actions: ReaderActions, onClone: () -> Unit) {
    val card = state.card ?: return
    OutlinedCard(Modifier.fillMaxWidth().testTag("action-panel")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Действия", style = MaterialTheme.typography.titleMedium)
            if (card.supportsMemory || state.lfClassification != null) {
                Button(onClick = actions::readMemory, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Прочитать память")
                }
            }
            if (card.isLowFrequency) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { actions.detectLf("FAST") }, enabled = !state.busy) {
                        Text("LFCLASS FAST")
                    }
                    OutlinedButton(onClick = { actions.detectLf("FULL") }, enabled = !state.busy) {
                        Text("FULL")
                    }
                }
                state.lfClassification?.let { classification ->
                    Text(
                        "${classification.chip} · ${if (classification.structured) "LFCLASS" else "LFINFO read-only"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val cloneAllowed = card.family in setOf(
                CardFamily.MIFARE_CLASSIC,
                CardFamily.MIFARE_PLUS,
                CardFamily.EM_MARINE,
            )
            if (cloneAllowed && state.cloneStage == CloneStage.IDLE) {
                OutlinedButton(
                    onClick = onClone,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("clone-action"),
                ) {
                    Text("Клонировать UID")
                }
            }
        }
    }
}

@Composable
private fun CloneStatus(state: ReaderUiState, actions: ReaderActions) {
    if (state.cloneStage == CloneStage.IDLE) return
    val text = when (state.cloneStage) {
        CloneStage.WAITING_SOURCE_REMOVAL -> "Уберите карту-источник"
        CloneStage.WAITING_TARGET -> "Поднесите целевую карту"
        CloneStage.WRITING -> "Цель обнаружена, выполняется одна запись"
        CloneStage.VERIFYING -> "Независимая проверка UID"
        CloneStage.COMPLETE -> "UID цели подтверждён"
        CloneStage.FAILED -> "Клонирование не подтверждено"
        CloneStage.IDLE -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("clone-status"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Клонирование", style = MaterialTheme.typography.titleMedium)
            Text(text)
            if (state.cloneStage !in setOf(CloneStage.COMPLETE, CloneStage.FAILED)) {
                TextButton(onClick = actions::cancelClone) { Text("Отменить") }
            }
        }
    }
}

@Composable
private fun OperationProgress(state: ReaderUiState) {
    Card(Modifier.fillMaxWidth().testTag("operation-progress")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.operationLabel ?: "Операция")
            if (state.progressTotal > 0) {
                LinearProgressIndicator(
                    progress = { state.progressCurrent.toFloat() / state.progressTotal.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${state.progressCurrent} / ${state.progressTotal}", style = MaterialTheme.typography.bodySmall)
            } else {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun MemoryPane(
    state: ReaderUiState,
    onBlock: (MemoryBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Память", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            state.memoryLayout?.let { Text("${it.blockCount} × ${it.blockSize} Б", style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(Modifier.height(12.dp))
        if (state.memory.isEmpty()) {
            Box(Modifier.fillMaxSize().testTag("memory-empty"), contentAlignment = Alignment.Center) {
                Text(
                    if (state.card == null) "Поднесите карту" else "Для этой карты память не открыта",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().testTag("memory-list"),
            ) {
                itemsIndexed(state.memory, key = { _, item -> item.index }) { index, block ->
                    if (block.groupLabel != null &&
                        (index == 0 || state.memory[index - 1].groupLabel != block.groupLabel)
                    ) {
                        Text(
                            block.groupLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                        )
                    }
                    MemoryRow(block, onBlock)
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(block: MemoryBlock, onBlock: (MemoryBlock) -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onBlock(block) }
            .testTag("memory-block-${block.index}"),
        border = BorderStroke(
            1.dp,
            if (block.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(block.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    block.dataHex?.chunked(2)?.joinToString(" ") ?: if (block.error == null) "—" else "Ошибка чтения",
                    fontFamily = FontFamily.Monospace,
                    color = if (block.error != null) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                if (block.dataHex != null) {
                    Text(block.asciiPreview, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(if (block.writable) "✎" else "🔒", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockEditor(
    block: MemoryBlock,
    state: ReaderUiState,
    wide: Boolean,
    actions: ReaderActions,
    onDismiss: () -> Unit,
) {
    var value by remember(block.index, block.dataHex) { mutableStateOf(block.dataHex.orEmpty()) }
    var lock by remember(block.index) { mutableStateOf(false) }
    var passwordAccess by remember(block.index) { mutableStateOf(state.keys.lfPassword.isNotBlank()) }
    var pendingHf by remember(block.index) { mutableStateOf<PendingHfWrite?>(null) }
    var pendingLf by remember(block.index) { mutableStateOf<PendingLfWrite?>(null) }
    var understood by remember(block.index) { mutableStateOf(false) }
    var phrase by remember(block.index) { mutableStateOf("") }
    var localError by remember(block.index) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lfT55 = state.lfClassification?.family == CardFamily.LF_T55XX
    val expectedLength = (state.memoryLayout?.blockSize ?: 0) * 2
    val pendingRisk = pendingHf?.risk ?: pendingLf?.risk
    val oldHex = pendingHf?.oldHex ?: pendingLf?.oldHex
    val newHex = pendingHf?.newHex ?: pendingLf?.draft?.dataHex

    val content: @Composable ColumnScope.() -> Unit = {
        Text(block.label, style = MaterialTheme.typography.headlineSmall)
        Text(block.risk?.title ?: "Блок памяти", style = MaterialTheme.typography.titleMedium)
        block.risk?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.hexInput().take(expectedLength) },
            label = { Text("HEX · $expectedLength символов") },
            readOnly = !block.writable || pendingRisk != null,
            singleLine = false,
            minLines = 2,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.Characters,
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().testTag("block-hex-input"),
        )
        if (lfT55 && block.writable && pendingRisk == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = passwordAccess,
                    onCheckedChange = { passwordAccess = it },
                    modifier = Modifier.testTag("lf-password-access"),
                )
                Text("Текущий password access")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = lock,
                    onCheckedChange = { lock = it },
                    modifier = Modifier.testTag("lf-lock-bit"),
                )
                Text("Необратимо установить lock-bit")
            }
        }
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("editor-error")) }
        if (pendingRisk != null && oldHex != null && newHex != null) {
            HorizontalDivider()
            Text("Проверка перед единственной записью", fontWeight = FontWeight.SemiBold)
            KeyValue("Было", oldHex, Modifier.testTag("write-old-value"))
            KeyValue("Будет", newHex, Modifier.testTag("write-new-value"))
            RiskNotice(pendingRisk)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = understood,
                    onCheckedChange = { understood = it },
                    modifier = Modifier.testTag("confirm-write-checkbox"),
                )
                Text("Понимаю риск и подтверждаю одну попытку")
            }
            if (pendingRisk.confirmationPhrase.isNotEmpty()) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("Введите ${pendingRisk.confirmationPhrase}") },
                    modifier = Modifier.fillMaxWidth().testTag("confirmation-phrase"),
                )
            }
            Button(
                enabled = understood &&
                    (pendingRisk.confirmationPhrase.isEmpty() || phrase == pendingRisk.confirmationPhrase) &&
                    !state.busy,
                onClick = {
                    scope.launch {
                        val result = pendingHf?.let { actions.commitHfWrite(it) }
                            ?: pendingLf?.let { actions.commitLfWrite(it, phrase) }
                        result?.onSuccess { onDismiss() }
                            ?.onFailure { localError = it.message }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("commit-write"),
            ) { Text("Записать один раз и проверить") }
        } else if (block.writable) {
            Button(
                onClick = {
                    scope.launch {
                        localError = null
                        if (lfT55) {
                            val draft = LfWriteDraft(
                                page = block.index / 8,
                                block = block.index % 8,
                                dataHex = value,
                                lock = lock,
                                access = if (passwordAccess) 'P' else 'N',
                                password = state.keys.lfPassword.takeIf(String::isNotBlank),
                            )
                            actions.prepareLfWrite(draft)
                                .onSuccess { pendingLf = it }
                                .onFailure { localError = it.message }
                        } else {
                            actions.prepareHfWrite(block.index, value)
                                .onSuccess { pendingHf = it }
                                .onFailure { localError = it.message }
                        }
                    }
                },
                enabled = value.length == expectedLength && !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("prepare-write"),
            ) { Text("Перечитать и подготовить запись") }
        } else {
            Text("Эта область доступна только для чтения", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
    }

    if (wide) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).imePadding().testTag("block-editor"),
            ) {
                Column(
                    Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("block-editor")) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RiskNotice(risk: WriteRisk) {
    val color = when (risk.level) {
        RiskLevel.CRITICAL, RiskLevel.BLOCKED -> MaterialTheme.colorScheme.errorContainer
        RiskLevel.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        RiskLevel.SAFE -> MaterialTheme.colorScheme.secondaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(risk.title, fontWeight = FontWeight.SemiBold)
            Text(risk.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloneEditor(
    card: CardInfo?,
    wide: Boolean,
    actions: ReaderActions,
    onDismiss: () -> Unit,
) {
    if (card == null) return
    var magicConfirmed by remember { mutableStateOf(false) }
    var coding by remember { mutableStateOf(EmCoding.MANCHESTER) }
    var speed by remember { mutableStateOf(EmSpeed.RF_64) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val em = card.family == CardFamily.EM_MARINE

    val content: @Composable ColumnScope.() -> Unit = {
        Text("Клонирование UID", style = MaterialTheme.typography.headlineSmall)
        Text("Источник зафиксирован: ${card.uidHex}", fontFamily = FontFamily.Monospace)
        Text("После запуска уберите источник и только затем поднесите цель.")
        if (em) {
            Text("Цель должна быть подтверждённой T5577/T55xx; EM4x05/EM4x50 остаются read-only.")
            Text("Кодирование", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmCoding.entries.forEach { item ->
                    FilterChip(selected = coding == item, onClick = { coding = item }, label = { Text(item.title) })
                }
            }
            Text("Скорость", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmSpeed.entries.forEach { item ->
                    FilterChip(selected = speed == item, onClick = { speed = item }, label = { Text(item.title) })
                }
            }
            SecretField("Текущий пароль · необязательно", currentPassword, {
                currentPassword = it.hexInput().take(8)
            })
            SecretField("Новый пароль · необязательно", newPassword, {
                newPassword = it.hexInput().take(8)
            })
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = magicConfirmed,
                        onCheckedChange = { magicConfirmed = it },
                        modifier = Modifier.testTag("magic-target-confirmation"),
                    )
                    Text("Цель — сменная magic-карта с поддержкой записи UID")
                }
            }
        }
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val request = if (em) {
                    CloneRequest.EmMarine(
                        sourceUidHex = card.uidHex,
                        coding = coding,
                        speed = speed,
                        currentPassword = currentPassword.takeIf(String::isNotBlank),
                        newPassword = newPassword.takeIf(String::isNotBlank),
                    )
                } else {
                    CloneRequest.Mifare(card.uidHex, magicConfirmed)
                }
                actions.beginClone(request)
                    .onSuccess { onDismiss() }
                    .onFailure { localError = it.message }
            },
            enabled = if (em) {
                (currentPassword.isEmpty() || currentPassword.length == 8) &&
                    (newPassword.isEmpty() || newPassword.length == 8)
            } else magicConfirmed,
            modifier = Modifier.fillMaxWidth().testTag("begin-clone"),
        ) { Text("Зафиксировать источник и начать") }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Отмена") }
    }

    if (wide) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                modifier = Modifier.testTag("clone-editor"),
            ) {
                Column(
                    Modifier.padding(24.dp).widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("clone-editor")) {
            Column(
                Modifier
                    .padding(20.dp)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .testTag("clone-editor-content"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun DeviceDialog(state: ReaderUiState, actions: ReaderActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Считыватели ODRFID") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.devices.forEach { device ->
                    OutlinedButton(
                        onClick = {
                            actions.connect(device.deviceId)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${device.displayName}${if (device.hasPermission) " · доступ разрешён" else ""}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun DiagnosticDialog(log: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Диагностический журнал") },
        text = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    log.ifBlank { "Журнал пуст" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 460.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                        .testTag("diagnostic-log"),
                )
            }
        },
        confirmButton = {
            Button(onClick = { copyText(context, "ODRFID log", log) }) { Text("Копировать лог") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun KeyValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(92.dp))
        Text(value, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

private fun String.hexInput(): String = filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.uppercase()

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
