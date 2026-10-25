package com.hexo141.liquidglass.vivo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hexo141.liquidglass.adb.AdbCredentials
import com.hexo141.liquidglass.adb.UsbAdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Everything the vivo page renders. Kept in one snapshot object so updates are atomic. */
data class VivoUiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val target: VivoTarget? = null,
    val deviceSummary: String = "",
    val status: String = "No target",
    val payloads: List<VivoPayload> = emptyList(),
    val selectedPayload: VivoPayload? = null,
    val localName: String? = null,
    val localUri: Uri? = null,
    val running: Boolean = false,
    val stage: VivoStage = VivoStage.Idle,
    val transferProgress: Int? = null,
    val success: Boolean = false,
)

/**
 * USB-OTG controller for the vivo/iQOO wired flow.
 *
 * The app is the USB host: it owns the target's adbd transport, so it must request USB permission,
 * read the target identity, then hand the same transport to [VivoWiredRunner] and rebuild it after
 * the target reboots.
 */
class VivoWiredController(
    context: Context,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
    private val onStateChanged: (VivoUiState) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val credentials = AdbCredentials(appContext, "openroot")
    private val session = VivoUsbSession()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var permissionWaiter: CompletableDeferred<Boolean>? = null
    private var connectJob: Job? = null
    private var workJob: Job? = null
    private var lastUsbPermissionKey: String? = null
    private var lastUsbPermissionAt = 0L

    @Volatile
    private var stopRequested = false

    var state by mutableStateOf(VivoUiState())
        private set

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            permissionWaiter?.complete(
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
            )
        }
    }

    init {
        registerPermissionReceiver()
        val payloads = runCatching { VivoPayloadCatalog.load(appContext) }
            .onFailure { appendLog("Payload catalog failed: ${it.message}") }
            .getOrDefault(emptyList())
        state = state.copy(payloads = payloads)
    }

    private fun registerPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                permissionReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(permissionReceiver, filter)
        }
    }

    /** Finds and connects the attached USB ADB target, then reads its model/kernel. */
    fun connect(force: Boolean = false) {
        if (state.running || state.connecting) return
        if (!force && state.connected && session.client != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            setStatus("USB host transport requires Android 8.0 or newer")
            return
        }
        stopRequested = false
        connectJob?.cancel()
        connectJob = scope.launch {
            session.close()
            updateState { it.copy(connecting = true, connected = false, status = "Searching", success = false) }
            try {
                val client = openClient(waitingAfterReboot = false)
                val target = withContext(Dispatchers.IO) { readTarget(client) }
                session.client = client
                updateState {
                    it.copy(
                        connecting = false,
                        connected = true,
                        target = target,
                        deviceSummary = client.connectionSummary(),
                        status = "Ready",
                    )
                }
                appendLog("Connected: ${target.identity}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                session.close()
                updateState {
                    it.copy(
                        connecting = false,
                        connected = false,
                        target = null,
                        status = error.message?.takeIf(String::isNotBlank)
                            ?: "USB ADB connection failed",
                    )
                }
                appendLog("Connect failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun selectPayload(payload: VivoPayload) {
        if (state.running) return
        updateState {
            it.copy(
                selectedPayload = payload,
                localName = null,
                localUri = null,
                success = false,
            )
        }
    }

    fun selectLocal(uri: Uri, name: String) {
        if (state.running) return
        updateState {
            it.copy(
                selectedPayload = null,
                localName = name,
                localUri = uri,
                success = false,
            )
        }
    }

    fun start() {
        if (state.running) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            setStatus("USB host transport requires Android 8.0 or newer")
            return
        }
        val target = state.target
        if (!state.connected || target == null || session.client == null) {
            setStatus("Connect the target first")
            return
        }
        val payload = state.selectedPayload
        val localUri = state.localUri
        if (payload == null && localUri == null) {
            setStatus("Select a payload first")
            return
        }
        stopRequested = false
        workJob?.cancel()
        workJob = scope.launch {
            var localFile: File? = null
            updateState {
                it.copy(
                    running = true,
                    stage = VivoStage.Connecting,
                    status = "Preparing",
                    transferProgress = null,
                    success = false,
                )
            }
            try {
                if (localUri != null) {
                    updateState { it.copy(status = "Reading payload") }
                    localFile = withContext(Dispatchers.IO) { copyLocalPayload(localUri) }
                    appendLog(
                        "Local payload: ${localFile.name}",
                    )
                }
                val runner = VivoWiredRunner(
                    context = appContext,
                    session = session,
                    connectTarget = { waiting -> openClient(waiting) },
                    publish = { update ->
                        if (!stopRequested) {
                            updateState {
                                it.copy(
                                    stage = update.stage,
                                    status = update.status,
                                    transferProgress = update.transferProgress,
                                    success = update.success,
                                )
                            }
                        }
                    },
                    log = { message -> if (!stopRequested) appendLog(message) },
                )
                withContext(Dispatchers.IO) {
                    runner.run(target, payload, localFile)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!stopRequested) {
                    val reason = error.message?.takeIf(String::isNotBlank)
                        ?: error.javaClass.simpleName
                    updateState {
                        it.copy(
                            running = false,
                            connected = false,
                            stage = VivoStage.Failed,
                            status = reason,
                        )
                    }
                    appendLog("Failed: $reason")
                }
            } finally {
                localFile?.delete()
                if (!stopRequested) {
                    updateState {
                        it.copy(
                            running = false,
                            connected = session.client != null,
                            transferProgress = null,
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        if (!state.running && !state.connecting) return
        stopRequested = true
        connectJob?.cancel()
        workJob?.cancel()
        permissionWaiter?.cancel()
        session.close()
        updateState {
            it.copy(
                connecting = false,
                connected = false,
                running = false,
                stage = VivoStage.Idle,
                transferProgress = null,
                status = "Stopped",
            )
        }
        appendLog("Stopped by user")
    }

    fun dispose() {
        stopRequested = true
        connectJob?.cancel()
        workJob?.cancel()
        permissionWaiter?.cancel()
        session.close()
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
    }

    private suspend fun openClient(waitingAfterReboot: Boolean): UsbAdbClient =
        withContext(Dispatchers.IO) {
            val timeout = if (waitingAfterReboot) RECONNECT_TIMEOUT_MS else INITIAL_CONNECT_TIMEOUT_MS
            val deadline = System.currentTimeMillis() + timeout
            var lastError: Throwable? = null
            while (true) {
                val device = UsbAdbClient.findAdbDevices(usbManager).firstOrNull()
                if (device == null) {
                    if (!waitingAfterReboot) {
                        throw IOException("No USB target")
                    }
                    setStatus("Waiting for target")
                    if (System.currentTimeMillis() > deadline) {
                        throw IOException("Reconnect timed out")
                    }
                    delay(DEVICE_SCAN_INTERVAL_MS)
                    continue
                }

                val permissionKey = usbPermissionKey(device)
                if (!usbManager.hasPermission(device)) {
                    val recentlyGranted = lastUsbPermissionKey == permissionKey &&
                        SystemClock.elapsedRealtime() - lastUsbPermissionAt <
                        USB_PERMISSION_REUSE_GRACE_MS
                    if (!recentlyGranted) {
                        setStatus("Approve USB debugging")
                        if (!requestUsbPermission(device)) {
                            throw IOException("USB permission denied")
                        }
                        lastUsbPermissionKey = permissionKey
                        lastUsbPermissionAt = SystemClock.elapsedRealtime()
                    }
                } else {
                    lastUsbPermissionKey = permissionKey
                    lastUsbPermissionAt = SystemClock.elapsedRealtime()
                }

                val client = runCatching {
                    UsbAdbClient(usbManager, device, credentials, appContext.cacheDir)
                }.getOrElse { error ->
                    lastError = error
                    if (System.currentTimeMillis() > deadline) {
                        throw IOException(
                            error.message ?: "Cannot open the USB ADB device",
                            error,
                        )
                    }
                    delay(DEVICE_SCAN_INTERVAL_MS)
                    continue
                }

                try {
                    setStatus("Connecting…")
                    client.connect()
                    val probe = client.shell("true")
                    if (probe.exitCode != 0) {
                        throw IOException("ADB shell probe failed (${probe.exitCode})")
                    }
                    return@withContext client
                } catch (error: Throwable) {
                    lastError = error
                    client.close()
                    if (!waitingAfterReboot && System.currentTimeMillis() > deadline) {
                        throw IOException(
                            error.message ?: "USB ADB connection failed",
                            error,
                        )
                    }
                    delay(DEVICE_SCAN_INTERVAL_MS)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException(lastError?.message ?: "USB ADB connection failed")
        }

    private fun readTarget(client: UsbAdbClient): VivoTarget {
        val properties = parseGetProp(client.shell("getprop").output)
        fun first(vararg names: String): String = names.firstNotNullOfOrNull { name ->
            properties[name]?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()

        val brand = first(
            "ro.product.brand",
            "ro.product.manufacturer",
            "ro.product.vendor.brand",
        )
        val model = first(
            "ro.vivo.market.name",
            "ro.vivo.product.model",
            "ro.product.marketname",
            "ro.product.model",
            "ro.product.vendor.model",
        )
        val system = first(
            "ro.build.version.bbk",
            "ro.vivo.os.build.display.id",
            "ro.vivo.rom.version",
            "ro.vivo.os.version",
            "ro.build.version.incremental",
            "ro.build.display.id",
        )
        val kernel = client.shell("uname -r").output.trim()
        if (brand.isBlank() || model.isBlank() || system.isBlank() || kernel.isBlank()) {
            throw IOException("Could not read the full target identity")
        }
        return VivoTarget(brand, model, system, kernel)
    }

    private fun parseGetProp(output: String): Map<String, String> = buildMap {
        output.lineSequence().forEach { line ->
            val match = GETPROP_LINE.matchEntire(line.trim()) ?: return@forEach
            put(match.groupValues[1], match.groupValues[2])
        }
    }

    private suspend fun requestUsbPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val waiter = CompletableDeferred<Boolean>()
        permissionWaiter?.cancel()
        permissionWaiter = waiter
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            USB_PERMISSION_REQUEST_CODE,
            intent,
            flags,
        )
        usbManager.requestPermission(device, pendingIntent)
        return try {
            withTimeoutOrNull(USB_PERMISSION_TIMEOUT_MS) { waiter.await() } ?: false
        } finally {
            if (permissionWaiter === waiter) permissionWaiter = null
        }
    }

    private fun usbPermissionKey(device: UsbDevice): String =
        "${device.vendorId}|${device.productId}"

    private fun copyLocalPayload(uri: Uri): File {
        val target = File.createTempFile("openroot-vivo-local-", ".bin", appContext.cacheDir)
        return try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("The selected file cannot be read")
            var total = 0L
            input.use { source ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_LOCAL_PAYLOAD_BYTES) {
                            throw IOException("The selected payload is larger than 512 MB")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (total == 0L) throw IOException("The selected payload is empty")
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** The UI uses this for the local file's display name. */
    fun localPayloadName(uri: Uri): String =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
            ?: uri.lastPathSegment
            ?: "local payload"

    private fun setStatus(status: String) {
        updateState { it.copy(status = status) }
    }

    private fun appendLog(message: String) {
        message.lineSequence().filter(String::isNotBlank).forEach(onLog)
    }

    private fun updateState(block: (VivoUiState) -> VivoUiState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            state = block(state)
            onStateChanged(state)
        } else {
            mainHandler.post {
                state = block(state)
                onStateChanged(state)
            }
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.hexo141.liquidglass.action.USB_ADB_PERMISSION"
        const val USB_PERMISSION_REQUEST_CODE = 741
        const val DEVICE_SCAN_INTERVAL_MS = 1_000L
        const val USB_PERMISSION_TIMEOUT_MS = 60_000L
        const val USB_PERMISSION_REUSE_GRACE_MS = 15_000L
        const val INITIAL_CONNECT_TIMEOUT_MS = 120_000L
        const val RECONNECT_TIMEOUT_MS = 240_000L
        const val MAX_LOCAL_PAYLOAD_BYTES = 512L * 1024L * 1024L
        val GETPROP_LINE = Regex("^\\[([^]]+)]\\s*:\\s*\\[(.*)]$")
    }
}

/** Fraction used by the shared exploit-log progress bar. */
fun VivoUiState.progressFraction(): Float = when (stage) {
    VivoStage.Idle -> 0f
    VivoStage.Connecting -> 0.04f
    VivoStage.ReadingTarget -> 0.08f
    VivoStage.PushingPayload ->
        0.15f + ((transferProgress ?: 0).coerceIn(0, 100) / 100f) * 0.25f
    VivoStage.Rebooting -> 0.46f
    VivoStage.Reconnecting -> 0.52f
    VivoStage.WaitingForBoot -> 0.58f
    VivoStage.ExecutingPayload -> 0.72f
    VivoStage.CheckingRoot -> 0.82f
    VivoStage.ActivatingKernelSu -> 0.9f
    VivoStage.Done -> 1f
    VivoStage.Failed -> 0f
}
