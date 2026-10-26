package kr.callbrief

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts settings in this class. Other stores need their own verified storage policy. No audio is copied here. */
class SecureSettings(context: Context, private val keyAccess: KeyAccess = AndroidKeys(if(context is AccountStorageContext) ALIAS + "." + context.scope else ALIAS)) {
    private val prefs = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
    companion object { private val lock = Any(); private const val ALIAS = "callbrief.client.v1" }
    interface KeyAccess { fun existing(): SecretKey?; fun createForFirstEmptyStore(): SecretKey }
    private class AndroidKeys(private val alias: String) : KeyAccess {
        override fun existing(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? SecretKey
        override fun createForFirstEmptyStore(): SecretKey {
            check(existing() == null) { "기존 설정 키는 교체하지 않아요." }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false).build())
            }.generateKey()
        }
    }
    private fun key(allowFirstCreation: Boolean = false): SecretKey {
        keyAccess.existing()?.let { return it }
        check(allowFirstCreation) { "설정 암호화 키를 찾지 못했어요. 기존 설정과 제외 규칙은 유지했어요. 복구 확인이 필요해요." }
        return keyAccess.createForFirstEmptyStore()
    }
    fun snapshot(): JSONObject = synchronized(lock) {
        val sealed = prefs.getString("state", null) ?: return@synchronized JSONObject()
        val parts = sealed.split(':', limit = 2)
        check(parts.size == 2) { "설정 저장소를 읽을 수 없어요. 재연결이 필요해요." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }
    fun update(block: (JSONObject) -> Unit) = synchronized(lock) {
        val hasExistingState = prefs.getString("state", null) != null
        val state = snapshot()
        // Monotonic report fence: restoring the same exclusions must not revive an older queued payload.
        val reportPolicyKeys=listOf("personalPhoneDisabled","personalAliasDisabled","exclusions","identities","aliasMappings","treeUri","pendingRevocation")
        val beforePolicy=reportPolicyKeys.map { state.opt(it)?.toString() }
        val beforeEpoch=state.optLong("dailyReportPolicyEpoch",0)
        block(state)
        if(beforePolicy!=reportPolicyKeys.map { state.opt(it)?.toString() })state.put("dailyReportPolicyEpoch",beforeEpoch+1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(allowFirstCreation = !hasExistingState)) }
        val ciphertext = cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        check(prefs.edit().putString("state", sealed).commit()) { "설정을 안전하게 저장하지 못했어요." }
    }
    /** Intake only: approval payload and its required-journal marker in an existing store.
     * prepare must durably quarantine the attempt before preferences can expose new bytes.
     * A false/throwing preference commit is uncertain, not a rollback. Its journal stays held. */
    internal fun commitExistingProcessingSetup(value: JSONObject, requiredJournal: JSONObject,
        validate: (JSONObject) -> Unit,
        prepare: (JSONObject, String) -> Boolean,
        committed: () -> Unit): Boolean = synchronized(lock) {
        val original = prefs.getString("state", null) ?: error("APPROVED_PROCESSING_EXISTING_SETTINGS_REQUIRED")
        val state = snapshot() // Authenticates with the existing key before any journal or settings write.
        validate(state)
        if (!prepare(state, original)) return@synchronized false
        validate(state)
        val updated = JSONObject(state.toString()).put("approvedAutomationProcessingSetup", JSONObject(value.toString()))
            .put("approvedAutomationProcessingSetupIntakeRequired",JSONObject(requiredJournal.toString()))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(updated.toString().toByteArray(Charsets.UTF_8))
        val sealed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        validate(state)
        check(prefs.edit().putString("state", sealed).commit()) { "APPROVED_PROCESSING_SETTINGS_COMMIT_UNCERTAIN" }
        validate(state)
        committed() // Only a durable journal completion allows the existing reader to use the field.
        true
    }
    fun message(value: String) = update { it.put("statusMessage", value); it.put("statusAt", java.time.Instant.now().toString()) }
    fun uploadedId(fingerprint: String): String? = snapshot().optJSONObject("uploaded")?.optString(fingerprint)?.takeIf { it.isNotBlank() }
    fun recordUpload(fingerprint: String, id: String) = update {
        val ledger = it.optJSONObject("uploaded") ?: JSONObject()
        ledger.put(fingerprint, id); it.put("uploaded", ledger)
    }
    fun counterpartKey(phone: String): String = synchronized(lock) {
        val keys=snapshot().optJSONObject("counterpartKeys") ?: JSONObject()
        keys.optString(phone).takeIf { it.isNotBlank() }?.let { return@synchronized it }
        val key=java.util.UUID.randomUUID().toString()
        update { val current=it.optJSONObject("counterpartKeys") ?: JSONObject();current.put(phone,key);it.put("counterpartKeys",current) }
        key
    }
    fun assignment(uri: String): String? = snapshot().optJSONObject("assignments")?.optString(uri)?.takeIf { it.isNotBlank() }
    fun assign(uri: String, topicId: String) = update {
        val assignments = it.optJSONObject("assignments") ?: JSONObject()
        assignments.put(uri, topicId); it.put("assignments", assignments)
    }
}
