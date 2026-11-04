package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Subscription mode: transcript goes into the inbox once, the scheduled task's result is applied and removed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallInboxTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private var now=1_793_300_000_000L
    private class FakeInbox:CallInboxStore {
        val files=LinkedHashMap<String,String>();var failNext=false
        override fun put(path:String,json:String){ if(failNext){failNext=false;throw java.io.IOException("x")};files[path]=json }
        override fun list(dir:String)=files.keys.filter{it.startsWith("$dir/")}.map{it.removePrefix("$dir/") to "sha-$it"}
        override fun get(path:String)=files.getValue(path)
        override fun delete(path:String,sha:String){ assertEquals("sha-$path",sha);files.remove(path) }
    }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES('rec1',?,'g1','rec1','rec1.m4a','audio/mp4',10,0,0,1,1,1)",GeneralLocalStore.OWNER)
            null
        } }
    }
    private fun runner(inbox:FakeInbox,consent:Boolean=true)=CallRecordAnalysisRunner(context,ClaudeCallAnalyzer(),
        { JSONObject().put(PipelineSettingsKeys.CONSENT_GRANTED,consent) },{ now },inbox)

    @Test fun sendsOnceThenAppliesResultAndCleansUp() {
        val text=ClaudeCallAnalyzerTest.TRANSCRIPT
        val t=CallTranscript("rec1",1,"a".repeat(64),now,125_000,"김과장",text,listOf(CallTranscript.Segment(0,5000,text)),"m","b".repeat(64),true)
        val id=CallRecordStore(context){ now }.use { it.insert(t);CallRecordStore.recordId(t) }
        val inbox=FakeInbox()
        assertTrue(runner(inbox,consent=false).runPending())
        assertTrue(inbox.files.isEmpty())
        assertEquals(CallRecordStore.WAITING_CONSENT,CallRecordStore(context).use { it.record(id)!!.state })

        assertTrue(runner(inbox).runPending())
        val job=inbox.files.entries.single();assertTrue(job.key.startsWith("inbox/"))
        assertFalse("no names or numbers in paths",job.key.contains("rec1")||job.key.contains("김과장"))
        val o=JSONObject(job.value);assertEquals(CallInbox.recordKey(id),o.getString("id"));assertFalse(job.value.contains("rec1|"));assertTrue(o.getString("message").contains(text));assertTrue(o.has("schema"))
        runner(inbox).runPending();assertEquals("not re-sent within 6 h",1,inbox.files.size)

        // The scheduled task answers; an invented quote is dropped by the same filter as the API path.
        inbox.files.remove(job.key)
        val analysis=ClaudeCallAnalyzerTest.analysis(JSONArray()
            .put(ClaudeCallAnalyzerTest.task("create",null,"견적서 메일 보내기","견적서는 내일까지 메일로 보내드릴게요",due="2026-10-30"))
            .put(ClaudeCallAnalyzerTest.task("create",null,"지어낸 일","자막에 없는 말")))
        inbox.files["results/"+CallInbox.fileName(id)]=JSONObject().put("id",id).put("analysis",analysis).toString()
        assertTrue(runner(inbox).runPending())
        assertTrue(inbox.files.isEmpty())
        val r=CallRecordStore(context).use { it.record(id)!! }
        assertEquals(CallRecordStore.ANALYZED,r.state)
        assertEquals(listOf("견적서 메일 보내기"),CallRecordStore(context).use { it.openItems().map { i->i.title } })
    }

    @Test fun networkFailureIsRetriedLater() {
        val text=ClaudeCallAnalyzerTest.TRANSCRIPT
        val t=CallTranscript("rec1",1,"a".repeat(64),now,1000,null,text,emptyList(),"m","b".repeat(64),true)
        CallRecordStore(context){ now }.use { it.insert(t) }
        val inbox=FakeInbox().apply { failNext=true }
        assertFalse(runner(inbox).runPending())
        assertTrue(runner(inbox).runPending());assertEquals(1,inbox.files.size)
    }

    @Test fun updateIsDownloadedVerifiedAndOfferedOnlyWhenNewer() {
        val inbox=FakeInbox()
        val apk="fake apk bytes"
        val sha=java.security.MessageDigest.getInstance("SHA-256").digest(apk.toByteArray()).joinToString(""){"%02x".format(it)}
        inbox.files["app/ieum.apk"]=apk
        inbox.files["app/version.json"]=JSONObject().put("versionCode",kr.callbrief.BuildConfig.VERSION_CODE).put("apk","app/ieum.apk").put("sha256",sha).toString()
        assertFalse(CallUpdate.check(context,inbox));assertNull(CallUpdate.ready(context))
        inbox.files["app/version.json"]=JSONObject().put("versionCode",kr.callbrief.BuildConfig.VERSION_CODE+1).put("versionName","새 버전").put("apk","app/ieum.apk").put("sha256","0".repeat(64)).toString()
        assertTrue(runCatching { CallUpdate.check(context,inbox) }.isFailure);assertNull("bad checksum is never offered",CallUpdate.ready(context))
        inbox.files["app/version.json"]=JSONObject().put("versionCode",kr.callbrief.BuildConfig.VERSION_CODE+1).put("versionName","새 버전").put("apk","app/ieum.apk").put("sha256",sha).put("notes","할 일 화면 개선").toString()
        assertTrue(CallUpdate.check(context,inbox))
        assertEquals("할 일 화면 개선",CallUpdate.ready(context)!!.notes)
        assertEquals(apk,CallUpdate.file(context).readText())
    }
}
