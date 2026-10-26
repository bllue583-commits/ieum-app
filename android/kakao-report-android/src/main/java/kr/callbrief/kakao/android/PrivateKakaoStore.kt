package kr.callbrief.kakao.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tokens and local consent live under noBackupFilesDir, sealed by an app Keystore key. No plaintext fallback. */
class PrivateKakaoStore(context: Context, namespace: String, private val keys: KeyAccess = AndroidKeys(namespace)) {
    interface KeyAccess { fun existing(): SecretKey?; fun createForEmptyStore(): SecretKey }
    private class AndroidKeys(namespace: String) : KeyAccess {
        private val alias = "ieum.kakao.report.v1.$namespace"
        override fun existing() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? SecretKey
        override fun createForEmptyStore(): SecretKey {
            check(existing() == null) { "existing_key_not_replaced" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false).build())
            }.generateKey()
        }
    }
    private val file: AtomicFile
    init {
        require(namespace.matches(Regex("[a-zA-Z0-9_-]{8,80}")))
        val dir = File(context.applicationContext.noBackupFilesDir,"kakao-report-v1")
        check(dir.isDirectory || dir.mkdirs()) { "private_directory_unavailable" }
        file = AtomicFile(File(dir,"$namespace.sealed"))
    }
    companion object { private val lock = Any() }
    fun snapshot(): JSONObject = synchronized(lock) {
        val bytes = try { file.readFully() } catch (e: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path+".bak").exists()) throw e
            return@synchronized JSONObject()
        }
        check(bytes.size in 30..131072 && bytes[0] == 1.toByte()) { "private_state_invalid" }
        val key = keys.existing() ?: error("private_state_key_missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        cipher.updateAAD(file.baseFile.name.toByteArray(Charsets.UTF_8))
        JSONObject(String(cipher.doFinal(bytes.copyOfRange(13,bytes.size)),Charsets.UTF_8))
    }
    fun update(change: (JSONObject) -> Unit) = synchronized(lock) {
        val hadData = file.baseFile.exists() || File(file.baseFile.path+".bak").exists()
        val state = snapshot(); change(state)
        val key = keys.existing() ?: if (!hadData) keys.createForEmptyStore() else error("private_state_key_missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key) }
        cipher.updateAAD(file.baseFile.name.toByteArray(Charsets.UTF_8))
        val plain = state.toString().toByteArray(Charsets.UTF_8)
        check(plain.size <= 65536) { "private_state_too_large" }
        val sealed = byteArrayOf(1)+cipher.iv+cipher.doFinal(plain)
        val out = file.startWrite()
        try { out.write(sealed); file.finishWrite(out) } catch(e: Exception) { file.failWrite(out); throw e }
    }
}
