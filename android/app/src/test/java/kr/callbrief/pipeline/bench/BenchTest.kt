package kr.callbrief.pipeline.bench

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.callbrief.pipeline.CallInboxStore
import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.CallTranscript
import kr.callbrief.pipeline.ClaudeCallAnalysis
import kr.callbrief.pipeline.ClaudeCallAnalyzerTest
import kr.callbrief.pipeline.ClaudeCallException
import kr.callbrief.pipeline.PhoneStatus
import kr.callbrief.pipeline.PipelineModelHttp
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** r18-bench (DESIGN_V7 4.4, brief r18-bench 7): the engine bench and the samples. Generated data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BenchTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val min=60_000L;private val day=86_400_000L
    private val now=1_800_000_000_000L
    @Before fun setup() { context=RuntimeEnvironment.getApplication() }

    // ---- normalized CER: the same numbers as tools/eval/ko_cer.py ----
    @Test fun koCerGivesThePythonNumbersOfTheParityFile() {
        val f=generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it,"tools/eval/ko_cer_parity.json") }.first { it.isFile }
        val cases=JSONObject(f.readText()).getJSONArray("cases")
        assertTrue(cases.length()>=15)
        for(i in 0 until cases.length()) { val c=cases.getJSONObject(i)
            assertEquals(c.getString("ref"),c.getString("refNorm"),KoCer.normalize(c.getString("ref")))
            assertEquals(c.getString("hyp"),c.getString("hypNorm"),KoCer.normalize(c.getString("hyp")))
            assertEquals(c.getString("ref"),c.getInt("edits"),KoCer.edits(c.getString("refNorm"),c.getString("hypNorm"))) }
    }
    @Test fun numbersNamesAndHallucinations() {
        val r=KoCer.normalize("철근 삼십만 원, 레미콘 두 대. 대한철강 김민수");val h=KoCer.normalize("철근 30만원 레미콘 3대 대한철강")
        assertEquals(1 to 2,KoCer.numberHits(r,h))
        assertEquals(1 to 2,KoCer.nameHits(r,h,listOf("대한철강","김민수","없는업체").map(KoCer::normalize)))
        assertTrue(KoCer.hallucination(800,"그."));assertFalse(KoCer.hallucination(1500,"그."));assertFalse(KoCer.hallucination(800,"네 알겠습니다"))
    }

    // ---- the switch ----
    @Test fun configMissingOrBrokenIsOff() {
        assertFalse(BenchConfigs.parse(null).first.on);assertFalse(BenchConfigs.parse("{oops").second.on);assertFalse(BenchConfigs.parse("{}").first.on)
        val (b,s)=BenchConfigs.parse("""{"bench":{"on":true,"candidates":["a","c","x","b"],"calls":99,"threads":{"c":3,"b":0}},"samples":{"on":true,"calls":12,"maxMinutes":40}}""")
        assertTrue(b.on);assertEquals(listOf("c","b","a"),b.candidates);assertEquals(BenchConfig.MAX_CALLS,b.calls);assertEquals(mapOf("c" to 3),b.threads)
        assertTrue(s.on);assertEquals(12,s.calls);assertEquals(40,s.maxMinutes)
    }

    private class Inbox(private val answer:()->String):CallInboxStore {
        val gets=ArrayList<String>();var writes=0
        override fun put(path:String,json:String) { writes++ }
        override fun replace(path:String,json:String) { writes++ }
        override fun list(dir:String):List<Pair<String,String>> = emptyList()
        override fun get(path:String):String { gets+=path;return answer() }
        override fun delete(path:String,sha:String) { writes++ }
    }
    private class Sched:BenchScheduler { val calls=ArrayList<String>()
        override fun benchOn(app:Context) { calls+="on" }
        override fun benchOff(app:Context) { calls+="off" } }

    /** The most important one (brief 7): with no app/config.json the app runs exactly as without the bench. */
    @Test fun offTheHeartbeatReadsOneFileAndDoesNothingElse() {
        val before=SecureSettings(context).snapshot().toString()
        val inbox=Inbox { throw ClaudeCallException("INBOX_HTTP_404") };val sched=Sched();var samplesCalled=0
        Bench.onHeartbeat(context,inbox,now,samples={ _,_,_ -> samplesCalled++ },work=sched)
        assertEquals(listOf(BenchConfigs.PATH),inbox.gets);assertEquals(0,inbox.writes)
        assertTrue(sched.calls.isEmpty());assertEquals(0,samplesCalled)
        val after=SecureSettings(context).snapshot()
        assertEquals(before,after.toString())
        assertFalse(after.has(Bench.CONFIG_KEY)||after.has(BenchState.KEY)||after.has(Samples.KEY))
        // A file that says off is the same.
        Bench.onHeartbeat(context,Inbox { """{"bench":{"on":false}}""" },now,samples={ _,_,_ -> samplesCalled++ },work=sched)
        assertTrue(sched.calls.isEmpty());assertEquals(0,samplesCalled);assertFalse(SecureSettings(context).snapshot().has(Bench.CONFIG_KEY))
        // The heartbeat has no bench or samples key then, and is the same file as before the bench existed.
        val s=SecureSettings(context).snapshot()
        assertNull(Bench.heartbeat(s));assertNull(Samples.heartbeat(s))
        val plain=PhoneStatus.Input(1_000L,"v3.8",1056,0,null,5,6,"READY","","",12)
        val o=PhoneStatus.build(plain.copy(bench=Bench.heartbeat(s),samples=Samples.heartbeat(s)))
        assertFalse(o.has("bench"));assertFalse(o.has("samples"));assertEquals(PhoneStatus.build(plain).toString(),o.toString())
    }
    @Test fun aNetworkErrorChangesNothingThisHour() {
        val sched=Sched()
        Bench.onHeartbeat(context,Inbox { """{"bench":{"on":true}}""" },now,samples={ _,_,_ -> },work=sched)
        assertEquals(listOf("on"),sched.calls)
        Bench.onHeartbeat(context,Inbox { throw IOException("INBOX_HTTP_503") },now,samples={ _,_,_ -> },work=sched)
        assertEquals(listOf("on"),sched.calls);assertTrue(Bench.config(SecureSettings(context).snapshot()).first.on)
        // Switched off in the mailbox: the work goes, the models go (benchOff), the numbers stay with state OFF.
        SecureSettings(context).update { it.put(BenchState.KEY,BenchState.fresh(1,now).toString()) }
        Bench.onHeartbeat(context,Inbox { throw ClaudeCallException("INBOX_HTTP_404") },now,samples={ _,_,_ -> },work=sched)
        assertEquals(listOf("on","off"),sched.calls)
        assertEquals("OFF",BenchState.parse(SecureSettings(context).snapshot().optString(BenchState.KEY)).getString("state"))
        assertFalse(Bench.config(SecureSettings(context).snapshot()).first.on)
    }

    // ---- when it may run ----
    @Test fun theGateOpensOnlyOnTheChargerOutOfACallWithNothingWaiting() {
        assertNull(BenchGate.stop(plugged=true,inCall=false,thermal=0,transcriptionBusy=false,queueWaiting=false))
        assertEquals("NOT_PLUGGED",BenchGate.stop(false,false,0,false,false))
        assertEquals("IN_CALL",BenchGate.stop(true,true,0,false,false))
        assertEquals("HOT",BenchGate.stop(true,false,BenchGate.HOT,false,false))
        assertEquals("TRANSCRIBING",BenchGate.stop(true,false,0,true,false))
        assertEquals("QUEUE",BenchGate.stop(true,false,0,false,true))
    }

    // ---- which calls ----
    private fun call(id:String,lengthMin:Double,agoDays:Double)=BenchCall(id,1,"a".repeat(64),"audio/mp4",1000,now-(agoDays*day).toLong(),(lengthMin*min).toLong())
    @Test fun theBenchTakesShortWorkCallsAndAtMostTwoLongOnes() {
        val calls=(1..30).map { call("m$it",2.0,it*0.1) }+listOf(call("s1",0.5,0.05),call("big1",12.0,1.0),call("big2",15.0,2.0),call("big3",20.0,0.5),call("mid",7.0,0.2))
        val p=BenchPick.bench(calls,20)
        assertEquals(20,p.size);assertEquals(2,p.count { it.durationMs>BenchPick.LONG_MS })
        assertTrue(p.none { it.recordingId=="s1" || it.recordingId=="mid" });assertTrue(p.any { it.recordingId=="big3" })
        assertEquals(listOf("lt1m","m1to3","m3to10","m10plus"),listOf(0.5,2.0,7.0,12.0).map { BenchPick.bucket((it*min).toLong()) })
    }
    @Test fun samplesStayInSevenDaysAndTheMinutesAndNeverRepeat() {
        val calls=listOf(call("old",3.0,8.0),call("a",4.0,1.0),call("b",5.0,1.5),call("c",3.0,2.0),call("l1",11.0,1.0),call("l2",12.0,2.0),call("l3",13.0,3.0))
        val p=BenchPick.samples(calls,now-7*day,setOf(calls[1].key),count=12,maxMinutes=40)
        assertFalse(p.any { it.recordingId=="old" || it.recordingId=="a" })
        assertTrue(p.sumOf { it.durationMs }<=40*min);assertTrue(p.count { it.durationMs>BenchPick.LONG_MS }<=2)
        assertEquals(listOf("b","c","l1","l2"),p.map { it.recordingId })
    }

    // ---- stop and go on ----
    @Test fun progressIsKeptPieceByPieceAndANewRunStartsAgain() {
        val s=BenchState.fresh(1,now)
        BenchState.setPlan(s,listOf(call("r1",2.0,0.1),call("r2",12.0,0.2)))
        assertEquals(0,BenchState.nextPiece(s,"c",0))
        BenchState.addPiece(s,"c",0,0,BenchState.Piece(120_000,6_000,5_000,100,8,4,4,2,1,1,12,false))
        assertEquals(1,BenchState.nextPiece(s,"c",0));assertFalse(BenchState.done(s,"c",0))
        val again=BenchState.parse(s.toString()) // a stop and a later run read it back
        assertEquals(1,BenchState.nextPiece(again,"c",0))
        BenchState.finish(again,"c",0,120_000,null);assertTrue(BenchState.done(again,"c",0))
        assertEquals(1,BenchState.tried(again,"b",1));assertEquals(2,BenchState.tried(again,"b",1))
        assertEquals(2,BenchState.plan(again).size);assertEquals("r2",BenchState.call(BenchState.plan(again)[1]).recordingId)
        assertFalse(BenchState.fresh(2,now).has("plan"))
    }

    // ---- what goes up ----
    @Test fun theHeartbeatHasNumbersAndCodesOnly() {
        val s=BenchState.fresh(1,now)
        BenchState.setPlan(s,listOf(call("통화 녹음 홍길동_01012345678_251029.m4a",2.0,0.1),call("r2",12.0,0.2)))
        BenchState.addPiece(s,"c",0,0,BenchState.Piece(120_000,6_000,9_000,200,10,4,3,2,2,1,15,false));BenchState.finish(s,"c",0,120_000,null)
        BenchState.addPiece(s,"b",0,0,BenchState.Piece(120_000,90_000,400_000,200,4,4,4,2,2,0,8,true));BenchState.finish(s,"b",0,120_000,null)
        BenchState.finish(s,"b",1,720_000,"ANDROID_STT_NOT_COMPLETED")
        BenchState.setModel(s,"c","READY")
        val hb=Bench.safe(BenchState.heartbeat(s,BenchConfig(on=true))!!)
        val text=hb.toString()
        assertFalse(Regex("[가-힣]").containsMatchIn(text));assertFalse(text.contains("0101234"));assertFalse(text.contains("r2"));assertFalse(text.contains("m4a"))
        val c=hb.getJSONObject("cand").getJSONObject("c")
        assertEquals(1,c.getInt("calls"));assertEquals(0.05,c.getDouble("wallRtf"),1e-9);assertEquals(5.0,c.getDouble("cerPct"),1e-9)
        assertEquals(75.0,c.getDouble("numberPct"),1e-9);assertEquals(1.0,c.getDouble("hallucPerCall"),1e-9);assertTrue(c.isNull("mAh"));assertEquals("cpu",c.getString("energyBasis"))
        assertEquals(1,c.getJSONObject("byLen").getJSONObject("m1to3").getInt("calls"))
        val b=hb.getJSONObject("cand").getJSONObject("b")
        assertEquals(1,b.getInt("failed"));assertEquals(1,b.getJSONObject("fail").getInt("ANDROID_STT_NOT_COMPLETED"));assertEquals(100,b.getInt("fgSharePct"))
        assertEquals(2,hb.getInt("planned"));assertEquals("READY",hb.getJSONObject("models").getString("c"))
        // Whatever a text slips into the block, the filter drops it.
        val dirty=Bench.safe(JSONObject().put("ok",1).put("t","김 부장님 견적서").put("n","01012345678").put("p","/storage/x.m4a").put("한글",1).put("a",JSONArray().put("홍길동").put(2)))
        assertEquals("""{"ok":1,"a":[2]}""",dirty.toString())
        // And through the heartbeat file itself.
        val o=PhoneStatus.build(PhoneStatus.Input(1_000L,"v3.8",1056,0,null,5,6,"READY","","",12).copy(bench=JSONObject().put("x","홍길동 010-1234-5678").put("y",3)))
        assertEquals("""{"y":3}""",o.getJSONObject("bench").toString())
    }

    // ---- model download (fake server) ----
    private class Server(val data:ByteArray):PipelineModelHttp { var fail=false;val asked=ArrayList<Long>()
        override fun get(url:String,rangeStart:Long):PipelineModelHttp.Response { asked+=rangeStart
            if(fail) throw IOException("cut")
            val body=data.copyOfRange(rangeStart.toInt(),data.size)
            return if(rangeStart>0) PipelineModelHttp.Response(206,"bytes $rangeStart-${data.size-1}/${data.size}",body.size.toLong(),ByteArrayInputStream(body))
                else PipelineModelHttp.Response(200,null,body.size.toLong(),ByteArrayInputStream(body)) } }
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    @Test fun modelsResumeAreCheckedAndNeedSpace() {
        val data=ByteArray(300_000) { (it%251).toByte() };val spec=BenchModels.Spec("m.bin",data.size.toLong(),sha(data),"https://example.invalid/m.bin")
        val dir=File(context.noBackupFilesDir,"bench-test").also { it.deleteRecursively() }
        val big=BenchModels.MIN_FREE_BYTES+10_000_000
        // Stopped part way: the part file stays and the next try asks for the rest only.
        val server=Server(data);var reads=0
        val m=BenchModels(dir,server) { big }
        assertEquals(BenchModels.Result.RETRY,m.fetch(spec) { ++reads>2 })
        val part=File(dir,"m.bin.part");assertTrue(part.isFile && part.length()>0 && part.length()<data.size)
        assertEquals(BenchModels.Result.READY,m.fetch(spec) { false })
        assertEquals(listOf(0L,2*65_536L),server.asked)
        assertNotNull(m.ready(spec));assertArrayEquals(data,File(dir,"m.bin").readBytes())
        // A wrong file is thrown away.
        val bad=spec.copy(name="bad.bin",sha256="0".repeat(64))
        assertEquals(BenchModels.Result.FAILED,m.fetch(bad) { false });assertFalse(File(dir,"bad.bin.part").exists());assertNull(m.ready(bad))
        // Under 2 GB free nothing is fetched.
        val small=BenchModels(dir,Server(data)) { BenchModels.MIN_FREE_BYTES }
        assertEquals(BenchModels.Result.NO_SPACE,small.fetch(spec.copy(name="other.bin")) { false })
        // A cut connection is a retry.
        assertEquals(BenchModels.Result.RETRY,BenchModels(dir,Server(data).also { it.fail=true }) { big }.fetch(spec.copy(name="x.bin")) { false })
        // The bench's folder goes as a whole; the app's own model folder is another one.
        assertTrue(m.deleteAll()>0);assertFalse(dir.exists())
        assertNotEquals(File(context.noBackupFilesDir,BenchModels.DIR),File(context.noBackupFilesDir,"pipeline-stt"))
        assertEquals(874188075L,BenchModels.TURBO_Q8.bytes);assertEquals(3,BenchModels.specs("c").size);assertTrue(BenchModels.specs("a").isEmpty())
    }

    // ---- which calls the store gives: work calls only ----
    @Test fun personalAndUnanalysedCallsAreLeftOut() {
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2","rec3").forEach { r -> p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,'x.m4a','audio/mp4',10,?,?,1,1,1,?)",
                r,GeneralLocalStore.OWNER,r,now-day,now-day,now-day) }
            null } }
        fun t(id:String)=CallTranscript(id,1,"a".repeat(64),now-day,120_000,"김과장",ClaudeCallAnalyzerTest.TRANSCRIPT,emptyList(),"m","b".repeat(64),true)
        CallRecordStore(context) { now }.use { db ->
            listOf("rec1","rec2","rec3").forEach { db.insert(t(it)) }
            db.applyAnalysis(CallRecordStore.recordId(t("rec1")),ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis()))
            db.applyAnalysis(CallRecordStore.recordId(t("rec2")),ClaudeCallAnalysis.parse(ClaudeCallAnalyzerTest.analysis().put("business",false)))
            val rows=db.benchRows(now-7*day)
            assertEquals(listOf("rec1"),rows.map { it.recordingId })
            assertTrue(rows.single().transcript.isNotEmpty())
        }
    }

    // ---- samples ----
    private class Releases:Samples.Releases {
        val log=ArrayList<String>();val uploaded=LinkedHashMap<String,ByteArray>();var failUploads=0;var next=100L
        override fun find(tag:String):Samples.Release? { log+="find";return null }
        override fun create(tag:String):Samples.Release { log+="create:$tag";return Samples.Release(7,emptyList()) }
        override fun upload(releaseId:Long,name:String,contentType:String,bytes:ByteArray):Long { log+="upload:$name"
            if(failUploads>0) { failUploads--;throw IOException("SAMPLE_HTTP_502") };uploaded[name]=bytes;return next++ }
        override fun deleteAsset(id:Long) { log+="deleteAsset:$id" }
        override fun deleteRelease(id:Long) { log+="deleteRelease:$id" }
        override fun deleteTag(tag:String) { log+="deleteTag:$tag" }
    }
    private val row=CallRecordStore.BenchRow("rec1",now-day,180_000,"김과장님 견적서는 010-1234-5678로 문자 주세요\n삼십만 원이요","""{"v":1,"s":[3,40],"d":[5,4]}""")
    @Test fun offSendsNothing() {
        val r=Releases();var queued=0;var cancelled=0
        Samples.onHeartbeat(context,SamplesConfig(on=false),now,r,{ queued++ },{ cancelled++ })
        assertTrue(r.log.isEmpty());assertEquals(0,queued);assertEquals(0,cancelled);assertNull(Samples.heartbeat(SecureSettings(context).snapshot()))
    }
    @Test fun aSampleGoesUpUnderARandomNameWithoutNamesOrNumbers() {
        val r=Releases();val s=JSONObject();val c=call("rec1",3.0,1.0)
        assertTrue(Samples.uploadNext(s,SamplesConfig(on=true),listOf(c),now,r,{ row },{ ByteArray(10) { 1 } },{ _,_ -> listOf(0L to 120_000L,120_000L to 60_000L) to true }))
        val names=r.uploaded.keys.toList()
        assertEquals(2,names.size);assertTrue(names[0].matches(Regex("s_[a-z0-9]{8}\\.m4a")));assertEquals(names[0].removeSuffix(".m4a")+".json",names[1])
        val side=String(r.uploaded.getValue(names[1]))
        assertFalse(side.contains("rec1"));assertFalse(side.contains("010-1234"));assertFalse(side.contains("x.m4a"))
        val j=JSONObject(side);assertEquals(180.0,j.getDouble("durationS"),1e-9);assertEquals(2,j.getJSONArray("pieces").length());assertEquals(43L,j.getJSONArray("lines").getJSONObject(1).getLong("startS"))
        assertEquals(listOf("find","create:${Samples.TAG}"),r.log.take(2))
        // The same recording never goes up twice.
        assertFalse(Samples.uploadNext(s,SamplesConfig(on=true),listOf(c),now,r,{ row },{ ByteArray(10) },{ _,_ -> emptyList<Pair<Long,Long>>() to false }))
        val hb=Samples.heartbeat(JSONObject().put(Samples.KEY,s.toString()).put(Bench.CONFIG_KEY,Bench.stored(BenchConfig(),SamplesConfig(on=true))))!!
        assertEquals(1,hb.getInt("uploaded"));assertEquals(1,hb.getInt("live"));assertEquals(3.0,hb.getDouble("minutes"),1e-9)
    }
    @Test fun failuresAreRetriedThreeTimesThenLeft() {
        val r=Releases().also { it.failUploads=10 };val s=JSONObject();val c=call("rec1",3.0,1.0)
        repeat(Samples.MAX_TRIES) { assertThrows(IOException::class.java) { Samples.uploadNext(s,SamplesConfig(on=true),listOf(c),now,r,{ row },{ ByteArray(4) },{ _,_ -> emptyList<Pair<Long,Long>>() to false }) } }
        assertEquals(3,s.getInt("failed"));assertEquals("SAMPLE_HTTP_502",s.getString("lastCode"))
        assertFalse(Samples.uploadNext(s,SamplesConfig(on=true),listOf(c),now,r,{ row },{ ByteArray(4) },{ _,_ -> emptyList<Pair<Long,Long>>() to false }))
    }
    @Test fun afterSevenDaysTheyGoAndOffTakesEverything() {
        val r=Releases()
        val s=JSONObject().put("releaseId",7).put("live",JSONArray()
            .put(JSONObject().put("id","s_old").put("assets",JSONArray().put(1).put(2)).put("atMs",now-8*day))
            .put(JSONObject().put("id","s_new").put("assets",JSONArray().put(3).put(4)).put("atMs",now-day)))
        Samples.sweep(s,r,now,all=false)
        assertEquals(listOf("deleteAsset:1","deleteAsset:2"),r.log);assertEquals(1,Samples.live(s).size);assertEquals(1,s.getInt("deleted"))
        r.log.clear();Samples.sweep(s,r,now,all=true)
        assertEquals(listOf("deleteAsset:3","deleteAsset:4","deleteRelease:7","deleteTag:${Samples.TAG}"),r.log)
        assertTrue(Samples.live(s).isEmpty());assertFalse(s.has("releaseId"));assertEquals(2,s.getInt("deleted"))
        // Through the heartbeat: off with something still up deletes it and stops the work.
        SecureSettings(context).update { it.put(Samples.KEY,JSONObject().put("releaseId",9).put("live",JSONArray().put(JSONObject().put("id","s_x").put("assets",JSONArray().put(5)).put("atMs",now))).toString()) }
        val r2=Releases();var cancelled=0
        Samples.onHeartbeat(context,SamplesConfig(on=false),now,r2,{ fail("queued while off") },{ cancelled++ })
        assertEquals(listOf("deleteAsset:5","deleteRelease:9","deleteTag:${Samples.TAG}"),r2.log);assertEquals(1,cancelled)
        assertEquals("bin",Samples.extension("audio/unknown"));assertEquals("m4a",Samples.extension("audio/mp4"));assertEquals("amr",Samples.extension("audio/amr"))
    }
}
