package ru.opendev.odrfidmne

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import ru.opendev.odrfidmne.ui.OdrfidApp
import ru.opendev.odrfidmne.ui.OdrfidTheme
import ru.opendev.odrfidmne.ui.ViewModelReaderActions

class MainActivity : ComponentActivity() {
    private val viewModel: ReaderViewModel by viewModels {
        ReaderViewModel.Factory((application as OdrfidApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val log by viewModel.diagnosticText.collectAsStateWithLifecycle()
            val actions = remember(viewModel) { ViewModelReaderActions(viewModel) }
            OdrfidTheme {
                OdrfidApp(state = state, diagnosticLog = log, actions = actions)
            }
        }
        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = intent.usbDevice() ?: return
        viewModel.connect(device.deviceId)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
