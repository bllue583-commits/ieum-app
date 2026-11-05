package kr.callbrief.pipeline

import android.content.Context
import android.provider.CallLog
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.format.DateTimeFormatter

/** r12-7 (DESIGN_V4 2-2, user 11/5 "녹음 파일명에 정보가 있잖아"): recordings pair with the call log by their own time (the
 * recorder's file name), the person the file name names comes first, records stored before the fix pair on the next pass.
 * Every number and name here is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordingPairTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val min=60_000L
    /** 2026-11-05 18:00 KST: "today" for the new-recording path. */
    private val now=Instant.parse("2026-11-05T09:00:00Z").toEpochMilli()
    @Before fun setup() { context=RuntimeEnvironment.getApplication() }

    private class FakeInbox:CallInboxStore {
        val files=LinkedHashMap<String,String>()
        override fun put(path:String,json:String){ files[path]=json }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha-$it"}
        override fun get(path:String)=files.getValue(path)
        override fun delete(path:String,sha:String){ files.remove(path) }
    }
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun fmt(pattern:String,ms:Long)=DateTimeFormatter.ofPattern(pattern).withZone(CallRecordStore.KST).format(Instant.ofEpochMilli(ms))
    private fun number(i:Int)="0102000%04d".format(i)

    /** Every file name shape [kr.callbrief.core.RecordingNames] reads, the way Samsung and other recorders write them. */
    private fun fileName(i:Int,at:Long):String = when(i%10) {
        0 -> "통화 녹음 김과장_${fmt("yyMMdd_HHmmss",at)}.m4a"
        1 -> "통화녹음 이대리_${fmt("yyMMdd_HHmmss",at)}.m4a"
        2 -> "Call recording ${number(i).let { "${it.take(3)}-${it.substring(3,7)}-${it.takeLast(4)}" }}_${fmt("yyyyMMdd_HHmmss",at)}.m4a"
        3 -> "Call recording ${number(i)}_${fmt("yyMMdd_HHmmss",at)}.m4a"
        4 -> "통화 녹음 박소장_${fmt("yyyyMMdd_HHmmss",at)}.m4a"
        5 -> "Call recording 최반장_${fmt("yyMMdd_HHmmss",at)}.m4a"
        6 -> "통화 녹음 ${number(i).let { "${it.take(3)} ${it.substring(3,7)} ${it.takeLast(4)}" }}_${fmt("yyMMdd_HHmmss",at)}.m4a"
        7 -> "통화 녹음 정실장_${fmt("yyyy-MM-dd HH-mm-ss",at)}.m4a"
        8 -> "Call recording 한기사_${fmt("yyyy-MM-dd_HH-mm-ss",at)}.m4a"
        else -> "${number(i)}_${fmt("yyyyMMdd_HHmmss",at)}.m4a"
    }
    private val names=listOf("김과장","이대리",null,null,"박소장","최반장",null,"정실장","한기사",null)

    /** One recording as discovery and transcription leave it: the recording row (original_started_at_ms empty, as on the
     * phone), the file row with the time read from its name, and the call record. [recordTime]: what the record kept
     * (null: no start time). */
    private fun seed(id:String,name:String,lengthMs:Long,recordTime:(Long)->Long?) {
        val fileMs=kr.callbrief.core.RecordingNames.startedAt(name)!!.toEpochMilli()
        port().use { p -> p.transaction {
            p.execute("CREATE TABLE IF NOT EXISTS local_files(recording_id TEXT PRIMARY KEY,root_uri TEXT NOT NULL,actual_document_id TEXT NOT NULL,actual_uri TEXT NOT NULL,source_signature TEXT NOT NULL,plaintext_sha256 TEXT NOT NULL,filename_time_candidate_ms INTEGER,UNIQUE(root_uri,actual_document_id))")
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,modified_at_ms,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/mp4',10,?,0,0,1,1,1)",
                id,GeneralLocalStore.OWNER,id,name,fileMs+lengthMs+90*min)
            p.execute("INSERT INTO local_files VALUES(?,'content://generated/tree',?,?,'sig','x',?)",id,"doc_$id","content://generated/document/$id",fileMs);null } }
        CallRecordStore(context){ now }.use { db ->
            db.insert(CallTranscript(id,1,"a".repeat(64),recordTime(fileMs),lengthMs,CallTranscriptionRunner.counterpart(name),"철근 반입 일정 확인했습니다",emptyList(),"m","b".repeat(64),true)) }
    }
    private fun recordId(id:String)="$id|1|${"a".repeat(64)}"
    private fun linked():Map<String,Long> =port().use { p -> p.query("SELECT record_id,log_id FROM call_record_log").associate { it["record_id"].toString().substringBefore('|') to (it["log_id"] as Number).toLong() } }
    private fun key(id:String)=CallRecordStore(context){ now }.use { it.record(recordId(id))!!.counterpartKey }

    /** Ten Samsung-named recordings and their ten calls from [base]; each call has another person's call a minute away, and
     * one file is named by the end of the call (outside the usual window). Half the records kept no start time, half a wrong
     * one (the moment it was transcribed). */
    private fun tenCalls(prefix:String,base:Long):List<FirstPass.LogEntry> {
        val log=ArrayList<FirstPass.LogEntry>()
        for(i in 0 until 10) {
            val start=base+i*40*min;val len=(1+i%5)*min+7_000L
            val fileAt=if(i==4) start+len+5_000L else start+(5_000L*(i%3))
            val logId=1_000L*(prefix.length+1)+i
            log+=FirstPass.LogEntry(logId,number(i),names[i],start,len,if(i%2==0) CallLog.Calls.INCOMING_TYPE else CallLog.Calls.OUTGOING_TYPE)
            log+=FirstPass.LogEntry(logId+500,"0103999%04d".format(i),"다른사람",start+min,len,CallLog.Calls.OUTGOING_TYPE)
            seed("$prefix$i",fileName(i,fileAt),len-3_000L) { if(i%2==0) null else now+i*min }
        }
        return log
    }

    @Test fun tenSamsungRecordingsPairWithTheirTenCallsOnTheNewRecordingPath() {
        val log=tenCalls("new",now-9*3_600_000L)
        // The new-recording path: the call log trigger reconciles right after a call (CallLogDiscoveryWorker).
        val saved=JSONObject()
        CallRecordStore(context,{ now },CallRecordStore.SettingsEdit { e -> e(saved) }).use { it.reconcileCallLog(log) }
        val pairs=linked()
        for(i in 0 until 10) assertEquals("new$i",log[2*i].id,pairs["new$i"])
        // Each call carries the number of the person it was with, so the ledger can keep them.
        for(i in 0 until 10) assertEquals(number(i),key("new$i"))
        // The records kept no or a wrong time: the recording's own time is stored where there was none.
        CallRecordStore(context){ now }.use { db -> assertNotNull(db.record(recordId("new0"))!!.startedMs) }
        val last=JSONObject(saved.getString(PipelineSettingsKeys.CALLLOG_MATCH_STATS)).getJSONObject("last")
        assertEquals(10,last.getInt("matched"));assertEquals(1,last.getInt("byEnd"));assertEquals(1,last.getInt("byParty"))
        assertEquals(10,last.getInt("records24h"));assertEquals(0,last.getInt("open24h"))
    }

    @Test fun tenSamsungRecordingsPairWithTheirTenCallsOnTheFirstPassPath() {
        val base=now-10*86_400_000L
        val log=tenCalls("old",base)
        // The first-pass path: the hourly pass reconciles before it sends the old records in batches.
        val s=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true).put(PipelineSettingsKeys.FIRST_PASS_START_MS,now-3_600_000L)
        CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ now },FakeInbox(),save={ e -> e(s) },callLog={ log },smsAllowed={ false }).runPending()
        val pairs=linked()
        for(i in 0 until 10) assertEquals("old$i",log[2*i].id,pairs["old$i"])
    }

    @Test fun recordsStoredBeforeTheFixPairOnTheNextPass() {
        // The pass that did not pair them (no call log yet, as before the fix) leaves nothing behind that keeps them out.
        val log=tenCalls("re",now-9*3_600_000L)
        CallRecordStore(context){ now }.use { it.reconcileCallLog(emptyList()) }
        assertTrue(linked().isEmpty())
        CallRecordStore(context){ now }.use { it.reconcileCallLog(log) }
        assertEquals(10,linked().size)
    }

    @Test fun anEntryAlreadyTiedToARecordIsNotTakenAgain() {
        val start=now-3*3_600_000L
        val log=listOf(FirstPass.LogEntry(1,number(1),"이대리",start,2*min,CallLog.Calls.INCOMING_TYPE))
        seed("a",fileName(1,start+5_000L),2*min-3_000L) { it }
        CallRecordStore(context){ now }.use { it.reconcileCallLog(log) }
        assertEquals(mapOf("a" to 1L),linked())
        // A second recording of the same person a little later (a copy, a second recorder): the entry stays with the first.
        seed("b",fileName(1,start+20_000L),2*min-3_000L) { it }
        CallRecordStore(context){ now }.use { it.reconcileCallLog(log) }
        assertEquals(mapOf("a" to 1L),linked())
    }

    @Test fun aPhoneBookNameWithOneNumberGivesTheRecordThatNumberWithoutAnEntry() {
        val start=now-5*3_600_000L
        // The call itself is not in the log (deleted), but an earlier call of the same name is: one number under that name.
        val log=listOf(FirstPass.LogEntry(1,number(4),"박소장",start-2*86_400_000L,min,CallLog.Calls.INCOMING_TYPE),
            FirstPass.LogEntry(2,number(5),"최반장",start-2*86_400_000L,min,CallLog.Calls.INCOMING_TYPE),
            FirstPass.LogEntry(3,number(6),"최반장",start-3*86_400_000L,min,CallLog.Calls.INCOMING_TYPE))
        seed("one",fileName(4,start),2*min) { it }
        seed("two",fileName(5,start+3_600_000L),2*min) { it }
        val saved=JSONObject()
        CallRecordStore(context,{ now },CallRecordStore.SettingsEdit { e -> e(saved) }).use { it.reconcileCallLog(log) }
        assertTrue(linked().isEmpty())
        assertEquals(number(4),key("one"))
        // Two numbers under one name: the name stays, no number is guessed.
        assertEquals("최반장",key("two"))
        assertEquals(1,JSONObject(saved.getString(PipelineSettingsKeys.CALLLOG_MATCH_STATS)).getJSONObject("last").getInt("byPhoneBook"))
    }

    // ---- the rule itself ---------------------------------------------------------------------------------------------
    private val t=now
    private fun e(id:Long,n:String?,name:String?,at:Long,dur:Long=2*min)=FirstPass.LogEntry(id,n,name,at,dur,CallLog.Calls.INCOMING_TYPE)
    private fun r(id:String,at:Long,cp:String?,dur:Long=2*min)=FirstPass.RecordForMatch(id,at,cp,CallRecordStore.counterpartKey(cp),dur)

    @Test fun thePersonInTheFileNameIsTakenOutsideTheWindowOnTheSameDay() {
        // The same number 12 minutes away, another person's call right in the window: the file name decides.
        assertEquals(1L,FirstPass.match(listOf(r("a",t,"010-2000-0001")),listOf(e(1,number(1),null,t-12*min),e(2,number(2),null,t)))["a"]?.id)
        // By the phone-book name, spaces and titles aside.
        assertEquals(1L,FirstPass.match(listOf(r("a",t,"김 과장님")),listOf(e(1,number(1),"김과장",t+20*min),e(2,number(2),"이대리",t)))["a"]?.id)
        // More than 30 minutes, or another KST day: not the same call.
        assertNull(FirstPass.match(listOf(r("a",t,"010-2000-0001")),listOf(e(1,number(1),null,t-31*min)))["a"])
        val midnight=java.time.LocalDate.of(2026,11,6).atStartOfDay(CallRecordStore.KST).toInstant().toEpochMilli()
        assertNull(FirstPass.match(listOf(r("a",midnight+5*min,"010-2000-0001")),listOf(e(1,number(1),null,midnight-10*min)))["a"])
        // The length must still agree.
        assertNull(FirstPass.match(listOf(r("a",t,"010-2000-0001",dur=10*min)),listOf(e(1,number(1),null,t-5*min)))["a"])
    }

    @Test fun twoCallsOfThePersonThatTimeAndLengthCannotTellApartAreNotPaired() {
        var st:FirstPass.MatchStats?=null
        assertNull(FirstPass.match(listOf(r("a",t,"010-2000-0001")),listOf(e(1,number(1),null,t-10*min),e(2,number(1),null,t+10*min))) { st=it }["a"])
        assertEquals(1,st!!.several)
        // One of them in the usual window: that one.
        assertEquals(2L,FirstPass.match(listOf(r("a",t,"010-2000-0001")),listOf(e(1,number(1),null,t-10*min),e(2,number(1),null,t-30_000L)))["a"]?.id)
    }

    @Test fun withoutAPersonInTheFileNameTheUsualRuleHolds() {
        assertEquals(1L,FirstPass.match(listOf(r("a",t,null)),listOf(e(1,number(1),null,t-30_000L)))["a"]?.id)
        assertNull(FirstPass.match(listOf(r("a",t,null)),listOf(e(1,number(1),null,t-10*min)))["a"])
        assertNull(FirstPass.match(listOf(r("a",t,null)),listOf(e(1,number(1),null,t-30_000L),e(2,number(2),null,t)))["a"])
    }

    @Test fun missGapsAreSignedMinutesAndFarOnesAlsoInHours() {
        var st:FirstPass.MatchStats?=null
        val before=FirstPass.FROM_MS-30*86_400_000L
        FirstPass.match(listOf(r("late",t+25*min,null),r("early",t-35*min,null),r("far",t+3*3_600_000L+5*min,null),r("old",before,null)),
            listOf(e(1,number(1),null,t))) { st=it }
        assertEquals(mapOf("20" to 1,"-40" to 1,FirstPass.MISS_FAR to 1,FirstPass.MISS_BEFORE to 1),st!!.missGapMin)
        assertEquals(mapOf("3" to 1),st!!.missGapHour)
        assertEquals("60",FirstPass.missBin(60*min));assertEquals(FirstPass.MISS_FAR,FirstPass.missBin(60*min+1))
        // Through the heartbeat's counts filter: numbers only, every key kept.
        val json=PhoneStatus.counts(JSONObject(st!!.mergeInto(null,now)))
        assertEquals(1,json.getJSONObject("last").getJSONObject("missGapMin").getInt("-40"))
        assertEquals(1,json.getJSONObject("last").getJSONObject("missGapHour").getInt("3"))
    }
}
