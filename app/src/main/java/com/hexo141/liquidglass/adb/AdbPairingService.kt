package com.hexo141.liquidglass.adb

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.hexo141.liquidglass.MainActivity
import com.hexo141.liquidglass.R
import com.hexo141.liquidglass.adb.mdns.AdbMdns
import java.net.ConnectException
import kotlin.concurrent.thread

/**
 * Notification-bar wireless debugging (ADB) pairing.
 *
 * mDNS discovers the local `_adb-tls-pairing._tcp` service, a notification with an inline
 * reply collects the 6-digit pairing code, then SPAKE2 + TLS hands this app's ADB public key
 * to adbd. Discovery keeps running after a successful pair, so pairing can be repeated.
 *
 * Reference: Shizuku's AdbPairingService (https://github.com/RikkaApps/Shizuku), which stops
 * discovery after the first success.
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "AdbPairingService"

        const val CHANNEL_ID = "adb_pairing"

        /** Persistent notification showing the current state (waiting / input / working). */
        private const val ID_FOREGROUND = 1

        /** Separate notification so a pairing result is never overwritten by the state one. */
        private const val ID_RESULT = 2

        private const val REPLY_KEY = "pairing_code"
        private const val EXTRA_PORT = "port"

        private const val REQ_CONTENT = 1
        private const val REQ_STOP = 2
        private const val REQ_RETRY = 3
        private const val REQ_REPLY_BASE = 1000

        /** Device name written next to the key in adbd's adb_keys. */
        const val DEVICE_NAME = "OpenRoot"

        private const val ACTION_START = "com.hexo141.liquidglass.action.PAIRING_START"
        private const val ACTION_STOP = "com.hexo141.liquidglass.action.PAIRING_STOP"
        private const val ACTION_REPLY = "com.hexo141.liquidglass.action.PAIRING_REPLY"
        private const val ACTION_RETRY = "com.hexo141.liquidglass.action.PAIRING_RETRY"

        /** Debug builds only: pair with an externally supplied code (used by adb-driven smoke tests). */
        private const val ACTION_DEBUG_PAIR = "com.hexo141.liquidglass.action.DEBUG_PAIR"

        const val EXTRA_DEBUG_CODE = "debug_pair_code"
        const val EXTRA_DEBUG_PORT = "debug_pair_port"
        const val EXTRA_DEBUG_HOST = "debug_pair_host"

        @Volatile
        private var running = false

        /** Whether the service is currently alive and listening. */
        fun isRunning(): Boolean = running

        /** Start (or restart) listening for pairing requests. */
        fun start(context: Context) {
            val intent = Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
            startService(context, intent)
        }

        /** Stop listening for pairing requests. Safe to call when the service is not running. */
        fun stop(context: Context) {
            // stopService() never starts the service (unlike an ACTION_STOP startService call) and
            // is a no-op when it is already gone. This avoids the foreground-service start/stop
            // race that happens when "Stop" is tapped twice.
            context.stopService(Intent(context, AdbPairingService::class.java))
        }

        /** Debug builds only: run a pairing attempt with an explicit code and port. */
        fun debugPair(context: Context, code: String, port: Int, host: String?) {
            if (!isDebuggable(context)) return
            val intent = Intent(context, AdbPairingService::class.java)
                .setAction(ACTION_DEBUG_PAIR)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_DEBUG_CODE, code)
                .putExtra(EXTRA_DEBUG_HOST, host)
            startService(context, intent)
        }

        private fun isDebuggable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        private fun startService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun replyIntent(context: Context, port: Int) =
            Intent(context, AdbPairingService::class.java)
                .setAction(ACTION_REPLY)
                .putExtra(EXTRA_PORT, port)

        private fun stopIntent(context: Context) =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP)

        private fun retryIntent(context: Context) =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_RETRY)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    private var adbMdns: AdbMdns? = null
    private var searching = false
    private var lastPort = -1
    private var lastHost: String? = null
    private var lastPromptPort = -1
    private var pairing = false
    private var stoppedAfterSuccess = false

    private val discoveredListener = AdbMdns.OnAdbDaemonDiscoveredListener { address, port ->
        Log.i(TAG, "pairing service port changed: $port (host=$address)")
        if (port <= 0) return@OnAdbDaemonDiscoveredListener
        lastPort = port
        lastHost = address?.hostAddress
        // A new port means the pairing dialog was opened again. A re-resolve of the same port must
        // not replace whatever the user is currently looking at (e.g. a pairing result).
        if (!pairing && port != lastPromptPort) {
            lastPromptPort = port
            notify(ID_FOREGROUND, inputNotification(port))
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startDiscovery()
                goForeground(waitingNotification())
            }

            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REPLY_KEY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, lastPort)
                when {
                    code.isEmpty() -> {
                        Log.w(TAG, "ignore reply with empty code")
                        notify(ID_RESULT, failureNotification(getString(R.string.adb_pairing_failed_empty_code)))
                        goForeground(inputOrWaiting())
                    }
                    port <= 0 -> {
                        Log.w(TAG, "ignore reply for unknown port")
                        notify(ID_RESULT, failureNotification(getString(R.string.adb_pairing_failed_port)))
                        goForeground(waitingNotification())
                    }
                    else -> {
                        goForeground(workingNotification(port))
                        pair(code, port)
                    }
                }
            }

            ACTION_RETRY -> {
                // The pairing dialog may still be open, so ask for the code again on the same port
                goForeground(inputOrWaiting())
            }

            ACTION_DEBUG_PAIR -> {
                if (!isDebuggable(this)) return START_NOT_STICKY
                val code = intent.getStringExtra(EXTRA_DEBUG_CODE).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                val host = intent.getStringExtra(EXTRA_DEBUG_HOST)
                goForeground(workingNotification(port))
                pair(code, port, host)
            }

            ACTION_STOP -> {
                stopDiscovery()
                NotificationManagerCompat.from(this).cancel(ID_FOREGROUND)
                NotificationManagerCompat.from(this).cancel(ID_RESULT)
                stopForegroundSafe()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
        // The port is carried in the Intent so a redelivered reply still works after a restart
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        running = false
        stopDiscovery()
        runCatching { NotificationManagerCompat.from(this).cancel(ID_FOREGROUND) }
        // Keep the success notification visible; a manual stop should clear any stale result.
        if (!stoppedAfterSuccess) {
            runCatching { NotificationManagerCompat.from(this).cancel(ID_RESULT) }
        }
        runCatching { stopForegroundSafe() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ discovery

    private fun startDiscovery() {
        if (searching) return
        searching = true
        adbMdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, discoveredListener).also { it.start() }
        Log.i(TAG, "start discovering _adb-tls-pairing._tcp")
    }

    private fun stopDiscovery() {
        if (!searching) return
        searching = false
        adbMdns?.stop()
        adbMdns = null
        Log.i(TAG, "stop discovery")
    }

    // ------------------------------------------------------------------ pairing

    private fun pair(code: String, port: Int, host: String? = null) {
        pairing = true
        thread(name = "adb-pairing-$port") {
            // Prefer the address adbd advertised over mDNS; fall back to loopback for devices
            // whose pairing server also listens there.
            val hosts = listOfNotNull(host?.takeIf { it.isNotBlank() }, lastHost, "127.0.0.1").distinct()
            var failure: Throwable? = null
            for (candidate in hosts) {
                Log.i(TAG, "pairing via $candidate:$port")
                failure = runCatching {
                    val keyManager = AdbKeyManager(applicationContext)
                    PairingConnectionCtx(
                        candidate,
                        port,
                        code.toByteArray(Charsets.UTF_8),
                        keyManager.privateKey,
                        keyManager.certificate,
                        DEVICE_NAME
                    ).use { it.start() }
                }.exceptionOrNull()
                if (failure == null || failure !is ConnectException) break
            }

            val result = failure
            if (result == null) {
                Log.i(TAG, "pair succeeded on port $port")
                // adbd now trusts our key, so the main screen can connect without a code.
                PairingState(this).markPaired()
                mainHandler.post {
                    pairing = false
                    stoppedAfterSuccess = true
                    notify(ID_RESULT, succeedNotification())
                    // adbd has taken the public key, so there is nothing left to listen for: drop
                    // the foreground notification and stop. The main screen hides the pairing
                    // button and reconnects directly from then on.
                    stopDiscovery()
                    NotificationManagerCompat.from(this).cancel(ID_FOREGROUND)
                    stopForegroundSafe()
                    stopSelf()
                }
            } else {
                Log.w(TAG, "pair failed on port $port (phase=${PairingConnectionCtx.sLastPhase})", result)
                mainHandler.post {
                    pairing = false
                    notify(ID_RESULT, failureNotification(describeFailure(result)))
                    goForeground(waitingNotification())
                }
            }
        }
    }

    /** Always returns a non-empty, human readable reason for a failed pairing. */
    private fun describeFailure(error: Throwable): String {
        if (error is ConnectException) {
            return getString(R.string.adb_pairing_failed_port)
        }
        val raw = error.message?.trim().orEmpty()
        val lower = raw.lowercase()
        return when {
            // Both "Exchanging message wasn't successful" and "Could not exchange peer info"
            // mean the SPAKE2 keys did not match, i.e. a wrong or expired pairing code
            lower.contains("exchange") || lower.contains("cipher") || lower.contains("password") ->
                getString(R.string.adb_pairing_failed_code)

            // adbd mixes the TLS exporter output into the SPAKE2 password, so a failure to export
            // it aborts pairing before any code can be checked
            lower.contains("keying material") -> getString(R.string.adb_pairing_failed_tls_export)

            lower.contains("tls") || lower.contains("ssl") ->
                getString(R.string.adb_pairing_failed_tls)

            raw.isNotEmpty() -> raw

            else -> error.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.adb_pairing_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.adb_pairing_channel_description)
            setSound(null, null)
            setShowBadge(false)
            setAllowBubbles(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

    private fun baseNotification(title: CharSequence): Notification.Builder =
        builder()
            .setSmallIcon(R.drawable.ic_adb_pairing)
            .setContentTitle(title)
            .setContentIntent(contentIntent())

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQ_CONTENT,
        Intent(this, MainActivity::class.java),
        immutableFlags()
    )

    private fun immutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

    private fun mutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun servicePendingIntent(requestCode: Int, intent: Intent, mutable: Boolean): PendingIntent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, if (mutable) mutableFlags() else immutableFlags())
        } else {
            PendingIntent.getService(this, requestCode, intent, if (mutable) mutableFlags() else immutableFlags())
        }

    private fun waitingNotification(): Notification =
        baseNotification(getString(R.string.adb_pairing_searching_title))
            .setContentText(getString(R.string.adb_pairing_searching_text))
            .setOngoing(true)
            .addAction(stopAction())
            .build()

    /** Posted once a pairing port is discovered; carries the inline pairing-code reply action. */
    private fun inputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(REPLY_KEY)
            .setLabel(getString(R.string.adb_pairing_code_hint))
            .build()

        val action = Notification.Action.Builder(
            null,
            getString(R.string.adb_pairing_input_code),
            servicePendingIntent(REQ_REPLY_BASE + port, replyIntent(this, port), mutable = true)
        ).addRemoteInput(remoteInput).build()

        return baseNotification(getString(R.string.adb_pairing_found_title))
            .setContentText(getString(R.string.adb_pairing_found_text))
            .setOngoing(true)
            .addAction(action)
            .addAction(stopAction())
            .build()
    }

    private fun workingNotification(port: Int): Notification =
        baseNotification(getString(R.string.adb_pairing_working_title))
            .setContentText(getString(R.string.adb_pairing_working_text, port))
            .setOngoing(true)
            .build()

    private fun succeedNotification(): Notification =
        baseNotification(getString(R.string.adb_pairing_succeed_title))
            .setContentText(getString(R.string.adb_pairing_succeed_text))
            .setAutoCancel(true)
            .build()

    private fun failureNotification(reason: String): Notification =
        baseNotification(getString(R.string.adb_pairing_failed_title))
            .setContentText(reason)
            .setStyle(Notification.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .addAction(retryAction())
            .addAction(stopAction())
            .build()

    private fun inputOrWaiting(): Notification =
        if (lastPort > 0) inputNotification(lastPort) else waitingNotification()

    private fun stopAction(): Notification.Action =
        Notification.Action.Builder(
            null,
            getString(R.string.adb_pairing_stop),
            servicePendingIntent(REQ_STOP, stopIntent(this), mutable = false)
        ).build()

    private fun retryAction(): Notification.Action =
        Notification.Action.Builder(
            null,
            getString(R.string.adb_pairing_retry),
            servicePendingIntent(REQ_RETRY, retryIntent(this), mutable = false)
        ).build()

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked before starting; failure is handled
    private fun notify(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot post notification: POST_NOTIFICATIONS not granted", e)
        }
    }

    private fun goForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID_FOREGROUND, notification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
            // On Android 12+ a denied background FGS start degrades to a plain notification
            notify(ID_FOREGROUND, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
