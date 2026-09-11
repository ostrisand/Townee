package town.matters.reader

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Tokens never enter plaintext preferences or backup; passwords are never stored. */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "account-session.bin"))
    private val alias = "town.matters.reader.account.v1"
    private val cipher = SessionCipher(::secretKey)

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun secretKey(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    override suspend fun load(): AccountSession? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        val plaintext = cipher.decrypt(file.readFully())
        try {
            val j = JSONObject(plaintext.toString(Charsets.UTF_8))
            val token = j.getString("token").also { require(it.isNotBlank()) }
            AccountSession(token, Account.parse(j.getJSONObject("account")))
        } finally { plaintext.fill(0) }
    }

    override suspend fun save(session: AccountSession) = withContext(Dispatchers.IO) {
        val plaintext = JSONObject().put("token", session.token).put("account", session.account.json())
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.encrypt(plaintext) } finally { plaintext.fill(0) }
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        // Destroying the key also makes any surviving ciphertext unreadable.
        keyStore().deleteEntry(alias)
        file.delete()
        check(!file.baseFile.exists()) { "Could not delete local session" }
    }
}
