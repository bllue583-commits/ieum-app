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
    // v3.8 r18-lock (DESIGN_V7 4.2-5): these two now live in the settings ([PERSONAL_KEY], written once by [migrate]); the
    // constants stay only as the source of that move and for a phone that has not run it yet. The original values are not
    // on the phone, so they cannot be salted: they keep matching in their old unsalted form (PrivacyRules compares both).
    // Remove them once every phone reports `security.saltedHash` (the move is done).
    const val PERSONAL_PHONE_HASH = "382e6bfad716a41aa161688852be0777cae22b3bea2ebebe3b635fd1b32eaead"
    const val PERSONAL_ALIAS_HASH = "1684ce349a07988c9ac5bd3572cccf2b7208da08820864b3bcda1c5c7401a4fc"
    /** Settings keys: the device salt (32 random bytes, 64 hex characters; never leaves the phone), the personal list, and the
     * version of the move ([migrate]). */
    const val SALT_KEY="numberSalt";const val PERSONAL_KEY="personalHashes";const val MIGRATED_KEY="numberSaltVersion"
    const val MIGRATION_VERSION=1
    /** The personal list as the code had it, in the settings' form ("legacy": unsalted, cannot be made again). */
    internal fun legacyPersonal():JSONArray = JSONArray()
        .put(JSONObject().put("type","phone").put("hash",PERSONAL_PHONE_HASH).put("legacy",true))
        .put(JSONObject().put("type","alias").put("hash",PERSONAL_ALIAS_HASH).put("length",2).put("legacy",true))
    fun salt(state: JSONObject): String? = state.optString(SALT_KEY).takeIf { PrivacyRules.validSalt(it) }
    fun rules(state: JSONObject): PrivacyRules {
        val phones = mutableSetOf<String>(); val aliases = mutableListOf<ExcludedAlias>()
        // Before the move, the code's list; after it, the settings' (the same two entries).
        val personal = state.optJSONArray(PERSONAL_KEY) ?: legacyPersonal()
        for (index in 0 until personal.length()) {
            val row = personal.optJSONObject(index) ?: continue
            val hash = row.optString("hash").takeIf { it.isNotBlank() } ?: continue
            if (row.optString("type") == "phone") { if (!state.optBoolean("personalPhoneDisabled")) phones.add(hash) }
            else if (!state.optBoolean("personalAliasDisabled")) aliases.add(ExcludedAlias(hash, row.optInt("length", 2)))
        }
        val custom = state.optJSONArray("exclusions") ?: JSONArray()
        for (index in 0 until custom.length()) {
            val row = custom.getJSONObject(index)
            if (row.optString("type") == "phone") phones.add(row.getString("hash"))
            else aliases.add(ExcludedAlias(row.getString("hash"), row.getInt("length")))
        }
        return PrivacyRules(phones, aliases, salt(state))
    }
    /** The fingerprint to keep for a phone key from now on: salted when the phone has its salt, else the old form. */
    fun numberHash(state: JSONObject, key: String): String = salt(state)?.let { PrivacyRules.salted(it, key) } ?: PrivacyRules.hash(key)
    /** [stored] (written by [numberHash], or before the salt) is the fingerprint of [key]. */
    fun sameNumber(state: JSONObject, stored: String, key: String): Boolean =
        stored == PrivacyRules.hash(key) || salt(state)?.let { stored == PrivacyRules.salted(it, key) } == true
    /** v3.8 r18-lock: makes the device salt and moves the personal list into the settings, in one settings write, once.
     * Safe to run at every start and to be cut off at any point: either the write happened (all of it) or nothing changed.
     * An existing salt is never replaced (fingerprints made with it would stop matching). Returns true when it wrote. */
    fun migrate(settings: kr.callbrief.SecureSettings, random: java.security.SecureRandom = java.security.SecureRandom()): Boolean {
        if (migrated(settings.snapshot())) return false
        var wrote = false
        settings.update { s -> wrote = migrateState(s, random) }
        return wrote
    }
    fun migrated(s: JSONObject) = salt(s) != null && s.has(PERSONAL_KEY) && s.optInt(MIGRATED_KEY, 0) >= MIGRATION_VERSION
    /** The move on one settings object (what [migrate] does inside its one write). True when it changed something. */
    internal fun migrateState(s: JSONObject, random: java.security.SecureRandom = java.security.SecureRandom()): Boolean {
        if (migrated(s)) return false
        if (salt(s) == null) s.put(SALT_KEY, ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) })
        if (!s.has(PERSONAL_KEY)) s.put(PERSONAL_KEY, legacyPersonal())
        s.put(MIGRATED_KEY, MIGRATION_VERSION)
        return true
    }
    /** Heartbeat `security`: [saltedHash] = the salt is there and the move is done (every fingerprint written from now on is
     * salted); [legacyHashes] = old unsalted fingerprints still matched because their values are not on the phone. Counts only. */
    fun heartbeat(state: JSONObject): JSONObject {
        val personal = state.optJSONArray(PERSONAL_KEY); val custom = state.optJSONArray("exclusions") ?: JSONArray()
        var legacy = 0
        for (array in listOfNotNull(personal ?: legacyPersonal(), custom)) for (i in 0 until array.length()) {
            if (array.optJSONObject(i)?.optBoolean("salted") != true) legacy++
        }
        return JSONObject().put("saltedHash", migrated(state))
            .put("legacyHashes", legacy)
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
    /** Exclusion rules win; otherwise every recording in the folder the user chose (with consent) is read.
     * Samsung names files by contact name, so a phone number in the name is not required. */
    fun collectionDecision(recording:RecordingRef,state:JSONObject):LocalCollectionDecision =
        if(blocked(recording,state)) LocalCollectionDecision.EXCLUDE else LocalCollectionDecision.ALLOW
    fun requireCollectionAllowed(recording:RecordingRef,state:JSONObject) {
        check(collectionDecision(recording,state)==LocalCollectionDecision.ALLOW) {
            if(blocked(recording,state)) "EXCLUDED_BEFORE_READ" else "CONTACT_OR_IDENTITY_REVIEW_REQUIRED"
        }
    }
    fun requireAllowed(recording: RecordingRef, state: JSONObject): RecordingIdentity {
        require(!blocked(recording,state)) { "개인정보 제외 규칙으로 차단된 통화예요." }
        require(collectionDecision(recording,state)==LocalCollectionDecision.ALLOW) { "파일명의 번호와 이름 범위 확인이 필요한 통화예요. 음성은 읽지 않았어요." }
        return identity(recording,state) ?: RecordingIdentity("", recording.name.substringBeforeLast('.'))
    }
}
