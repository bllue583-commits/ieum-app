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

/** v4.1 r22-1 (design E9): a call recording that did not find its call-log entry is held against the personal and excluded
 * numbers by the person its file name names before it goes up; the paired path stays as it was. Every name and number
 * here is made up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R22PrivacyTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val min=60_000L
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
    private fun fmt(ms:Long)=DateTimeFormatter.ofPattern("yyMMdd_HHmmss").withZone(CallRecordStore.KST).format(Instant.ofEpochMilli(ms))
    private fun file(who:String,at:Long)="통화 녹음 ${who}_${fmt(at)}.m4a"
    private fun seed(id:String,name:String,lengthMs:Long) {
        val fileMs=kr.callbrief.core.RecordingNames.startedAt(name)!!.toEpochMilli()
        port().use { p -> p.transaction {
            p.execute("CREATE TABLE IF NOT EXISTS local_files(recording_id TEXT PRIMARY KEY,root_uri TEXT NOT NULL,actual_document_id TEXT NOT NULL,actual_uri TEXT NOT NULL,source_signature TEXT NOT NULL,plaintext_sha256 TEXT NOT NULL,filename_time_candidate_ms INTEGER,UNIQUE(root_uri,actual_document_id))")
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,modified_at_ms,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/mp4',10,?,0,0,1,1,1)",
                id,GeneralLocalStore.OWNER,id,name,fileMs+lengthMs)
            p.execute("INSERT INTO local_files VALUES(?,'content://generated/tree',?,?,'sig','x',?)",id,"doc_$id","content://generated/document/$id",fileMs);null } }
        CallRecordStore(context){ now }.use { db ->
            db.insert(CallTranscript(id,1,"a".repeat(64),fileMs,lengthMs,CallTranscriptionRunner.counterpart(name),"주말에 밥 먹자",emptyList(),"m","b".repeat(64),true)) }
    }
    private fun rid(id:String)="$id|1|${"a".repeat(64)}"
    private fun entry(id:Long,number:String,name:String?,at:Long,len:Long,excluded:Boolean=false)=FirstPass.LogEntry(id,number,name,at,len,CallLog.Calls.INCOMING_TYPE,excluded)
    private fun run(s:JSONObject,log:List<FirstPass.LogEntry>,inbox:FakeInbox=FakeInbox()):FakeInbox {
        CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),{ s },{ now },inbox,save={ e -> e(s) },callLog={ log },smsAllowed={ false },afterSend={}).runPending()
        return inbox
    }
    private fun settings()=JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,true)
    private fun sentJobs(inbox:FakeInbox)=inbox.files.keys.count { it.startsWith(CallInbox.INBOX+"/") }
    private val friend="01020009001";private val work="01020009002";private val family="01020009003"

    @Test fun anUnpairedRecordingOfAnExcludedPersonByNameIsForgottenNotSent() {
        val at=now-3*3_600_000L
        // The excluded entry is two hours from the file's time (no candidate in the window); only its name ties them.
        val log=listOf(entry(1,family,"우리집",at-2*3_600_000L,3*min,excluded=true))
        seed("x",file("우리집",at),3*min)
        val s=settings();val inbox=run(s,log)
        assertEquals(0,sentJobs(inbox))
        CallRecordStore(context){ now }.use { assertNull(it.record(rid("x"))) }
        assertEquals(1L,s.optLong(CallRecordStore.PRIVACY_N_KEPT_BY_NAME))
    }

    @Test fun anUnpairedRecordingOfAPersonalNumberByItsStoredNameIsKeptPersonal() {
        val at=now-3*3_600_000L
        // An older call of the personal number is in the stored call log under its phone-book name.
        val log=listOf(entry(1,friend,"친구 철수 😀",at-3*86_400_000L,4*min))
        CallRecordStore(context){ now }.use { it.reconcileCallLog(log);it.rememberPersonal(friend) }
        seed("p",file("친구철수",at),5*min)
        val s=settings();val inbox=run(s,log)
        assertEquals(0,sentJobs(inbox))
        CallRecordStore(context){ now }.use { db -> val r=db.record(rid("p"))!!
            assertEquals(CallRecordStore.ANALYZED,r.state);assertEquals(false,r.analysis?.business) }
        assertEquals(1L,s.optLong(CallRecordStore.PRIVACY_N_KEPT_BY_NAME))
    }

    @Test fun anUnpairedRecordingWhoseAliasPairedBeforeWithAPersonalNumberIsKeptPersonal() {
        val at=now-5*3_600_000L
        // Yesterday's call of the same alias found its entry (no name in the log): that number is personal now.
        val log=listOf(entry(1,friend,null,at-86_400_000L,2*min))
        seed("old",file("동네형",at-86_400_000L),2*min-2_000L)
        CallRecordStore(context){ now }.use { it.reconcileCallLog(log);it.rememberPersonal(friend) }
        seed("new",file("동네형",at),2*min)
        val s=settings();val inbox=run(s,log)
        assertEquals(0,sentJobs(inbox))
        CallRecordStore(context){ now }.use { assertEquals(false,it.record(rid("new"))!!.analysis?.business) }
    }

    @Test fun anUnpairedRecordingOfAnotherNameGoesUpAsBefore() {
        val at=now-3*3_600_000L
        val log=listOf(entry(1,family,"우리집",at-2*3_600_000L,3*min,excluded=true))
        CallRecordStore(context){ now }.use { it.rememberPersonal(friend) }
        seed("w",file("김과장",at),3*min)
        seed("n","Call_${fmt(at+min)}.m4a",3*min) // nothing in the file name: it goes up too (the analysis decides, E9)
        val s=settings();val inbox=run(s,log)
        assertEquals(2,sentJobs(inbox))
        assertEquals(2L,s.optLong(CallRecordStore.PRIVACY_N_UPLOADED_UNLINKED))
        assertEquals(0L,s.optLong(CallRecordStore.PRIVACY_N_KEPT_BY_NAME))
    }

    @Test fun thePairedPathIsAsBefore() {
        val at=now-3*3_600_000L
        // Paired with a work number: sent and not counted as unpaired; paired with an excluded entry: forgotten.
        val log=listOf(entry(1,work,"김과장",at,3*min),entry(2,family,"우리집",at+30*min,3*min,excluded=true))
        seed("w",file("김과장",at+5_000L),3*min-2_000L)
        seed("f",file("우리집",at+30*min+5_000L),3*min-2_000L)
        val s=settings();val inbox=run(s,log)
        assertEquals(1,sentJobs(inbox))
        assertEquals(0L,s.optLong(CallRecordStore.PRIVACY_N_UPLOADED_UNLINKED))
        CallRecordStore(context){ now }.use { assertNull(it.record(rid("f")));assertEquals(work,it.record(rid("w"))!!.counterpartKey) }
    }

    @Test fun theSamePersonInTheWindowPairsEvenWhenTheLengthsDisagree() {
        val at=now-3*3_600_000L
        // 3 minutes in the log, 7 in the recording (ringing kept, a held call): before r22 this was "length" and went up
        // unpaired; an excluded person's call is now paired, so forgotten.
        val log=listOf(entry(1,family,"우리집",at,3*min,excluded=true))
        seed("f",file("우리집",at+5_000L),7*min)
        val saved=JSONObject()
        CallRecordStore(context,{ now },CallRecordStore.SettingsEdit { e -> e(saved) }).use { it.reconcileCallLog(log) }
        CallRecordStore(context){ now }.use { assertNull(it.record(rid("f"))) }
        val last=JSONObject(saved.getString(PipelineSettingsKeys.CALLLOG_MATCH_STATS)).getJSONObject("last")
        assertEquals(1,last.getInt("byPartyLength"));assertEquals(0,last.getInt("length"))
    }

    @Test fun aLengthFailureIsBinnedByTheLengthGap() {
        val at=now-3*3_600_000L
        // Nothing in the file name, one entry in the window whose length is 2 minutes off: still "length", binned at +120 s.
        val r=FirstPass.RecordForMatch("r",at,null,null,5*min)
        var st:FirstPass.MatchStats?=null
        FirstPass.match(listOf(r),listOf(entry(1,work,"김과장",at,3*min))) { st=it }
        assertEquals(1,st!!.length);assertEquals(mapOf("120" to 1),st!!.lengthGapSec)
    }

    @Test fun aCallSentBeforeItsExcludedEntryWasFoundCountsAsKeptByLink() {
        val at=now-3*3_600_000L
        seed("f",file("우리집",at+5_000L),3*min)
        val s=settings();val inbox=run(s,emptyList())
        assertEquals(1,sentJobs(inbox))
        // The next pass reads the call log with the entry: the call is forgotten and counted as found after it went up.
        run(s,listOf(entry(1,family,"우리집",at,3*min,excluded=true)))
        CallRecordStore(context){ now }.use { assertNull(it.record(rid("f"))) }
        assertEquals(1L,s.optLong(CallRecordStore.PRIVACY_N_KEPT_BY_LINK))
    }

    @Test fun namesMatchWithoutSpacesEmojiOrPunctuation() {
        assertTrue(CallRecordStore.samePrivacyName("친구 철수 😀","친구철수"))
        assertTrue(CallRecordStore.samePrivacyName("우리집!","우리 집"))
        assertFalse(CallRecordStore.samePrivacyName("김과장","우리집"))
        val (numbers,names)=CallRecordStore.fileParty("010-2000-9003",CallRecordStore.counterpartKey("010-2000-9003"),"Call recording 010-2000-9003_251105_120000.m4a")
        assertEquals(setOf(family),numbers);assertTrue(names.isEmpty())
    }
}
