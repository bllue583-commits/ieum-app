package kr.callbrief.pipeline

import android.content.Context
import android.os.Build
import android.util.Log
import kr.callbrief.core.PhoneKey
import kr.callbrief.core.PrivacyRules
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One number's card (design v2.8 r9-2; card JSON v2 since v3.4, DESIGN_V4 4.1-6): who answers it, their company, the last
 * call and what is open with them, worked out ahead so the call card (v3.0) shows in well under a second. Holds no phone
 * number and no transcript: the number is only the key, summaries are cut to their first sentence of at most
 * [ContactCards.FIRST_CHARS] letters. v2 adds [lastDurationMs], [calls30d] and per task [Task.dueMs] and [Task.checkReason]
 * (r14-2's first line picks from them); a v1 card reads with those empty. */
internal data class ContactCard(
    val phoneKey:String,
    /** Everyone who answers this number (a shared office line has several), the one called last first. */
    val people:List<Person>,
    val company:Company?,
    val lastCallMs:Long?,
    /** "in", "out", "missed", "rejected" or null. */
    val lastDirection:String?,
    /** Latest work calls, texts and mail with their summary's first sentence (business ones only). */
    val recent:List<Recent>,
    val openTasks:List<Task>,
    val issues:List<IssueLine>,
    val texts:List<Text>,
    val updatedMs:Long,
    /** v3.3 (DESIGN_V4 3.1-5): a number only the call log knows (no person, company or summarised call): its phone-book name
     * (no number in it) and how often it called in the last 30 days. Null on every other card. */
    val thin:Thin?=null,
    /** v2: how long the last call with this number lasted (ms; the call log, else the recording), null when not known. */
    val lastDurationMs:Long?=null,
    /** v2: calls with this number in the call log over the last [ContactCards.THIN_DAYS] days (every card, not only thin ones). */
    val calls30d:Int=0,
    /** The card JSON version it was read from (written cards are always [VERSION]). */
    val version:Int=VERSION,
) {
    data class Thin(val name:String?,val calls30:Int)
    data class Person(val personId:String,val name:String,val role:String?,val isStaff:Boolean)
    data class Company(val id:String,val name:String,val trade:String?)
    /** [kind]: "통화", "문자" or "메일". [unsure]: the summary ended in "(숫자 확인 필요)" (taken off [summaryFirst]; the card shows a badge). */
    data class Recent(val atMs:Long,val kind:String,val summaryFirst:String,val unsure:Boolean=false)
    /** [requestedMs]: when it was asked for (the "요청 날짜" the screens show). [tentative]: "확인 필요" (v3.3). v2: [dueMs] the due
     * date as KST midnight (null when the item's date is not a plain date); [checkReason] why it is "확인 필요" (null when it is not). */
    data class Task(val id:String,val title:String,val requestedMs:Long,val status:String,val aiNote:String?,val tentative:Boolean=false,
                    val dueMs:Long?=null,val checkReason:String?=null)
    data class IssueLine(val title:String,val status:String)
    /** [dir]: "in" or "out". */
    data class Text(val atMs:Long,val dir:String,val first:String)

    fun toJson():JSONObject=JSONObject().put("v",VERSION)
        .put("people",JSONArray().apply { people.forEach { put(JSONObject().put("personId",it.personId).put("name",it.name).put("role",it.role ?: JSONObject.NULL).put("isStaff",it.isStaff)) } })
        .put("company",company?.let { JSONObject().put("id",it.id).put("name",it.name).put("trade",it.trade ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("lastCallMs",lastCallMs ?: JSONObject.NULL).put("lastDirection",lastDirection ?: JSONObject.NULL)
        .put("recent",JSONArray().apply { recent.forEach { put(JSONObject().put("atMs",it.atMs).put("kind",it.kind).put("summaryFirst",it.summaryFirst).apply { if(it.unsure) put("unsure",true) }) } })
        .put("openTasks",JSONArray().apply { openTasks.forEach { put(JSONObject().put("id",it.id).put("title",it.title).put("requestedMs",it.requestedMs).put("status",it.status).put("aiNote",it.aiNote ?: JSONObject.NULL)
            .put("dueMs",it.dueMs ?: JSONObject.NULL).put("checkReason",it.checkReason ?: JSONObject.NULL).apply { if(it.tentative) put("tentative",true) }) } })
        .put("issues",JSONArray().apply { issues.forEach { put(JSONObject().put("title",it.title).put("status",it.status)) } })
        .put("texts",JSONArray().apply { texts.forEach { put(JSONObject().put("atMs",it.atMs).put("dir",it.dir).put("first",it.first)) } })
        .put("updatedMs",updatedMs)
        .put("lastDurationMs",lastDurationMs ?: JSONObject.NULL).put("calls30d",calls30d)
        .apply { thin?.let { t -> put("thin",JSONObject().put("name",t.name ?: JSONObject.NULL).put("calls30",t.calls30)) } }

    companion object {
        /** The card JSON version written now (v3.4). */
        const val VERSION=2
        /** [Task.checkReason] codes: the number guard's reasons (r13-1) when known, else [CHECK_UNSURE]. */
        const val CHECK_CONFUSABLE="confusable";const val CHECK_UNSURE="unsure"
        private fun JSONObject.text(k:String)=if(isNull(k)) null else optString(k)
        private fun JSONObject.long(k:String)=if(isNull(k) || !has(k)) null else optLong(k)
        private fun <T> JSONObject.list(k:String,f:(JSONObject)->T):List<T> = optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(f) } }.orEmpty()
        fun parse(phoneKey:String,o:JSONObject)=ContactCard(phoneKey,
            o.list("people") { Person(it.optString("personId"),it.optString("name"),it.text("role"),it.optBoolean("isStaff")) },
            o.optJSONObject("company")?.let { Company(it.optString("id"),it.optString("name"),it.text("trade")) },
            o.long("lastCallMs"),o.text("lastDirection"),
            o.list("recent") { Recent(it.optLong("atMs"),it.optString("kind"),it.optString("summaryFirst"),it.optBoolean("unsure",false)) },
            o.list("openTasks") { Task(it.optString("id"),it.optString("title"),it.optLong("requestedMs"),it.optString("status"),it.text("aiNote"),it.optBoolean("tentative",false),
                it.long("dueMs"),it.text("checkReason")) },
            o.list("issues") { IssueLine(it.optString("title"),it.optString("status")) },
            o.list("texts") { Text(it.optLong("atMs"),it.optString("dir"),it.optString("first")) },
            o.optLong("updatedMs"),
            o.optJSONObject("thin")?.let { Thin(it.text("name"),it.optInt("calls30")) },
            o.long("lastDurationMs"),
            // v1 had the 30-day count on thin cards only.
            if(o.has("calls30d")) o.optInt("calls30d") else o.optJSONObject("thin")?.optInt("calls30") ?: 0,
            o.optInt("v",1))
    }
}

/** The contact-card store (design v2.8 r9-2): `contact-cards-v1.sealed`, sealed the same way as the call store
 * ([ProtectedDatabase]) but a separate, small file, with one table `contact_cards(phone_key PK, json, updated_ms)`.
 * [get] answers from memory first, then from the file. The call store tells [touch] which numbers changed after each
 * write; the cards of those numbers are worked out again together, off the caller's thread. Personal numbers and the
 * user's excluded numbers never get a card, and one they had goes.
 *
 * v3.4 (DESIGN_V4 4.1-2, r14-1): the same file also holds `blocked(phone_key PK)`: every personal number and every number the
 * user's exclusion rules catch, written whole with each [refresh] and [rebuildAll] (and again when the rules changed, see
 * [afterCall]), with the rules' fingerprint in `card_meta`. The ringing path reads only this file ([blockedState]), never the
 * call store. Cards of v1 are read as they are and written again as v2 at the next flush ([preload] asks for it). */
internal object ContactCards {
    const val DATABASE="contact-cards-v1"
    const val RECENT=3;const val TASKS=5;const val ISSUES=3;const val TEXTS=2
    /** Longest first sentence of a summary on a card. */
    const val FIRST_CHARS=40
    /** Touches within this time are worked out together. */
    const val FLUSH_DELAY_MS=1_500L
    private const val TAG="ContactCards"
    private const val SCHEMA="CREATE TABLE IF NOT EXISTS contact_cards(phone_key TEXT PRIMARY KEY,json TEXT NOT NULL,updated_ms INTEGER NOT NULL)"
    private const val SCHEMA_STEP="contactCards.schema.v1"
    /** v3.4: the blocked numbers and the fingerprint of the rules they were worked out with. */
    private val SCHEMA2=listOf("CREATE TABLE IF NOT EXISTS blocked(phone_key TEXT PRIMARY KEY)",
        "CREATE TABLE IF NOT EXISTS card_meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
    private const val SCHEMA2_STEP="contactCards.schema.v2"
    /** card_meta key: the fingerprint of the exclusion rules [blocked] was written with (absent: never written). */
    private const val META_RULES="blockedRules"

    private val cache=ConcurrentHashMap<String,ContactCard>()
    /** Every card of the file is in [cache]: a key not there has no card. */
    @Volatile private var loadedAll=false
    @Volatile private var appContext:Context?=null
    private val lock=Any()
    private var port:AndroidSqlPort?=null
    private var portPath:String?=null
    private val pending=ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pendingAll=false
    private val scheduled=AtomicBoolean(false)
    private val executor by lazy { Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"ieum-contact-cards").apply { isDaemon=true } } }
    /** Off in host tests: touches then wait for [flushPending], and nothing opens the file behind a test's back. */
    @Volatile internal var background:Boolean=Build.FINGERPRINT!="robolectric"
    /** How long the last full read of the file into memory took (ms), or -1. */
    @Volatile var lastPreloadMs:Long=-1;private set
    /** v3.4: how long the last open of this file took (ms), or -1 (heartbeat callCard.cardOpenMs). */
    @Volatile var lastOpenMs:Long=-1;private set
    /** v3.4: opens of this file in this process (host tests: at most one on the ringing path). */
    @Volatile var opens:Int=0;private set
    /** v3.4: the blocked table in memory once read whole (null: not read yet). */
    @Volatile private var blockedKeys:Set<String>?=null
    /** v3.4: the blocked table was written at least once (null: not known yet). */
    @Volatile private var blockedBuilt:Boolean?=null

    // ---- reading ----------------------------------------------------------------------------------------------
    /** The card of [phoneKey] (any spelling: "010-…", "+82 10-…"), or null when it has none. Memory first, then the file
     * (with the context last given to [preload], [touch] or [get]). */
    fun get(phoneKey:String?):ContactCard? {
        val key=PhoneKey.of(phoneKey) ?: return null
        cache[key]?.let { return it }
        if(loadedAll) return null
        val ctx=appContext ?: return null
        return runCatching { port(ctx).query("SELECT json FROM contact_cards WHERE phone_key=?",key).firstOrNull()?.let { r ->
            ContactCard.parse(key,JSONObject(r["json"] as String)).also { cache[key]=it } } }
            .onFailure { Log.w(TAG,"card read failed: ${it.javaClass.simpleName}") }.getOrNull()
    }
    fun get(context:Context,phoneKey:String?):ContactCard? { remember(context);return get(phoneKey) }
    /** Reads every card into memory (app start, the end of the hourly pass). Returns how many, or -1 when the file could not be read. */
    fun preload(context:Context):Int {
        remember(context)
        return runCatching {
            val t0=System.nanoTime()
            val rows=port(context).query("SELECT phone_key,json FROM contact_cards")
            val read=rows.mapNotNull { r -> val k=r["phone_key"] as? String ?: return@mapNotNull null
                runCatching { k to ContactCard.parse(k,JSONObject(r["json"] as String)) }.getOrNull() }.toMap()
            synchronized(lock) { cache.keys.retainAll(read.keys);cache.putAll(read);loadedAll=true }
            lastPreloadMs=(System.nanoTime()-t0)/1_000_000L
            Log.i(TAG,"preload cards=${read.size} ms=$lastPreloadMs")
            // v3.4: v1 cards are written again as v2 at the next flush.
            if(read.values.any { it.version<ContactCard.VERSION }) { pendingAll=true;schedule() }
            runCatching { readBlocked(context) }
            read.size
        }.onFailure { Log.w(TAG,"preload failed: ${it.javaClass.simpleName}") }.getOrDefault(-1)
    }
    /** App start and the end of the hourly pass: numbers touched and not yet worked out are done now, then every card is
     * read into memory. v3.4: a blocked table never written, or written with other rules, is written now. Does nothing in
     * host tests ([background] off). */
    fun warm(context:Context) {
        if(!background) return
        runCatching { if(pendingAll || pending.isNotEmpty()) flushPending(kr.callbrief.IeumApplication.store(context)) }
            .onFailure { Log.w(TAG,"flush failed: ${it.javaClass.simpleName}") }
        runCatching { if(blockedStale(context)) rewriteBlocked(kr.callbrief.IeumApplication.store(context)) }
            .onFailure { Log.w(TAG,"blocked not written: ${it.javaClass.simpleName}") }
        preload(context)
    }

    // ---- v3.4: the blocked numbers (DESIGN_V4 4.1-2) --------------------------------------------------------------------
    /** Whether [phoneKey] is personal or excluded, from this file only (the ringing path: no call store). Null when the table
     * was never written (the first call after the update: the caller asks the store as before); throws when the file cannot
     * be read (the caller then counts the number as personal). The first answer reads the whole small table into memory. */
    fun blockedState(context:Context,phoneKey:String):Boolean? {
        check(!blockedReadFault) { "blocked read fault" }
        remember(context)
        val key=PhoneKey.of(phoneKey) ?: return null
        blockedKeys?.let { return key in it }
        val keys=readBlocked(context) ?: return null
        return key in keys
    }
    /** The blocked table into memory; null when it was never written. Throws when the file cannot be read. */
    private fun readBlocked(context:Context):Set<String>? {
        val p=port(context)
        if(p.query("SELECT v FROM card_meta WHERE k=?",META_RULES).isEmpty()) { blockedBuilt=false;return null }
        val keys=p.query("SELECT phone_key FROM blocked").mapNotNull { it["phone_key"] as? String }.toSet()
        blockedKeys=keys;blockedBuilt=true
        return keys
    }
    /** The fingerprint of the user's exclusion rules (the settings keys [kr.callbrief.LocalPrivacy.rules] reads). */
    internal fun rulesFingerprint(settings:JSONObject):String =
        listOf("personalPhoneDisabled","personalAliasDisabled","exclusions").joinToString("|") { settings.opt(it)?.toString().orEmpty() }
            .let { kr.callbrief.core.PrivacyRules.hash(it) }
    /** The table was never written, or with other rules than the settings have now. */
    internal fun blockedStale(context:Context):Boolean {
        val p=port(context)
        val stored=p.query("SELECT v FROM card_meta WHERE k=?",META_RULES).firstOrNull()?.get("v") as? String ?: return true
        val now=runCatching { kr.callbrief.SecureSettings(context.applicationContext ?: context).snapshot() }.getOrNull() ?: return false
        return stored!=rulesFingerprint(now)
    }
    /** Writes the whole blocked table again from [store] (personal numbers and the rules), off the ringing path. */
    fun rewriteBlocked(store:CallRecordStore):Int {
        remember(store.cardContext)
        val index=keyIndex(store)
        return writeBlocked(store,index,blocked(store,index))
    }
    private fun writeBlocked(store:CallRecordStore,index:Map<String,Set<String>>,isBlocked:(String)->Boolean):Int {
        val personal=store.cardRows("SELECT phone_key FROM personal_numbers").mapNotNull { PhoneKey.of(it["phone_key"] as? String) }
        val keys=(index.keys.filter(isBlocked)+personal).toSet()
        val print=runCatching { rulesFingerprint(kr.callbrief.SecureSettings(store.cardContext).snapshot()) }.getOrDefault("")
        val p=port(store.cardContext)
        p.transaction {
            p.execute("DELETE FROM blocked")
            keys.forEach { p.execute("INSERT OR IGNORE INTO blocked(phone_key) VALUES(?)",it) }
            p.execute("INSERT OR REPLACE INTO card_meta(k,v) VALUES(?,?)",META_RULES,print);null }
        blockedKeys=keys;blockedBuilt=true
        return keys.size
    }
    /** After a call ([CallSession], IDLE): off the session's thread, the big store says whether the number has been found
     * personal since the table was written (the analysis remembers a number after two personal calls in a row), and a table
     * never written or written with other rules is written again. Does nothing in host tests ([background] off). */
    fun afterCall(context:Context,phoneKey:String?) {
        if(!background) return
        remember(context)
        executor.execute { runCatching { afterCallNow(context,phoneKey) }.onFailure { Log.w(TAG,"after call: ${it.javaClass.simpleName}") } }
    }
    /** [afterCall] on the caller's thread (host tests). */
    internal fun afterCallNow(context:Context,phoneKey:String?,store:CallRecordStore=kr.callbrief.IeumApplication.store(context)) {
        if(blockedStale(context)) { rewriteBlocked(store);return }
        val key=PhoneKey.of(phoneKey) ?: return
        if(blockedKeys?.contains(key)!=true && store.isPersonal(key)) {
            val p=port(context)
            p.transaction { p.execute("INSERT OR IGNORE INTO blocked(phone_key) VALUES(?)",key);null }
            blockedKeys=(blockedKeys ?: emptySet())+key
        }
    }

    // ---- what changed -------------------------------------------------------------------------------------------
    /** The call store changed what [keys] (stored numbers, any spelling) show. Worked out together on the cards thread a
     * moment later. Never throws: a card may only be late, the write that touched it stands. */
    fun touch(store:CallRecordStore,keys:()->Collection<String?>) {
        runCatching { remember(store.cardContext);keys().mapNotNull { PhoneKey.of(it) }.forEach { pending.add(it) };schedule() }
            .onFailure { Log.w(TAG,"touch failed: ${it.javaClass.simpleName}") }
    }
    fun touch(store:CallRecordStore,keys:Collection<String?>)=touch(store) { keys }
    /** Too much changed to say which numbers (the ledger, the morning tidy): every card is worked out again. */
    fun touchAll(store:CallRecordStore) { runCatching { remember(store.cardContext);pendingAll=true;schedule() } }
    /** Numbers waiting to be worked out (tests). */
    internal fun pendingKeys():Set<String> =pending.toSet()
    private fun schedule() {
        if(!background || !scheduled.compareAndSet(false,true)) return
        executor.schedule({
            scheduled.set(false)
            runCatching { appContext?.let { flushPending(kr.callbrief.IeumApplication.store(it)) } }
                .onFailure { Log.w(TAG,"flush failed: ${it.javaClass.simpleName}") }
        },FLUSH_DELAY_MS,TimeUnit.MILLISECONDS)
    }
    /** Works out every touched number now with [store]. Returns how many cards were written or removed. */
    fun flushPending(store:CallRecordStore):Int {
        val all=pendingAll;pendingAll=false
        val keys=pending.toSet();pending.removeAll(keys)
        return try { if(all) rebuildAll(store) else if(keys.isEmpty()) 0 else refresh(store,keys) }
        catch(e:Exception) { pending.addAll(keys);if(all) pendingAll=true;throw e }
    }

    // ---- working out -------------------------------------------------------------------------------------------
    /** Works out the cards of [phoneKeys] again from [store] (only these). A number that is personal, excluded or that
     * nothing is known about loses its card; any card of a personal number goes too. Returns how many changed. */
    fun refresh(store:CallRecordStore,phoneKeys:Collection<String?>):Int {
        val keys=phoneKeys.mapNotNull { PhoneKey.of(it) }.toSet()
        remember(store.cardContext)
        val index=keyIndex(store)
        val blocked=blocked(store,index)
        val now=System.currentTimeMillis()
        val out=keys.associateWith { k -> if(blocked(k)) null else build(store,k,(index[k].orEmpty()+k).toList(),now) }
        val personal=index.keys.filter { blocked(it) }.toSet()+keys.filter { blocked(it) }
        write(store.cardContext,out+personal.associateWith { null },replaceAll=false)
        writeBlocked(store,index,blocked)
        return out.size
    }
    /** Every card again (the migration, and the settings button "카드 다시 만들기" later). Returns how many cards there are now. */
    fun rebuildAll(store:CallRecordStore):Int {
        remember(store.cardContext)
        val index=keyIndex(store)
        val blocked=blocked(store,index)
        val now=System.currentTimeMillis()
        val cards=index.keys.filterNot(blocked).mapNotNull { k -> build(store,k,(index[k].orEmpty()+k).toList(),now)?.let { k to it } }.toMap()
        write(store.cardContext,cards,replaceAll=true)
        writeBlocked(store,index,blocked)
        return cards.size
    }

    /** Every stored spelling of each number, by its [PhoneKey]: rows written before the one key rule may hold another form. */
    private fun keyIndex(store:CallRecordStore):Map<String,Set<String>> =
        store.cardRows("""SELECT phone_key AS k FROM people_phones UNION SELECT phone_key FROM people WHERE phone_key IS NOT NULL
            UNION SELECT counterpart_key FROM call_records WHERE counterpart_key IS NOT NULL UNION SELECT number_key FROM call_log WHERE number_key IS NOT NULL
            UNION SELECT phone_key FROM personal_numbers""")
            .mapNotNull { r -> (r["k"] as? String)?.let { s -> PhoneKey.of(s)?.let { it to s } } }
            .groupBy({ it.first },{ it.second }).mapValues { it.value.toSet() }
    /** Personal numbers (any spelling) and the user's own exclusion rules. */
    private fun blocked(store:CallRecordStore,index:Map<String,Set<String>>):(String)->Boolean {
        val personal=store.cardRows("SELECT phone_key FROM personal_numbers").mapNotNull { PhoneKey.of(it["phone_key"] as? String) }.toSet()
        val rules=runCatching { kr.callbrief.LocalPrivacy.rules(kr.callbrief.SecureSettings(store.cardContext).snapshot()) }.getOrNull()
        return { k -> k in personal || index[k].orEmpty().any { PhoneKey.of(it) in personal } || runCatching { rules?.excluded(k,k)==true }.getOrDefault(false) }
    }
    private fun marks(n:Int)=List(n) { "?" }.joinToString(",",prefix="(",postfix=")")
    private fun Map<String,Any?>.str(k:String)=this[k] as? String
    private fun Map<String,Any?>.long(k:String)=(this[k] as? Number)?.toLong()
    /** The summary's own warning (rule 9) that a number may be wrong: on a card it becomes the "확인 필요" badge. */
    const val NUMBER_WARNING="(숫자 확인 필요)"
    /** A summary's first sentence, no phone number in it, at most [FIRST_CHARS] letters, cut at a space when it is longer
     * (v3.3: never inside a word, and without "…"). */
    internal fun firstSentence(text:String?):String {
        val t=PrivacyRules.removePhoneTokens(text.orEmpty()).replace(NUMBER_WARNING,"").replace(Regex("\\s+")," ").trim()
        val end=Regex("[.!?。](\\s|$)").find(t)?.range?.first?.plus(1) ?: t.length
        val s=t.substring(0,end).trim()
        if(s.length<=FIRST_CHARS) return s
        val cut=s.lastIndexOf(' ',FIRST_CHARS).takeIf { it>=FIRST_CHARS/2 } ?: FIRST_CHARS
        return s.substring(0,cut).trim()
    }
    private fun business(analysisJson:String?)=analysisJson?.let { j -> runCatching { JSONObject(j).optJSONObject("analysis")?.optBoolean("business",true) }.getOrNull() }!=false
    private fun direction(logType:Int?)=when(logType) { 1 -> "in";2 -> "out";3 -> "missed";5,6 -> "rejected";else -> null }

    /** v2 [ContactCard.Task.dueMs]: an item's date "YYYY-MM-DD" as KST midnight; null for anything else ("다음 주", empty). */
    internal fun dueMs(dueText:String?):Long? = dueText?.trim()?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        ?.let { runCatching { java.time.LocalDate.parse(it).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() }.getOrNull() }
    /** v2 [ContactCard.Task.checkReason]: why an item is "확인 필요". The store keeps the guard's reason only as the confusable
     * note ([ContactCard.CHECK_CONFUSABLE] when the item carries its note); any other tentative item is [ContactCard.CHECK_UNSURE]. */
    internal fun checkReason(tentative:Boolean,aiNote:String?):String? = when {
        !tentative -> null
        !aiNote.isNullOrBlank() -> ContactCard.CHECK_CONFUSABLE
        else -> ContactCard.CHECK_UNSURE
    }
    /** v2: the last call's length (call log first, else the recording) and the call log's count over [THIN_DAYS] days. */
    private fun callFacts(store:CallRecordStore,v:List<String>,now:Long):Pair<Long?,Int> {
        val inV=marks(v.size)
        val logDur=store.cardRows("SELECT duration_ms FROM call_log WHERE number_key IN $inV ORDER BY start_ms DESC LIMIT 1",*v.toTypedArray()).firstOrNull()?.long("duration_ms")
        val dur=logDur ?: store.cardRows("""SELECT duration_ms FROM call_records WHERE counterpart_key IN $inV AND recording_id NOT LIKE 'sms:%' AND recording_id NOT LIKE 'mail:%'
            ORDER BY COALESCE(started_ms,created_ms) DESC LIMIT 1""",*v.toTypedArray()).firstOrNull()?.long("duration_ms")
        val n=store.cardRows("SELECT COUNT(*) AS n FROM call_log WHERE number_key IN $inV AND start_ms>=CAST(? AS INTEGER)",*(v+listOf((now-THIN_DAYS*86_400_000L).toString())).toTypedArray())
            .firstOrNull()?.long("n")?.toInt() ?: 0
        return dur?.takeIf { it>=0 } to n
    }

    /** The card of number [key] whose stored spellings are [v]; null when nothing worth a card is known (no person, company or
     * work record) and the call log has no call with it either (then a thin card, v3.3). */
    private fun build(store:CallRecordStore,key:String,v:List<String>,now:Long):ContactCard? {
        val inV=marks(v.size)
        val people=store.cardRows("""SELECT p.id,p.name,p.role,p.is_staff,p.company_id,
            (SELECT MAX(COALESCE(c.started_ms,c.created_ms)) FROM call_links l JOIN call_records c ON c.record_id=l.record_id WHERE l.person_id=p.id) AS last_ms
            FROM people p WHERE p.name!='' AND (p.phone_key IN $inV OR p.id IN (SELECT person_id FROM people_phones WHERE phone_key IN $inV))
            ORDER BY COALESCE(last_ms,0) DESC,p.updated_ms DESC""",*(v+v).toTypedArray())
        val personIds=people.mapNotNull { it.str("id") }
        // The calls of this number, and those linked to its people (a person may call from another line too).
        val (mine,mineArgs)=if(personIds.isEmpty()) "c.counterpart_key IN $inV" to v
            else "(c.counterpart_key IN $inV OR c.record_id IN (SELECT record_id FROM call_links WHERE person_id IN ${marks(personIds.size)}))" to v+personIds
        val companyId=people.firstOrNull { it.long("is_staff")!=1L && it.str("company_id")!=null }?.str("company_id")
            ?: store.cardRows("""SELECT l.company_id FROM call_links l JOIN call_records c ON c.record_id=l.record_id WHERE c.counterpart_key IN $inV AND l.company_id IS NOT NULL
                ORDER BY COALESCE(c.started_ms,c.created_ms) DESC LIMIT 1""",*v.toTypedArray()).firstOrNull()?.str("company_id")
        val company=companyId?.let { id -> store.cardRows("SELECT id,name,trade FROM companies WHERE id=?",id).firstOrNull() }
            ?.let { ContactCard.Company(it.str("id")!!,it.str("name").orEmpty(),it.str("trade")) }
        val recent=store.cardRows("""SELECT COALESCE(c.started_ms,c.created_ms) AS at,c.recording_id,c.summary,c.analysis_json FROM call_records c
            WHERE $mine AND c.state='ANALYZED' AND c.summary IS NOT NULL AND c.summary!='' ORDER BY at DESC LIMIT 12""",*mineArgs.toTypedArray())
            .filter { business(it.str("analysis_json")) }
            .mapNotNull { r -> firstSentence(r.str("summary")).takeIf { it.isNotEmpty() }?.let { ContactCard.Recent(r.long("at") ?: 0,MessageKind.of(r.str("recording_id").orEmpty()),it,
                r.str("summary").orEmpty().contains(NUMBER_WARNING)) } }
            .take(RECENT)
        if(people.isEmpty() && company==null && recent.isEmpty()) return thin(store,key,v,now)
        val logLast=store.cardRows("SELECT start_ms,type FROM call_log WHERE number_key IN $inV ORDER BY start_ms DESC LIMIT 1",*v.toTypedArray()).firstOrNull()
        val recordLast=store.cardRows("""SELECT COALESCE(c.started_ms,c.created_ms) AS at,x.direction FROM call_records c LEFT JOIN call_record_log x ON x.record_id=c.record_id
            WHERE c.counterpart_key IN $inV AND c.recording_id NOT LIKE 'sms:%' AND c.recording_id NOT LIKE 'mail:%' ORDER BY at DESC LIMIT 1""",*v.toTypedArray()).firstOrNull()
        val (lastMs,lastDir)=when {
            logLast!=null && (recordLast==null || (logLast.long("start_ms") ?: 0)>=(recordLast.long("at") ?: 0)-120_000L) ->
                logLast.long("start_ms") to direction(logLast.long("type")?.toInt())
            recordLast!=null -> recordLast.long("at") to when(recordLast.str("direction")) { "받은 전화" -> "in";"건 전화" -> "out";else -> null }
            else -> null to null
        }
        val tasks=store.cardRows("""SELECT i.id,i.title,i.status,i.created_ms,i.ai_note,i.tentative,i.due_text,
            CASE WHEN i.id IN (SELECT b.item_id FROM call_task_bindings b JOIN call_records c ON c.record_id=b.record_id WHERE $mine) THEN 0 ELSE 1 END AS far
            FROM workflow_items i WHERE i.kind='TODO' AND i.status NOT IN ('done','merged','wrong') AND (
              i.id IN (SELECT b.item_id FROM call_task_bindings b JOIN call_records c ON c.record_id=b.record_id WHERE $mine)
              OR i.id IN (SELECT item_id FROM task_companies WHERE company_id=?))
            ORDER BY far,i.created_ms DESC LIMIT $TASKS""",*(mineArgs+mineArgs+listOf(companyId ?: "")).toTypedArray())
            .map { val tentative=it.long("tentative")==1L
                ContactCard.Task(it.str("id")!!,PrivacyRules.removePhoneTokens(it.str("title").orEmpty()).trim(),it.long("created_ms") ?: 0,it.str("status").orEmpty(),it.str("ai_note"),tentative,
                    dueMs(it.str("due_text")),checkReason(tentative,it.str("ai_note"))) }
        val issues=company?.let { c -> store.issues(c.name).take(ISSUES).map { ContactCard.IssueLine(it.title,it.status) } }.orEmpty()
        val texts=store.cardRows("""SELECT COALESCE(c.started_ms,c.created_ms) AS at,c.recording_id,c.summary,c.transcript,c.analysis_json FROM call_records c
            WHERE c.counterpart_key IN $inV AND (c.recording_id LIKE 'sms:%' OR c.recording_id LIKE 'mail:%') ORDER BY at DESC LIMIT 8""",*v.toTypedArray())
            .filter { business(it.str("analysis_json")) }.mapNotNull { r ->
                // A text thread is stored as "[HH:mm 받은 문자] …" lines: the latest line tells the direction.
                val last=r.str("transcript").orEmpty().lines().lastOrNull { it.isNotBlank() }.orEmpty()
                val dir=if(last.contains("보낸 문자")) "out" else "in"
                val first=firstSentence(r.str("summary")).ifEmpty { firstSentence(last.substringAfter("] ",last)) }
                first.takeIf { it.isNotEmpty() }?.let { ContactCard.Text(r.long("at") ?: 0,dir,it) } }.take(TEXTS)
        val cardPeople=people.map { ContactCard.Person(it.str("id")!!,it.str("name").orEmpty(),it.str("role"),it.long("is_staff")==1L) }
        val (lastDuration,calls30)=callFacts(store,v,now)
        return ContactCard(key,cardPeople,company,lastMs,lastDir,recent,tasks,issues,texts,now,lastDurationMs=lastDuration,calls30d=calls30)
    }

    /** Days of the call log a thin card counts calls over. */
    const val THIN_DAYS=30L
    /** The thin card of a number only the call log knows (DESIGN_V4 3.1-5): its last call, how many in [THIN_DAYS] days, and the
     * phone-book name it had (numbers taken out); null when the log has no call with it either. Personal and excluded numbers
     * never get here ([blocked]). */
    private fun thin(store:CallRecordStore,key:String,v:List<String>,now:Long):ContactCard? {
        val inV=marks(v.size)
        val last=store.cardRows("SELECT start_ms,type,name,duration_ms FROM call_log WHERE number_key IN $inV ORDER BY start_ms DESC LIMIT 1",*v.toTypedArray()).firstOrNull() ?: return null
        val n=store.cardRows("SELECT COUNT(*) AS n FROM call_log WHERE number_key IN $inV AND start_ms>=CAST(? AS INTEGER)",*(v+listOf((now-THIN_DAYS*86_400_000L).toString())).toTypedArray())
            .firstOrNull()?.long("n")?.toInt() ?: 0
        val name=CallRecordStore.noNumber(last.str("name"))?.replace(Regex("\\s+")," ")?.trim()?.take(30)
        return ContactCard(key,emptyList(),null,last.long("start_ms"),direction(last.long("type")?.toInt()),
            emptyList(),emptyList(),emptyList(),emptyList(),now,ContactCard.Thin(name,n),lastDurationMs=last.long("duration_ms")?.takeIf { it>=0 },calls30d=n)
    }

    // ---- the file ------------------------------------------------------------------------------------------------
    private fun remember(context:Context) { appContext=context.applicationContext ?: context }
    private fun port(context:Context):AndroidSqlPort = synchronized(lock) {
        val app=context.applicationContext ?: context
        val path=ProtectedDatabase.file(app,DATABASE).canonicalPath
        port?.let { if(portPath==path && it.usable()) return it }
        port?.let { runCatching { it.close() } };port=null
        if(portPath!=path) { cache.clear();loadedAll=false;blockedKeys=null;blockedBuilt=null }
        val t0=System.nanoTime()
        val p=AndroidSqlPort(app,DATABASE,SCHEMA.byteInputStream())
        opens++
        try {
            if(p.needs(SCHEMA_STEP)) p.transaction { p.execute(SCHEMA);p.markDone(SCHEMA_STEP);null }
            if(p.needs(SCHEMA2_STEP)) p.transaction { SCHEMA2.forEach { p.execute(it) };p.markDone(SCHEMA2_STEP);null }
        } catch(e:Exception) { p.close();throw e }
        lastOpenMs=(System.nanoTime()-t0)/1_000_000L
        port=p;portPath=path;p
    }
    /** Writes [cards] in one sealed transaction (null removes that number's card); [replaceAll] drops every other card. */
    internal fun write(context:Context,cards:Map<String,ContactCard?>,replaceAll:Boolean) {
        val p=port(context)
        p.transaction {
            if(replaceAll) p.execute("DELETE FROM contact_cards")
            cards.forEach { (k,c) -> if(c==null) p.execute("DELETE FROM contact_cards WHERE phone_key=?",k)
                else p.execute("INSERT OR REPLACE INTO contact_cards(phone_key,json,updated_ms) VALUES(?,?,?)",k,c.toJson().toString(),c.updatedMs) };null }
        synchronized(lock) {
            if(replaceAll) { cache.clear();loadedAll=true }
            cards.forEach { (k,c) -> if(c==null) cache.remove(k) else cache[k]=c }
        }
    }
    /** Lets the file and the memory copy go (memory trim, tests). Touched numbers stay waiting. */
    fun shutdown() = synchronized(lock) {
        port?.let { runCatching { it.close() } };port=null;portPath=null
        cache.clear();loadedAll=false;blockedKeys=null;blockedBuilt=null
    }
    /** Forgets touched numbers (tests). */
    internal fun clearPending() { pending.clear();pendingAll=false }
    /** Every card is to be worked out again at the next flush (tests). */
    internal val wholeRebuildPending:Boolean get()=pendingAll
    /** Host tests: the blocked table cannot be read. */
    @Volatile internal var blockedReadFault=false
}
