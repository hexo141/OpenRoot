package com.hexo141.liquidglass.vivo

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Hardware identity of the USB target, read over the wired ADB shell. */
data class VivoTarget(
    val brand: String,
    val model: String,
    val system: String,
    val kernel: String,
) {
    val identity: String get() = "$brand $model"

    val isVivo: Boolean
        get() = listOf("vivo", "iqoo", "bbk").any(identity.lowercase()::contains)
}

/**
 * One prebuilt KSuRoot-style payload. The binaries are model/kernel specific, so the catalog
 * carries the exact build metadata and a SHA-256 digest instead of guessing at runtime.
 */
data class VivoPayload(
    val library: String,
    val displayName: String,
    val models: List<String>,
    val kernelVersion: String?,
    val kernelUnknown: Boolean,
    val buildId: String,
    val command: String,
    val sha256: String,
    val size: Long,
    val source: String,
) {
    val label: String
        get() = buildString {
            append(displayName.ifBlank { library })
            if (!kernelVersion.isNullOrBlank()) append(" · kernel ").append(kernelVersion)
            if (kernelUnknown) append(" · kernel unknown")
            if (buildId.isNotBlank()) append(" · ").append(buildId)
        }

    fun matches(target: VivoTarget): Boolean {
        val model = normalize(target.model)
        if (model.isEmpty()) return false
        // Model names are exact identifiers; prefix/substring matching would conflate e.g.
        // "X200" with "X200 Ultra" or "Neo10 Pro" with "Neo10 Pro+".
        val candidates = models + displayName.split('/')
        val modelHit = candidates.any { normalize(it) == model }
        if (!modelHit) return false
        val kernel = kernelVersion ?: return true
        return target.kernel == kernel ||
            target.kernel.startsWith("$kernel-") ||
            target.kernel.startsWith("$kernel.")
    }

    private fun normalize(value: String): String =
        value.lowercase().trim().replace(Regex("\\s+"), " ")
}

object VivoPayloadCatalog {
    private const val ASSET_NAME = "vivo_payloads.json"

    fun load(context: Context): List<VivoPayload> {
        val root = JSONObject(
            context.applicationContext.assets
                .open(ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() },
        )
        require(root.optString("schema") == "openroot-vivo-payloads/1") {
            "unsupported vivo payload catalog"
        }
        val array = root.optJSONArray("payloads") ?: error("vivo payload catalog is empty")
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    VivoPayload(
                        library = item.getString("library"),
                        displayName = item.optString("displayName"),
                        models = buildList {
                            val models = item.optJSONArray("models") ?: return@buildList
                            for (modelIndex in 0 until models.length()) {
                                models.optString(modelIndex).trim()
                                    .takeIf(String::isNotBlank)
                                    ?.let(::add)
                            }
                        },
                        kernelVersion = item.optString("kernelVersion")
                            .takeIf { it.isNotBlank() && it != "null" },
                        kernelUnknown = item.optBoolean("kernelUnknown", false),
                        buildId = item.optString("buildId"),
                        command = item.optString("command")
                            .ifBlank { "LD_PRELOAD=<payload> /system/bin/id" },
                        sha256 = item.getString("sha256"),
                        size = item.getLong("size"),
                        source = item.optString("source"),
                    ),
                )
            }
        }
    }

    /** The packaged binary, after size and digest verification against the catalog entry. */
    fun fileFor(context: Context, payload: VivoPayload): File? {
        val file = File(context.applicationInfo.nativeLibraryDir, payload.library)
        if (!file.isFile || file.length() != payload.size) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!actual.equals(payload.sha256, ignoreCase = true)) return null
        return file
    }
}
