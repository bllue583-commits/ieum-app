package kr.callbrief

import android.content.Context
import android.content.Intent
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual protected repository, JCE native intake and existing synthetic result wire.
 * FakeDrive is memory only: no OAuth, dot, provider, internet or real audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w411dp-h891dp-xhdpi",shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class Code23PartialBoundaryControlsTest:ProtectedTextHostTestBase() {
    companion object { lateinit var uiSession:GoogleConnectionSession;lateinit var uiContext:Context;lateinit var uiSettings:SecureSettings }
    class UiHost:RecordingWorkflowActivity() {
        override fun createSession()=uiSession
        override fun createStore()=RecordingWorkflowStore(uiContext,policySnapshot={uiSettings.snapshot()},analysisActor={uiSession.nativeSessionBinding()})
    }
    private lateinit var context:Context
    private lateinit var scoped:AccountStorageContext
    private lateinit var session:GoogleConnectionSession
    private lateinit var settings:SecureSettings
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var source:GeneralLocalStoreTest.Source
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private var recording="";private lateinit var job:DriveRelay.Reply;private var file=""
    private val raw="😀 합성 도면 2판을 요청합니다.\n합성 검토를 부탁합니다.\n진행 중입니다."
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=try { worker.submit<T>{f()}.get(30,TimeUnit.SECONDS) } catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
    private fun pipeline()=GoogleNativePipeline(context,session,settings,{source},drive,postingCapability={true}) { GeneralLocalStoreTest.host(it,keys) }
    private fun store()=RecordingWorkflowStore(scoped,policySnapshot={settings.snapshot()},analysisActor={session.nativeSessionBinding()})
    private fun port()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun snap()=store().use { it.snapshot(recording) }
    private fun count(t:String)=port().use { (it.query("SELECT count(*) AS n FROM $t").single()["n"] as Number).toLong() }
    private fun blocked(code:String,f:()->Unit) { try { f();fail("Expected $code") } catch(e:Exception) { assertTrue(e.message.orEmpty(),e.message.orEmpty().contains(code)) } }
    @Before fun seed() {
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(GoogleConnectionRegressionTest.Memory())
        val e=session.begin();session.activate(e,"fixture@example.invalid","fixture@example.invalid","BUSINESS-SYN-OWNER","memory-only-fixture")
        scoped=AccountStorageContext(context,session.ownerBinding());keys=SyntheticPilotIntegrationTest.HostKeys();drive=GoogleConnectionRegressionTest.FakeDrive()
        source=GeneralLocalStoreTest.Source().apply { put(name="통화 녹음 합성상대_01000000001_20261010_100000.wav") }
        val settingsKey=javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        settings=SecureSettings(scoped,object:SecureSettings.KeyAccess { override fun existing()=settingsKey;override fun createForFirstEmptyStore()=settingsKey });settings.update { it.put("treeUri",source.rootUri) }
        uiContext=scoped;uiSession=session;uiSettings=settings;RuntimeEnvironment.setFontScale(1f)
        io { pipeline().use { p->p.initializeEmpty();val first=p.importAndSubmit(source.documents.getValue("one"),true);recording=first.second.getJSONObject("state").getString("sourceId") }
            GeneralLocalStoreTest.host(scoped,keys).use { it.confirmTime(recording,it.snapshot().getJSONObject(0),Instant.parse("2026-10-10T01:00:00Z").toEpochMilli()) }
            store().use { it.confirmWork(recording,it.snapshot(recording),"합성 담당자","합성 회사","합성 업무","01000000001") }
            dispatch()
        }
    }
    private fun dispatch() { pipeline().use { p->job=p.submitExisting(recording);file=p.registration(job.jobId).getJSONObject("files").getJSONObject("result").getString("fileId") } }
    private fun request()=drive.bytes.values.mapNotNull { runCatching { StrictJson.`object`(StrictJson.parse(it)) }.getOrNull() }.single { it["kind"]=="CALL_ANALYSIS_REQUEST" && it["resultFileId"]==file }
    private fun claim(req:Map<String,Any>,id:String,category:String,value:String,quote:String,status:String?,certainty:String="explicit"):Map<String,Any?> {
        val start=raw.codePointCount(0,raw.indexOf(quote));val end=start+quote.codePointCount(0,quote.length)
        return mapOf("assignee" to null,"category" to category,"certainty" to certainty,"claim_id" to id,"due" to null,"due_expression" to null,
            "evidence" to listOf(mapOf("source_id" to req["sourceId"],"start" to start,"end" to end,"quote" to quote)),"replaces" to null,"status" to status,"subject_id" to null,"value" to value)
    }
    private fun result(revision:Long=1,uncertain:Boolean=false,overlap:Boolean=false):String {
        val req=request()
        val provenance=linkedMapOf<String,Any?>("source_kind" to "synthetic_contract_fixture","language" to "ko","run_id" to "business_fixture","audio_sha256" to req["audioSha"],"model_name" to null,"model_sha256" to null,"engine_sha256" to null,"execution_observed" to false,"reference_used_in_inference" to false,"quality_state" to "raw_review_required","run_started_at_utc" to null,"run_finished_at_utc" to null,"runner_sha256" to null,"source_commit" to null,"dataset" to null,"dataset_revision" to null,"license" to null)
        val src=mapOf("source_id" to req["sourceId"],"kind" to "call_transcript","raw_text" to raw,"text_sha256" to DriveRelay.sha256(raw.toByteArray()),"recorded_at" to null,"timestamp_basis" to "unknown","case_id" to null,"case_link_confirmed" to false,"speaker_assignment_verified" to false,"uncertain_ranges" to if(overlap)listOf(mapOf("start" to 2,"end" to 5,"reason" to "synthetic unclear audio")) else emptyList<Any>(),"provenance" to provenance)
        val packet=linkedMapOf<String,Any?>("schema_version" to 1L,"job_id" to req["jobId"],"base_revision" to req["baseRevision"],"scope" to "synthetic_contract_test","offset_unit" to "unicode_code_points","automatic_summary_allowed" to false,"root_semantic_review_required" to true,"sources" to listOf(src))
        packet["source_pack_sha256"]=DriveRelay.sha256(StrictJson.bytes(packet))
        val claims=listOf(claim(req,"doc","document","합성 도면","합성 도면 2판을 요청합니다.","waiting",if(uncertain)"uncertain" else "explicit"),
            claim(req,"todo","request","합성 검토","합성 검토를 부탁합니다.","open"),claim(req,"progress","status","진행 상태","진행 중입니다.","in_progress"))
        val candidate=mapOf("schema_version" to 1L,"job_id" to req["jobId"],"base_revision" to req["baseRevision"],"source_pack_sha256" to packet["source_pack_sha256"],"summary" to "합성 도면 요청과 검토. 전체 업무는 진행 중.","claims" to claims)
        val v=WireV0Codec.placeholder(req).toMutableMap();v["state"]="PROPOSAL";v["resultRevision"]=revision
        v["stt"]=mapOf("execution" to "FIXTURE","text" to raw,"textSha" to DriveRelay.sha256(raw.toByteArray()),"evidenceRef" to "synthetic_fixture_only")
        v["high"]=mapOf("execution" to "FIXTURE","packetJson" to String(StrictJson.bytes(packet)),"candidateJson" to String(StrictJson.bytes(candidate)),"evidenceRef" to "synthetic_fixture_only")
        v["resultDigest"]=WireV0Codec.digest("result",v);val bytes=StrictJson.bytes(v)
        assertTrue(WireV0Codec.candidateStructureValid(bytes,StrictJson.bytes(req)));drive.bytes[file]=bytes;drive.versions[file]=drive.versions.getValue(file)+1
        pipeline().use { it.poll(job.jobId) };return v["resultDigest"].toString()
    }
    private fun action(claim:String,kind:String,status:String="open",target:String?=null,revision:Long=0)=JSONObject().put("claim_id",claim).put("kind",kind).put("title","직접 확인 합성 $claim")
        .put("edition",if(kind=="DOCUMENT")"2판" else "").put("due","시간대 미확인 · 직접 입력").put("status",status).put("completion_confirmed",false).put("target_id",target ?: JSONObject.NULL).put("target_revision",revision)
    private fun apply(d:String,a:JSONArray,summary:String?=null,expected:JSONObject=snap())=store().use { it.applyBusinessReview(recording,expected,d,a,summary,true) }
    private fun evidence(id:String,vararg values:Pair<String,Any?>) { val json=JSONObject().put("id",id).put("scope","API35_ROBOLECTRIC_PROTECTED_SQL_JCE_FAKE_DRIVE_NOT_DEVICE").put("outcome","OBSERVATION_NOT_TEST_VERDICT");values.forEach { json.put(it.first,it.second ?: JSONObject.NULL) };File("build/reports/code23-partial-controls").apply { mkdirs() }.resolve("$id.json").writeText(json.toString(2)) }
    @After fun finishIndependent() { worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun dailyCurrent()=CanonicalReviewedDailySource(scoped){settings.snapshot()}.snapshot(ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul")))
    private fun coreAcceptCount()=port().use { (it.query("SELECT count(*) AS n FROM receipts WHERE operation='ACCEPT'").single()["n"] as Number).toLong() }
    private fun reviewCursor(d:String):String=store().use { s->
        val req=s.prepareAnalysis(recording,s.snapshot(recording),session.nativeSessionBinding())
        s.transitionRequest(req,"REVIEW",job.jobId)
        s.commitResultObservation(req,GoogleRelayStore.ResultObservation(job.jobId,"LEGACY_CANDIDATE","CALL_ANALYSIS_RESULT",0,1,d));req
    }
    private fun confirmed():Pair<String,String> {
        val d=result();val req=reviewCursor(d);apply(d,JSONArray().put(action("doc","DOCUMENT")),"합성 사용자가 확인한 이전 요약");assertTrue(dailyCurrent().fullyReviewed);return d to req
    }
    private fun error():GoogleRelayStore.ResultObservation {
        val r=WireV0Codec.placeholder(request()).toMutableMap();r["resultRevision"]=2L;r["state"]="ERROR";r["error"]=mapOf("code" to "PUBLIC_SYNTHETIC_FAILED","message" to "same original v0 error contract")
        r["resultDigest"]=WireV0Codec.digest("result",r);drive.bytes[file]=StrictJson.bytes(r);drive.versions[file]=drive.versions.getValue(file)+1
        return pipeline().use { it.pollObservation(job.jobId) }.also { assertEquals("ERROR",it.disposition);assertEquals(2L,it.revision) }
    }
    @Test fun C01CoreAndProfileFailuresCountOneCallAndPreservePriorText()=io {
        confirmed();val e=error();port().use { it.execute("UPDATE processing SET state='FAILED' WHERE recording_id=?",recording) }
        val v=dailyCurrent();assertFalse(v.fullyReviewed);assertEquals(1,v.expectedCount);assertEquals(1,v.pendingCount);assertEquals(1,v.unresolved.size);assertEquals(1,v.sources.size);assertEquals("ANALYSIS_FAILED",v.unresolved.single().reason);assertEquals(e.digest,v.unresolved.single().resultDigest);assertTrue(v.partialPreview().contains("합성 사용자가 확인한 이전 요약"));evidence("C01","singleCallCount" to v.pendingCount,"latestDigest" to v.unresolved.single().resultDigest)
    }
    @Test fun C02MatchingAcceptedProposalDoesNotBecomeBlanketHold()=io {
        confirmed();val v=dailyCurrent();assertTrue(v.fullyReviewed);assertEquals(0,v.pendingCount);assertEquals(0,v.unresolved.size);evidence("C02","positiveControl" to true)
    }
    private fun foreign(column:String,value:String) {
        // Deliberately irrelevant RAW SQL rows, not claimed as inbox-validated receipts.
        require(column in listOf("owner_id","job_id","result_id"));val columns=listOf("owner_id","job_id","result_id","kind","schema_version","result_revision","digest","payload","conflict","hidden")
        val selected=columns.joinToString(",") { when(it) { column->"?";"result_revision"->"result_revision+100";else->it } }
        port().use { it.execute("INSERT INTO drive_profiles_receipts SELECT $selected FROM drive_profiles_receipts WHERE owner_id=? AND job_id=? AND result_revision=1",value,session.ownerBinding(),job.jobId) }
    }
    @Test fun C03ForeignOwnerHighRevisionIsNotThisCallsResult()=io { confirmed();foreign("owner_id","PUBLIC-SYN-OTHER-OWNER");assertTrue(dailyCurrent().fullyReviewed);evidence("C03","rawForeignOwnerRowIgnored" to true) }
    @Test fun C04ForeignJobHighRevisionIsNotThisCallsResult()=io { confirmed();foreign("job_id","PUBLIC-SYN-OTHER-JOB");assertTrue(dailyCurrent().fullyReviewed);evidence("C04","rawForeignJobRowIgnored" to true) }
    @Test fun C05ForeignResultFileHighRevisionIsNotThisCallsResult()=io { confirmed();foreign("result_id","PUBLIC-SYN-OTHER-RESULT");assertTrue(dailyCurrent().fullyReviewed);evidence("C05","rawForeignResultFileRowIgnored" to true) }
    @Test fun C06NewProposalStillNeedsReviewBeforeWorkflowObserver()=io {
        val (before,req)=confirmed();val fresh=result(2);assertNotEquals(before,fresh);assertEquals("REVIEW",store().use { it.request(req) }.getString("state"));val v=dailyCurrent();assertFalse(v.fullyReviewed);assertEquals(1,v.pendingCount);assertEquals(1,v.sources.size);assertEquals(fresh,v.unresolved.single().resultDigest);evidence("C06","previousTextKept" to true,"newDigest" to fresh)
    }
    @Test fun C07FrozenJobSourceMismatchCannotSatisfyReview()=io {
        confirmed();port().use { val row=it.query("SELECT frozen FROM drive_v0_jobs WHERE job_id=?",job.jobId).single();val j=JSONObject(String(row["frozen"] as ByteArray));val request=JSONObject(j.getString("requestJson"));request.put("sourceVersion",99);j.put("requestJson",request.toString());it.execute("UPDATE drive_v0_jobs SET frozen=? WHERE job_id=?",j.toString().toByteArray(),job.jobId) }
        val v=dailyCurrent();assertFalse(v.fullyReviewed);assertEquals(1,v.sources.size);assertEquals(1,v.unresolved.size);evidence("C07","invalidFrozenBindingHeld" to true)
    }
    @Test fun C08ValidatedErrorRemainsFailureEvenWithManualConflictFlag()=io {
        val (_,req)=confirmed();val received=error();val row=port().use { it.query("SELECT conflict FROM drive_profiles_receipts WHERE job_id=? AND result_revision=2",job.jobId).single() };assertEquals(1L,(row["conflict"] as Number).toLong());assertEquals("REVIEW",store().use { it.request(req) }.getString("state"))
        val v=dailyCurrent();assertFalse(v.fullyReviewed);assertEquals("CURRENT_REQUEST_FAILED",v.unresolved.single().reason);assertEquals(received.digest,v.unresolved.single().resultDigest);assertTrue(v.partialPreview().contains("분석 실패"));evidence("C08","durableError" to received.revision,"reason" to v.unresolved.single().reason)
    }
    @Test fun C09ValidatedErrorWithoutEarlierReviewedSummaryIsFailure()=io {
        val req=store().use { s->s.prepareAnalysis(recording,s.snapshot(recording),session.nativeSessionBinding()).also { s.transitionRequest(it,"REVIEW",job.jobId) } }
        assertEquals(0L,count("workflow_reviewed_summaries"));assertEquals(0L,count("summary_versions"));val e=error()
        assertEquals("REVIEW",store().use { it.request(req) }.getString("state"))
        val v=dailyCurrent();evidence("C09","acceptedErrorRevision" to e.revision,"acceptedErrorDigest" to e.digest,"workflowStateStill" to "REVIEW","priorReviewedSummaries" to 0,"sources" to v.sources.size,"expectedCount" to v.expectedCount,"pending" to v.pendingCount,"unresolved" to v.unresolved.size,"reason" to v.unresolved.single().reason,"rendered" to v.partialPreview())
        assertFalse(v.fullyReviewed);assertEquals(1,v.expectedCount);assertEquals(0,v.sources.size);assertEquals(1,v.pendingCount);assertEquals(1,v.unresolved.size)
        assertEquals("CURRENT_REQUEST_FAILED",v.unresolved.single().reason);assertEquals(e.digest,v.unresolved.single().resultDigest);assertTrue(v.partialPreview().contains("분석 실패"));assertFalse(v.partialPreview().contains("기기에 부분 보고 보관"))
    }
}
