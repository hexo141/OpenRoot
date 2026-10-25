package com.hexo141.liquidglass.vivo

import android.content.Context
import com.hexo141.liquidglass.adb.UsbAdbClient
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class VivoStage {
    Idle,
    Connecting,
    ReadingTarget,
    PushingPayload,
    Rebooting,
    Reconnecting,
    WaitingForBoot,
    ExecutingPayload,
    CheckingRoot,
    ActivatingKernelSu,
    Done,
    Failed,
}

data class VivoRunUpdate(
    val stage: VivoStage,
    val status: String,
    val transferProgress: Int? = null,
    val success: Boolean = false,
)

/** Mutable holder so "stop" can close whichever transport the flow is currently using. */
class VivoUsbSession {
    @Volatile
    var client: UsbAdbClient? = null

    fun close() {
        val current = client
        client = null
        runCatching { current?.close() }
    }
}

/**
 * The wired vivo/iQOO flow: push the model-matched preload payload, reboot the target, wait for
 * the new USB session, run the payload, verify the temporary `su`, then late-load KernelSU.
 *
 * The target reboots during the flow, so the caller supplies [connectTarget] to rebuild the USB
 * transport; [session] is where the live client is shared with [VivoUsbSession.close] for a stop.
 */
class VivoWiredRunner(
    private val context: Context,
    private val session: VivoUsbSession,
    private val connectTarget: suspend (waitingAfterReboot: Boolean) -> UsbAdbClient,
    private val publish: (VivoRunUpdate) -> Unit,
    private val log: (String) -> Unit,
) {
    suspend fun run(
        target: VivoTarget,
        payload: VivoPayload?,
        localPayload: File?,
        managerPackage: String = "",
    ): Boolean {
        var localCopy = localPayload
        try {
            var client = session.client ?: connectTarget(false).also { session.client = it }
            publish(VivoRunUpdate(VivoStage.ReadingTarget, "Starting"))

            if (payload != null && !target.isVivo) {
                throw IOException("This entry only supports vivo/iQOO targets")
            }

            val source = if (payload != null) {
                VivoPayloadCatalog.fileFor(context, payload)
                    ?: throw IOException("Bundled payload verification failed")
            } else {
                localPayload ?: throw IOException("No privilege payload selected")
            }
            val remotePayload = REMOTE_PAYLOAD
            val command = if (payload != null) {
                payloadCommand(payload, remotePayload)
            } else {
                LOCAL_COMMAND
            }
            validateCommand(command)
            publish(
                VivoRunUpdate(
                    VivoStage.PushingPayload,
                    "Pushing payload",
                    transferProgress = 0,
                ),
            )
            client.shell("rm -f -- '$remotePayload'")
            client = pushWithRetry(client, source, remotePayload)
            val installCheck = client.shell(
                "chmod 0755 '$remotePayload' && test -f '$remotePayload' && test -x '$remotePayload'",
            )
            if (installCheck.exitCode != 0) {
                throw IOException(
                    "Payload install failed${installCheck.output.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}",
                )
            }
            log("Payload written to $remotePayload")

            publish(VivoRunUpdate(VivoStage.Rebooting, "Rebooting"))
            try {
                client.reboot()
            } catch (error: IOException) {
                // A successful reboot normally drops USB before adbd closes the service.
            }
            session.close()

            publish(VivoRunUpdate(VivoStage.Reconnecting, "Reconnecting"))
            delay(REBOOT_DISCONNECT_GRACE_MS)
            client = connectTarget(true)
            session.client = client

            publish(VivoRunUpdate(VivoStage.WaitingForBoot, "Waiting for boot"))
            client = waitForBootCompleted(client)
            delay(POST_BOOT_DELAY_MS)

            publish(VivoRunUpdate(VivoStage.ExecutingPayload, "Running payload"))
            val execution = client.shell(command)
            if (execution.output.isNotBlank()) log(execution.output.trimEnd())
            log("Payload exit=${execution.exitCode}")
            if (payload != null && execution.exitCode != 0) {
                throw IOException("Privilege payload exited with ${execution.exitCode}")
            }

            publish(VivoRunUpdate(VivoStage.CheckingRoot, "Checking root"))
            val rootCheck = client.shell("$SU_PATH -c id")
            if (rootCheck.output.isNotBlank()) log(rootCheck.output.trimEnd())
            if (!ROOT_UID_PATTERN.containsMatchIn(rootCheck.output)) {
                throw IOException("Temporary root check did not return uid=0")
            }

            publish(VivoRunUpdate(VivoStage.ActivatingKernelSu, "Activating KernelSU"))
            client = activateKernelSu(client, managerPackage)

            runCatching { client.shell("rm -f -- '$remotePayload'") }
            publish(
                VivoRunUpdate(
                    VivoStage.Done,
                    "Done",
                    success = true,
                ),
            )
            return true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            log("Failed: $reason")
            publish(VivoRunUpdate(VivoStage.Failed, reason))
            return false
        } finally {
            session.close()
            localCopy?.delete()
        }
    }

    private suspend fun pushWithRetry(
        initialClient: UsbAdbClient,
        source: File,
        destination: String,
    ): UsbAdbClient {
        var client = initialClient
        var resumeRemoteParts = false
        val completedPartIndices = linkedSetOf<Int>()
        val deferredPartIndices = linkedSetOf<Int>()
        val partFailures = mutableMapOf<Int, Int>()
        var transportFailures = 0
        var reconnects = 0
        while (true) {
            try {
                client.push(
                    source = source,
                    destination = destination,
                    resumeRemoteParts = resumeRemoteParts,
                    completedPartIndices = completedPartIndices,
                    deferredPartIndices = deferredPartIndices,
                    onLog = { log(it) },
                    onProgress = { transferred, total ->
                        val percent = if (total > 0L) {
                            ((transferred.coerceIn(0L, total) * 100L) / total).toInt()
                                .coerceIn(0, 100)
                        } else {
                            0
                        }
                        publish(
                            VivoRunUpdate(
                                VivoStage.PushingPayload,
                                "Pushing payload ($percent%)",
                                transferProgress = percent,
                            ),
                        )
                    },
                )
                return client
            } catch (error: UsbAdbClient.TransportException) {
                val interruptedPart = error.transferPartIndex
                if (interruptedPart != null) {
                    val failures = (partFailures[interruptedPart] ?: 0) + 1
                    partFailures[interruptedPart] = failures
                    deferredPartIndices += interruptedPart
                    log(
                        "Part ${interruptedPart.toString().padStart(5, '0')} deferred " +
                            "($failures/$SAME_PART_TRANSFER_ATTEMPTS)",
                    )
                    if (failures >= SAME_PART_TRANSFER_ATTEMPTS) throw error
                } else {
                    transportFailures += 1
                }
                reconnects += 1
                if (transportFailures >= SAME_PART_TRANSFER_ATTEMPTS ||
                    reconnects >= MAX_FILE_TRANSFER_RECONNECTS
                ) {
                    throw error
                }
                log("USB ADB transfer interrupted; rebuilding the transport")
                session.close()
                client = connectTarget(false)
                session.client = client
                resumeRemoteParts = true
            }
        }
    }

    private suspend fun waitForBootCompleted(initialClient: UsbAdbClient): UsbAdbClient {
        var client = initialClient
        var rebuilt = false
        var lastBootValue: String? = null
        val deadline = System.currentTimeMillis() + BOOT_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val value = client.shell("getprop sys.boot_completed").output.trim()
                if (value == "1") {
                        session.client = client
                    return client
                }
                if (value != lastBootValue) {
                    lastBootValue = value
                    log("sys.boot_completed=$value")
                }
            } catch (error: UsbAdbClient.TransportException) {
                if (rebuilt) throw IOException("ADB transport lost while waiting for boot", error)
                log("ADB transport dropped during boot; rebuilding")
                session.close()
                client = connectTarget(true)
                session.client = client
                rebuilt = true
                continue
            } catch (error: UsbAdbClient.ServiceRejectedException) {
                if (rebuilt) throw IOException("ADB shell rejected while waiting for boot", error)
                log("ADB shell not ready after handshake; rebuilding the transport")
                session.close()
                client = connectTarget(true)
                session.client = client
                rebuilt = true
                continue
            }
            delay(BOOT_WAIT_INTERVAL_MS)
        }
        throw IOException("Timed out waiting for the target to boot")
    }

    private suspend fun activateKernelSu(
        client: UsbAdbClient,
        managerPackage: String,
    ): UsbAdbClient {
        val script = copyAsset(ACTIVATION_ASSET)
        try {
            var current = client
            current = pushWithRetry(current, script, ACTIVATION_DESTINATION, ACTIVATION_MODE)
            val activationCommand = buildString {
                append("YHROOT_KSU_PACKAGE=")
                append(shellQuote(managerPackage))
                append(" /system/bin/sh ")
                append(shellQuote(ACTIVATION_DESTINATION))
            }
            log("$ $SU_PATH -c $activationCommand")
            val activation = current.shell(
                "chmod 0700 ${shellQuote(ACTIVATION_DESTINATION)} && " +
                    "${shellQuote(SU_PATH)} -c ${shellQuote(activationCommand)}",
            )
            if (activation.output.isNotBlank()) log(activation.output.trimEnd())
            if (activation.exitCode != 0) {
                throw IOException("KernelSU activation script exited with ${activation.exitCode}")
            }
            runCatching {
                current.shell("rm -f -- ${shellQuote(ACTIVATION_DESTINATION)}")
            }
            return current
        } finally {
            script.delete()
        }
    }

    private fun copyAsset(name: String): File {
        val target = File.createTempFile("openroot-ksu-", ".sh", context.cacheDir)
        return try {
            context.assets.open(name).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun payloadCommand(payload: VivoPayload, remote: String): String {
        val command = payload.command
            .replace("<payload>", remote)
            .replace("/data/local/tmp/preload.so", remote)
            .replace("/data/local/tmp/mi_mt6895_43499", remote)
            .replace("<su>", "/system/bin/id")
        return if (command.contains('<') || command.contains('>')) {
            LOCAL_COMMAND
        } else {
            command
        }
    }

    private fun validateCommand(command: String) {
        if (command.isBlank() ||
            command.toByteArray().size > MAX_COMMAND_BYTES ||
            command.indexOf('\u0000') >= 0 ||
            FORBIDDEN_COMMAND_PATTERN.containsMatchIn(command)
        ) {
            throw IOException("Privilege command is invalid")
        }
    }

    private suspend fun pushWithRetry(
        initialClient: UsbAdbClient,
        source: File,
        destination: String,
        mode: Int,
    ): UsbAdbClient {
        // The activation script is tiny: reuse the ordinary push without the resume bookkeeping.
        var client = initialClient
        repeat(3) { attempt ->
            try {
                client.push(source = source, destination = destination, mode = mode)
                return client
            } catch (error: UsbAdbClient.TransportException) {
                if (attempt == 2) throw error
                log("Script push interrupted; rebuilding the transport")
                session.close()
                client = connectTarget(false)
                session.client = client
            }
        }
        return client
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val REMOTE_PAYLOAD = "/data/local/tmp/preload.so"
        const val SU_PATH = "/data/local/tmp/su"
        const val LOCAL_COMMAND = "LD_PRELOAD=$REMOTE_PAYLOAD /system/bin/id"
        const val ACTIVATION_ASSET = "vivo_ksu_activate.sh"
        const val ACTIVATION_DESTINATION = "/data/local/tmp/openroot_ksu_activate.sh"
        const val ACTIVATION_MODE = 0x81C0
        const val BOOT_WAIT_INTERVAL_MS = 2_000L
        const val BOOT_WAIT_TIMEOUT_MS = 180_000L
        const val REBOOT_DISCONNECT_GRACE_MS = 3_000L
        const val POST_BOOT_DELAY_MS = 5_000L
        const val SAME_PART_TRANSFER_ATTEMPTS = 12
        const val MAX_FILE_TRANSFER_RECONNECTS = 64
        const val MAX_COMMAND_BYTES = 1024
        val ROOT_UID_PATTERN = Regex("(?:^|\\s)uid=0(?:\\(root\\))?(?:\\s|\\z)")
        val FORBIDDEN_COMMAND_PATTERN = Regex("[;\\r\\n&]|\\|\\|")
    }
}
