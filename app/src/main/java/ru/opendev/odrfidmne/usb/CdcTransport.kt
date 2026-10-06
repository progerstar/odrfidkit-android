package ru.opendev.odrfidmne.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.opendev.odrfidmne.model.ConnectionState
import ru.opendev.odrfidmne.model.ODRFID_PRODUCT_ID
import ru.opendev.odrfidmne.model.ODRFID_VENDOR_ID
import ru.opendev.odrfidmne.model.ReaderDevice
import ru.opendev.odrfidmne.protocol.DiagnosticLog
import ru.opendev.odrfidmne.protocol.SerialByteTransport

internal class CdcTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val diagnosticLog: DiagnosticLog,
) : SerialByteTransport {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mutex = Mutex()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _devices = MutableStateFlow<List<ReaderDevice>>(emptyList())
    val devices: StateFlow<List<ReaderDevice>> = _devices.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingBytes: SharedFlow<ByteArray> = _incomingBytes.asSharedFlow()

    private var port: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var readJob: Job? = null
    private var currentDeviceId: Int? = null
    private var receiversRegistered = false

    override val isOpen: Boolean
        get() = port?.isOpen == true && _connection.value is ConnectionState.Connected

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != USB_PERMISSION_ACTION) return
            val device = intent.usbDevice() ?: return
            if (!device.isOdrfidReader()) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            refreshDevices()
            if (granted) {
                scope.launch { connect(device.deviceId, requestPermission = false) }
            } else {
                _connection.value = ConnectionState.PermissionDenied(device.toReaderDevice(false))
            }
        }
    }

    private val attachDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!device.isOdrfidReader()) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDevices()
                    val matching = matchingDevices()
                    if (currentDeviceId == null && matching.size == 1 && usbManager.hasPermission(device)) {
                        scope.launch { connect(device.deviceId, requestPermission = false) }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDevices()
                    if (device.deviceId == currentDeviceId) {
                        scope.launch { close("Считыватель отключён от USB") }
                    }
                }
            }
        }
    }

    fun start() {
        if (!receiversRegistered) {
            registerReceiver(permissionReceiver, IntentFilter(USB_PERMISSION_ACTION), exported = false)
            registerReceiver(
                attachDetachReceiver,
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                },
                exported = true,
            )
            receiversRegistered = true
        }
        refreshDevices()
        val matching = matchingDevices()
        when {
            matching.isEmpty() -> _connection.value = ConnectionState.Disconnected
            matching.size == 1 && usbManager.hasPermission(matching.single()) ->
                scope.launch { connect(matching.single().deviceId, requestPermission = false) }
            matching.size == 1 ->
                _connection.value = ConnectionState.PermissionRequired(matching.single().toReaderDevice(false))
            else -> _connection.value = ConnectionState.Searching
        }
    }

    fun refreshDevices() {
        _devices.value = matchingDevices()
            .sortedBy(UsbDevice::getDeviceId)
            .map { it.toReaderDevice(usbManager.hasPermission(it)) }
    }

    suspend fun connect(deviceId: Int, requestPermission: Boolean = true) {
        mutex.withLock {
            val device = matchingDevices().firstOrNull { it.deviceId == deviceId }
            if (device == null) {
                closeLocked("Выбранный считыватель больше не подключён")
                return
            }
            val descriptor = device.toReaderDevice(usbManager.hasPermission(device))
            if (!usbManager.hasPermission(device)) {
                _connection.value = ConnectionState.PermissionRequired(descriptor)
                if (requestPermission) requestPermission(device)
                return
            }
            if (isOpen && currentDeviceId == deviceId) return

            closeLocked(null)
            _connection.value = ConnectionState.Connecting(descriptor.copy(hasPermission = true))
            diagnosticLog.event("Открытие CDC ${descriptor.displayName}")
            try {
                validateCdcInterfaces(device)
                val driver = CdcAcmSerialDriver(device)
                val selectedPort = driver.ports.firstOrNull()
                    ?: throw IOException("CDC ACM порт не найден")
                val selectedConnection = usbManager.openDevice(device)
                    ?: throw IOException("Android не открыл USB-устройство")

                try {
                    withContext(Dispatchers.IO) {
                        selectedPort.open(selectedConnection)
                        selectedPort.setParameters(
                            BAUD_RATE,
                            UsbSerialPort.DATABITS_8,
                            UsbSerialPort.STOPBITS_1,
                            UsbSerialPort.PARITY_NONE,
                        )
                        selectedPort.setFlowControl(UsbSerialPort.FlowControl.NONE)
                    }
                } catch (error: Throwable) {
                    runCatching { selectedPort.close() }
                    selectedConnection.close()
                    throw error
                }

                port = selectedPort
                usbConnection = selectedConnection
                currentDeviceId = deviceId
                _connection.value = ConnectionState.Connected(descriptor.copy(hasPermission = true))
                startReadLoop(selectedPort)
            } catch (error: Throwable) {
                closeLocked(error.message ?: "Не удалось открыть CDC")
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        val activePort = port ?: throw IOException("CDC порт не открыт")
        withContext(Dispatchers.IO) {
            activePort.write(data, WRITE_TIMEOUT)
        }
    }

    override suspend fun flushInput() {
        // CDC ACM has no hardware purge command. CdcAcmSerialPort inherits a
        // purgeHwBuffers implementation that throws UnsupportedOperationException.
        // AtCommandClient resets its CRLF framer and queued responses; the initial
        // CR and settling delay in the handshake precede a second software reset.
    }

    override suspend fun close(reason: String?) {
        mutex.withLock { closeLocked(reason) }
    }

    fun stop() {
        if (receiversRegistered) {
            runCatching { appContext.unregisterReceiver(permissionReceiver) }
            runCatching { appContext.unregisterReceiver(attachDetachReceiver) }
            receiversRegistered = false
        }
        scope.launch { close(null) }
    }

    private fun requestPermission(device: UsbDevice) {
        val intent = Intent(USB_PERMISSION_ACTION).setPackage(appContext.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            device.deviceId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun startReadLoop(activePort: UsbSerialPort) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            try {
                while (currentCoroutineContext().isActive && activePort.isOpen) {
                    val count = activePort.read(buffer, READ_TIMEOUT)
                    if (count > 0) _incomingBytes.emit(buffer.copyOf(count))
                }
            } catch (error: Throwable) {
                if (currentCoroutineContext().isActive) {
                    scope.launch { close("Ошибка чтения CDC: ${error.message}") }
                }
            }
        }
    }

    private suspend fun closeLocked(reason: String?) {
        readJob?.cancel()
        readJob = null
        val oldPort = port
        val oldConnection = usbConnection
        port = null
        usbConnection = null
        currentDeviceId = null
        withContext(Dispatchers.IO) {
            runCatching { oldPort?.close() }
            runCatching { oldConnection?.close() }
        }
        if (reason.isNullOrBlank()) {
            _connection.value = ConnectionState.Disconnected
        } else {
            diagnosticLog.event(reason)
            _connection.value = ConnectionState.Error(reason)
        }
    }

    private fun validateCdcInterfaces(device: UsbDevice) {
        var communication = false
        var data = false
        repeat(device.interfaceCount) { index ->
            when (device.getInterface(index).interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> communication = true
                UsbConstants.USB_CLASS_CDC_DATA -> data = true
            }
        }
        if (!communication || !data || !CdcAcmSerialDriver.probe(device)) {
            throw IOException("Устройство не содержит совместимую пару CDC ACM интерфейсов")
        }
    }

    private fun matchingDevices(): List<UsbDevice> = usbManager.deviceList.values
        .filter { it.isOdrfidReader() }

    private fun UsbDevice.isOdrfidReader(): Boolean =
        vendorId == ODRFID_VENDOR_ID && productId == ODRFID_PRODUCT_ID

    private fun UsbDevice.toReaderDevice(permission: Boolean): ReaderDevice {
        val label = runCatching { productName }.getOrNull()?.takeIf(String::isNotBlank)
            ?: "ODRFID MNE"
        return ReaderDevice(deviceId, "$label · #$deviceId", permission)
    }

    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter, exported: Boolean) {
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            if (exported) ContextCompat.RECEIVER_EXPORTED else ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private companion object {
        const val USB_PERMISSION_ACTION = "ru.opendev.odrfidmne.USB_PERMISSION"
        const val BAUD_RATE = 115_200
        const val READ_BUFFER_SIZE = 8 * 1024
        const val READ_TIMEOUT = 250
        const val WRITE_TIMEOUT = 1_000
    }
}
