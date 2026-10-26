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
internal class Code22IndependentBusinessReviewTest:ProtectedTextHostTestBase() {
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
    private fun evidence(id:String,vararg values:Pair<String,Any?>) { val json=JSONObject().put("id",id).put("scope","API35_ROBOLECTRIC_PROTECTED_SQL_JCE_FAKE_DRIVE_NOT_DEVICE").put("outcome","OBSERVATION_NOT_TEST_VERDICT");values.forEach { json.put(it.first,it.second ?: JSONObject.NULL) };File("build/reports/code22-independent-business").apply { mkdirs() }.resolve("$id.json").writeText(json.toString(2)) }
    @After fun finishIndependent() { worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    private fun dailyCurrent()=CanonicalReviewedDailySource(scoped){settings.snapshot()}.snapshot(ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul")))
    private fun coreAcceptCount()=port().use { (it.query("SELECT count(*) AS n FROM receipts WHERE operation='ACCEPT'").single()["n"] as Number).toLong() }
    private fun reviewCursor(d:String):String=store().use { s->
        val req=s.prepareAnalysis(recording,s.snapshot(recording),session.nativeSessionBinding())
        s.transitionRequest(req,"REVIEW",job.jobId)
        s.commitResultObservation(req,GoogleRelayStore.ResultObservation(job.jobId,"LEGACY_CANDIDATE","CALL_ANALYSIS_RESULT",0,1,d));req
    }
    @Test fun I01IngressCannotMintUserOrNativeAcceptAttestation():Unit=io {
        val userBefore=count("workflow_user_receipts");val accepts=coreAcceptCount();val d=result()
        assertEquals(userBefore,count("workflow_user_receipts"));assertEquals(accepts,coreAcceptCount());assertEquals(0L,count("summary_versions"));assertEquals(0L,count("workflow_business_reviews"))
        val view=store().use{it.businessReview(recording,d)};assertFalse(view.getBoolean("execution_attested"));assertFalse(view.getBoolean("canonical_write_allowed"))
        evidence("I01","userReceiptsBefore" to userBefore,"userReceiptsAfter" to count("workflow_user_receipts"),"nativeAcceptBefore" to accepts,"nativeAcceptAfter" to coreAcceptCount(),"candidateIsSyntheticNotModelAttestation" to true)
    }
    @Test fun I02LateDatabaseAbortRollsBackWholeBusinessAndSummaryBatch():Unit=io {
        val d=result();val before=snap();val userBefore=count("workflow_user_receipts");val receiptsBefore=count("receipts")
        port().use{it.execute("CREATE TRIGGER independent_review_abort BEFORE INSERT ON workflow_business_reviews BEGIN SELECT RAISE(ABORT,'INDEPENDENT_REVIEW_COMMIT_ABORT'); END")}
        try {apply(d,JSONArray().put(action("doc","DOCUMENT")).put(action("progress","PROGRESS","in_progress")),"합성 직접 확인 요약");fail("Late DB abort must roll back all prior writes")}catch(e:Exception){assertTrue(e.message.orEmpty(),(e.message.orEmpty().contains("INDEPENDENT_REVIEW_COMMIT_ABORT") || e.message.orEmpty().contains("SQLITE_OPERATION_FAILED")))}
        val after=snap();for(k in listOf("board_revision","task_revision","task_status","head_revision","user_revision","lock_revision","manual_status"))assertEquals(k,before.get(k),after.get(k))
        assertEquals(0,after.getJSONArray("items").length());assertEquals(0,after.getJSONArray("progress_history").length());assertEquals(0L,count("workflow_reviewed_summaries"));assertEquals(0L,count("workflow_business_reviews"));assertEquals(userBefore,count("workflow_user_receipts"));assertEquals(receiptsBefore,count("receipts"))
        port().use{it.execute("DROP TRIGGER independent_review_abort")};evidence("I02","atomicRollbackAfterFinalInsertAbort" to true)
    }
    @Test fun I03ConcurrentManualTaskChangeKeepsNewStatusAndRejectsOldSnapshot():Unit=io {
        val d=result();val old=snap();store().use{it.editBusinessProgress(recording,old,"done","합성 사용자의 직접 완료",true)}
        try {apply(d,JSONArray().put(action("doc","DOCUMENT")),expected=old);fail("Old task/board snapshot admitted")}catch(e:Exception){assertTrue(e.message.orEmpty(),listOf("CONFLICT","CHANGED","PROTECTED").any{code->e.message.orEmpty().contains(code)})}
        assertEquals("done",snap().getString("task_status"));assertEquals(0,snap().getJSONArray("items").length());assertEquals(0L,count("workflow_business_reviews"));evidence("I03","manualTaskDonePreserved" to true)
    }
    @Test fun I04SamePhoneAndLabelsNeverAuthorizeCrossCaseTarget():Unit=io {
        source.put("two","통화 녹음 합성별도_01000000001_20261010_110000.wav")
        val other=pipeline().use{it.importAndSubmit(source.documents.getValue("two"),true).second.getJSONObject("state").getString("sourceId")}
        val target=store().use{s->s.confirmWork(other,s.snapshot(other),"합성 담당자","합성 회사","합성 업무","01000000001");s.createItem(other,s.snapshot(other),"TODO","별도 업무 수동 할일","","")}
        val otherBefore=store().use{it.snapshot(other)};assertNotEquals(snap().getString("person_id"),otherBefore.getString("person_id"));assertNotEquals(snap().getString("case_id"),otherBefore.getString("case_id"))
        dispatch();val d=result();try {apply(d,JSONArray().put(action("doc","DOCUMENT")).put(action("todo","TODO","waiting",target,1)));fail("Cross recording target admitted")}catch(_:NoSuchElementException){}
        assertEquals(0,snap().getJSONArray("items").length());assertEquals("open",store().use{it.snapshot(other)}.getJSONArray("items").getJSONObject(0).getString("status"));assertEquals(1L,count("workflow_items"));assertEquals(0L,count("workflow_business_reviews"));evidence("I04","samePhoneSameLabelDifferentPersonAndCase" to true,"crossCaseBatchRollback" to true)
    }
    @Test fun I05DocumentCompletionAndReopenRetryNeverCompleteWholeTask():Unit=io {
        val d=result();val expected=snap();val actions=JSONArray().put(action("doc","DOCUMENT","done").put("completion_confirmed",true)).put(action("todo","TODO","waiting"))
        val first=apply(d,actions,"합성 직접 확인 부분 업무 요약",expected);val again=apply(d,actions,"합성 직접 확인 부분 업무 요약",expected)
        assertEquals(first,again);assertEquals(2,snap().getJSONArray("items").length());assertNotEquals("done",snap().getString("task_status"));assertNotEquals("done",snap().optString("manual_status"));assertEquals(1L,count("workflow_business_reviews"));assertEquals(1L,count("workflow_reviewed_summaries"));assertEquals(0L,count("summary_versions"));assertEquals(0L,coreAcceptCount())
        evidence("I05","receiptReusedAfterStoreReopen" to true,"documentDoneOnly" to true,"nativeAccept" to coreAcceptCount())
    }
    @Test fun I06CoreFailureWithMatchingReviewedWorkflowMustRemainPartial():Unit=io {
        val d=result();val requestId=reviewCursor(d);apply(d,JSONArray().put(action("doc","DOCUMENT")),"합성 사용자가 확인한 이전 요약")
        assertTrue(dailyCurrent().fullyReviewed)
        port().use{it.execute("UPDATE processing SET state='FAILED' WHERE recording_id=? AND source_version=?",recording,snap().getLong("source_version"))}
        val v=dailyCurrent();evidence("I06","requestId" to requestId,"workflowState" to store().use{it.request(requestId)}.getString("state"),"coreState" to "FAILED","fullyReviewed" to v.fullyReviewed,"readiness" to v.readiness.name,"expectedCount" to v.expectedCount,"sources" to v.sources.size,"pending" to v.pendingCount,"unresolved" to v.unresolved.size)
        assertFalse("Core FAILED must remain pending even when workflow REVIEW cursor matches an older user review",v.fullyReviewed);assertTrue(v.unresolved.any{it.id==recording})
    }
    @Test fun I07AcceptedNewErrorBeforeWorkflowObserverMustRemainPartial():Unit=io {
        val d=result();val requestId=reviewCursor(d);apply(d,JSONArray().put(action("doc","DOCUMENT")),"합성 사용자가 확인한 이전 요약")
        val newer=WireV0Codec.placeholder(request()).toMutableMap();newer["resultRevision"]=2L;newer["state"]="ERROR";newer["error"]=mapOf("code" to "INDEPENDENT_SYNTHETIC_FAILED","message" to "Public synthetic error fixture; no model execution")
        newer["resultDigest"]=WireV0Codec.digest("result",newer);drive.bytes[file]=StrictJson.bytes(newer);drive.versions[file]=drive.versions.getValue(file)+1
        val received=pipeline().use{it.pollObservation(job.jobId)};assertEquals("ERROR",received.disposition);assertEquals(2L,received.revision)
        val accepted=port().use{it.query("SELECT result_revision,digest FROM drive_profiles_receipts WHERE job_id=? AND result_revision=2",job.jobId)};assertEquals(1,accepted.size)
        val v=dailyCurrent();evidence("I07","acceptedNewErrorRevision" to received.revision,"acceptedNewErrorDigest" to received.digest,"workflowStateStill" to store().use{it.request(requestId)}.getString("state"),"fullyReviewed" to v.fullyReviewed,"readiness" to v.readiness.name,"pending" to v.pendingCount,"unresolved" to v.unresolved.size)
        assertFalse("Durable accepted ERROR revision2 must prevent full review while workflow cursor still shows proposal revision1",v.fullyReviewed)
    }
    @Test fun I08PartialInventoryPreservesAllMissingFailedAndUnknownRows():Unit=io {
        store().use{it.reviewOwnSummary(recording,snap(),"합성 확인 첫 통화")}
        val names=listOf("통화 녹음 합성미상_01000000001_20261010_110000.wav","통화 녹음 합성실패_01000000001_20261010_120000.wav","통화 녹음 합성누락_01000000001_20261010_130000.wav")
        names.forEachIndexed{i,name->source.put("fixture"+i,name)};GeneralLocalStoreTest.host(scoped,keys).use{it.importSelected(source)}
        val byName=port().use{it.query("SELECT recording_id,display_name FROM recordings")}.associate{it["display_name"].toString() to it["recording_id"].toString()};val failed=byName.getValue(names[1]);val missing=byName.getValue(names[2]);val unknown=byName.getValue(names[0])
        GeneralLocalStoreTest.host(scoped,keys).use{s->val all=s.snapshot();val row=(0 until all.length()).map{all.getJSONObject(it)}.single{it.getString("file_id")==failed};s.confirmTime(failed,row,Instant.parse("2026-10-10T03:00:00Z").toEpochMilli())}
        store().use{s->s.confirmWork(failed,s.snapshot(failed),"합성 실패 담당자","합성 회사","합성 실패 업무","01000000001")}
        port().use{it.execute("UPDATE processing SET state='FAILED' WHERE recording_id=?",failed);it.execute("DELETE FROM processing WHERE recording_id=?",missing)}
        val v=dailyCurrent();val ids=v.sources.map{it.id}.toSet()+v.unresolved.map{it.id}.toSet();assertEquals(setOf(recording,failed,missing,unknown),ids);assertEquals(4,v.expectedCount);assertEquals(1,v.sources.size);assertEquals(3,v.unresolved.size);assertFalse(v.fullyReviewed)
        assertEquals("ANALYSIS_FAILED",v.unresolved.single{it.id==failed}.reason);assertEquals("NATIVE_BINDING_MISSING",v.unresolved.single{it.id==missing}.reason);assertFalse(v.unresolved.single{it.id==unknown}.originalTimeKnown)
        evidence("I08","allFourInventoryRowsPresent" to true,"expectedCount" to v.expectedCount,"sources" to v.sources.size,"pendingDetails" to v.unresolved.size)
    }
    @Test fun I09LateScopeRevocationRollsBackUserSummaryAndItems():Unit=io {
        val d=result();val before=snap();val users=count("workflow_user_receipts");var gates=0
        val custom=RecordingWorkflowStore(scoped,policySnapshot={settings.snapshot()},scopeCurrent={++gates<=2},analysisActor={session.nativeSessionBinding()})
        custom.use{blocked("ANALYSIS_SCOPE_CHANGED"){it.applyBusinessReview(recording,before,d,JSONArray().put(action("doc","DOCUMENT")),"합성 사용자 확인 요약",true)}}
        assertEquals(3,gates);assertEquals(before.getLong("board_revision"),snap().getLong("board_revision"));assertEquals(0,snap().getJSONArray("items").length());assertEquals(0L,count("workflow_reviewed_summaries"));assertEquals(0L,count("workflow_business_reviews"));assertEquals(users,count("workflow_user_receipts"));evidence("I09","lateScopeRollback" to true)
    }
    @Test fun I10ReadOnlyDatePreviewMustNotClaimStoredPartial():Unit=io {
        val v=dailyCurrent();assertFalse(v.fullyReviewed);val text=v.partialPreview()
        evidence("I10","operation" to "snapshot plus partialPreview only; no storePartial/freeze/claim call","containsStoredClaim" to text.contains("기기에 부분 보고 보관"),"rendered" to text)
        assertFalse("Normal date read-only query must not state that its unsaved partial is stored",text.contains("기기에 부분 보고 보관"))
    }
}
