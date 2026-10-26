package kr.callbrief

import kr.callbrief.core.ExcludedAlias
import kr.callbrief.core.PrivacyRules
import org.json.JSONArray
import org.json.JSONObject

data class RecordingIdentity(val phone: String, val alias: String)
enum class LocalCollectionDecision { ALLOW, EXCLUDE, HOLD }

object LocalPrivacy {
    // Personal-use APK requested for one partner. No raw phone number is embedded.
    // Hashes are low-entropy matching values; never describe them as anonymous or encrypted.
    const val PERSONAL_PHONE_HASH = "382e6bfad716a41aa161688852be0777cae22b3bea2ebebe3b635fd1b32eaead"
    const val PERSONAL_ALIAS_HASH = "1684ce349a07988c9ac5bd3572cccf2b7208da08820864b3bcda1c5c7401a4fc"
    const val PERSONAL_PHONE_LABEL = "요청한 제외 번호"
    const val PERSONAL_ALIAS_LABEL = "요청한 제외 이름 · 이모티콘·문장부호 변형 포함"
    fun rules(state: JSONObject): PrivacyRules {
        val phones = mutableSetOf<String>(); val aliases = mutableListOf<ExcludedAlias>()
        if (!state.optBoolean("personalPhoneDisabled")) phones.add(PERSONAL_PHONE_HASH)
        if (!state.optBoolean("personalAliasDisabled")) aliases.add(ExcludedAlias(PERSONAL_ALIAS_HASH, 2))
        val custom = state.optJSONArray("exclusions") ?: JSONArray()
        for (index in 0 until custom.length()) {
            val row = custom.getJSONObject(index)
            if (row.optString("type") == "phone") phones.add(row.getString("hash"))
            else aliases.add(ExcludedAlias(row.getString("hash"), row.getInt("length")))
        }
        return PrivacyRules(phones, aliases)
    }
    fun identity(recording: RecordingRef, state: JSONObject): RecordingIdentity? {
        // Explicit phone in OEM filename is unique identity metadata; a display name alone isn't.
        val phones = PrivacyRules.phoneTokens(recording.name)
        if(phones.size>1) return null
        val stored = state.optJSONObject("identities")?.optJSONObject(recording.uri.toString())
        if (stored != null) {
            val phone = PrivacyRules.validatedPhone(stored.optString("phone")) ?: return null
            if (phones.isNotEmpty() && (phones.size != 1 || phone !in phones)) return null
            return RecordingIdentity(phone, stored.optString("alias"))
        }
        if (phones.size == 1) return RecordingIdentity(phones.single(), kr.callbrief.core.RecordingNames.callerAlias(recording.name).orEmpty())
        val mapping=state.optJSONObject("aliasMappings")?.optJSONObject(mappingKey(recording,state) ?: return null) ?: return null
        if (!mapping.optBoolean("reuse_enabled") || mapping.optBoolean("conflict") || mapping.optString("treeUri")!=state.optString("treeUri")) return null
        val phone=PrivacyRules.validatedPhone(mapping.optString("phone")) ?: return null
        return RecordingIdentity(phone,mapping.optString("display_alias"))
    }
    fun mappingKey(recording: RecordingRef, state: JSONObject): String? = mappingKeyFromName(recording.name,state)
    fun mappingKeyFromName(name: String, state: JSONObject): String? {
        val alias=kr.callbrief.core.RecordingNames.callerAlias(name) ?: return null
        val scope=state.optString("treeUri"); if(scope.isBlank()) return null
        // Permit mappings require exact aliases, including emoji/punctuation/case.
        // The broader exclusion normalization must never be reused to approve identity.
        return PrivacyRules.hash(scope+"\n"+alias)
    }
    fun reusedCase(recording: RecordingRef, state: JSONObject): String? {
        val identity=identity(recording,state) ?: return null
        val mapping=state.optJSONObject("aliasMappings")?.optJSONObject(mappingKey(recording,state) ?: return null) ?: return null
        if(!mapping.optBoolean("reuse_enabled") || mapping.optBoolean("conflict") || mapping.optString("phone")!=identity.phone) return null
        return mapping.optString("case_id").takeIf { it.isNotBlank() }
    }
    fun blocked(recording: RecordingRef, state: JSONObject): Boolean {
        val rules = rules(state)
        if (rules.excluded(recording.name)) return true
        val identity = identity(recording,state) ?: return false
        return rules.excluded(identity.alias,identity.phone)
    }
    fun collectionDecision(recording:RecordingRef,state:JSONObject):LocalCollectionDecision {
        // Existing exclusions win over approvals. Neither an unknown boundary nor a saved mapping permits a read.
        if(blocked(recording,state)) return LocalCollectionDecision.EXCLUDE
        val boundary=kr.callbrief.core.ContactBoundary.parse(recording.name) ?: return LocalCollectionDecision.HOLD
        // A readable alias field alone cannot establish that an excluded phone is absent.
        // Require one explicit valid phone or an existing scoped, explicitly confirmed identity.
        if(identity(recording,state)==null) return LocalCollectionDecision.HOLD
        if(boundary.phones.isEmpty()) {
            // A legacy URI-only entry is not a current name/folder confirmation.
            val key=mappingKey(recording,state) ?: return LocalCollectionDecision.HOLD
            val mapping=state.optJSONObject("aliasMappings")?.optJSONObject(key) ?: return LocalCollectionDecision.HOLD
            if(!mapping.optBoolean("reuse_enabled") || mapping.optBoolean("conflict") ||
                mapping.optString("treeUri")!=state.optString("treeUri")) return LocalCollectionDecision.HOLD
        }
        return LocalCollectionDecision.ALLOW
    }
    fun requireCollectionAllowed(recording:RecordingRef,state:JSONObject) {
        check(collectionDecision(recording,state)==LocalCollectionDecision.ALLOW) {
            if(blocked(recording,state)) "EXCLUDED_BEFORE_READ" else "CONTACT_OR_IDENTITY_REVIEW_REQUIRED"
        }
    }
    fun requireAllowed(recording: RecordingRef, state: JSONObject): RecordingIdentity {
        require(!blocked(recording,state)) { "개인정보 제외 규칙으로 차단된 통화예요." }
        require(collectionDecision(recording,state)==LocalCollectionDecision.ALLOW) { "파일명의 번호·이름 범위 확인이 필요한 통화예요. 음성은 읽지 않았어요." }
        return requireNotNull(identity(recording,state)) { "상대방 번호 확인이 필요한 통화예요. 음성은 읽지 않았어요." }
    }
}
