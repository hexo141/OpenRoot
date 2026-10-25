package com.hexo141.liquidglass.adb

import android.content.Context
import java.security.interfaces.RSAPublicKey

/**
 * ADB RSA identity for the USB host transport.
 *
 * Reuses the pairing key so both transports present the same key to adbd; only the ADB-specific
 * public-key encoding and token signing differ from the TLS path.
 */
class AdbCredentials(context: Context, name: String = "openroot") {
    private val keyManager = AdbKeyManager(context)
    private val privateKey = keyManager.privateKey
    private val publicKey = keyManager.certificate.publicKey as RSAPublicKey

    val adbPublicKey: ByteArray = AndroidPubkey.encodeWithName(publicKey, name)

    fun sign(data: ByteArray): ByteArray = AndroidPubkey.adbAuthSign(privateKey, data)
}
