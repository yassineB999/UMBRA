package org.umbra.core.core

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private val KEY = SecretKeySpec(
        hexToBytes("d41d8cd98f00b204e9800998ecf8427e" +
                   "d41d8cd98f00b204e9800998ecf8427e"), "AES"
    )

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, KEY)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    fun encrypt(plaintext: String): String =
        Base64.encodeToString(encrypt(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    fun decrypt(encoded: ByteArray): ByteArray {
        val iv = encoded.copyOfRange(0, IV_BYTES)
        val ciphertext = encoded.copyOfRange(IV_BYTES, encoded.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, KEY, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    fun decrypt(encoded: String): String =
        String(decrypt(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)

    fun decryptBytes(encoded: ByteArray): ByteArray = decrypt(encoded)

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
