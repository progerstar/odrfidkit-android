package ru.opendev.odrfidmne

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.opendev.odrfidmne.data.ReaderRepository
import ru.opendev.odrfidmne.protocol.AtCommandClient
import ru.opendev.odrfidmne.protocol.DiagnosticLog
import ru.opendev.odrfidmne.usb.CdcTransport

internal class AppContainer(application: Application) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val diagnosticLog = DiagnosticLog()
    val transport = CdcTransport(application, applicationScope, diagnosticLog)
    private val client = AtCommandClient(transport, applicationScope, diagnosticLog)
    val repository = ReaderRepository(transport, client, applicationScope)

    init {
        transport.start()
    }
}

class OdrfidApplication : Application() {
    internal lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        container.transport.stop()
        super.onTerminate()
    }
}

