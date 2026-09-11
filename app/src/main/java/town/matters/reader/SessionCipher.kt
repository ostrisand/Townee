package town.matters.reader

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A new random IV is generated for every write, authenticated with the ciphertext. */
class SessionCipher(private val key: () -> SecretKey) {
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        require(cipher.iv.size == 12)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }
    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size >= 29 && payload[0] == 1.toByte()) { "Invalid session format" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
        return cipher.doFinal(payload.copyOfRange(13, payload.size))
    }
}
