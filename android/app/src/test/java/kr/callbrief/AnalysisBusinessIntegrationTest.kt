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
internal class AnalysisBusinessIntegrationTest:ProtectedTextHostTestBase() {
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
    private fun evidence(id:String,vararg values:Pair<String,Any?>) { val json=JSONObject().put("id",id).put("scope","API35_ROBOLECTRIC_PROTECTED_SQL_JCE_FAKE_DRIVE_NOT_DEVICE").put("pass",true);values.forEach { json.put(it.first,it.second ?: JSONObject.NULL) };File("build/reports/analysis-business").apply { mkdirs() }.resolve("$id.json").writeText(json.toString(2)) }
    @After fun stop() { worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    @Test fun B01IngressStoresOnlyProposalUntilExplicitReview()=io { result();assertEquals(0,snap().getJSONArray("items").length());assertEquals(0L,count("workflow_business_reviews"));assertEquals(0L,count("summary_versions"));evidence("B01","automatic_business_writes" to 0) }
    @Test fun B02SelectedBatchUsesExistingBusinessSummaryAndReport()=io { val d=result();assertTrue(store().use { it.businessReview(recording,d) }.getBoolean("eligible"));val before=snap();apply(d,JSONArray().put(action("doc","DOCUMENT","waiting")).put(action("todo","TODO")).put(action("progress","PROGRESS","in_progress")),"직접 확인 합성 요약")
        val s=snap();assertEquals(2,s.getJSONArray("items").length());assertEquals("in_progress",s.getString("task_status"));assertEquals(1,s.getJSONArray("progress_history").length());assertEquals(before.opt("manual_text"),s.opt("manual_text"));assertEquals(0L,count("summary_versions"));assertEquals(1L,count("workflow_business_reviews"))
        val input=CanonicalReviewedDailySource(scoped){settings.snapshot()}.snapshot(ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul")))
        assertTrue(input.fullyReviewed);assertTrue(input.preview().contains("필요 서류"));assertTrue(input.preview().contains("todo"));assertEquals("USER_REVIEWED_LOCAL_NOT_MODEL_ATTESTATION",input.sources.single().summaryOrigin);evidence("B02","report" to input.preview(),"native_accept_fabricated" to false) }
    @Test fun B03MissingMeaningConfirmationWritesNothing()=io { val d=result();blocked("USER_MEANING_REVIEW_REQUIRED") { store().use { it.applyBusinessReview(recording,snap(),d,JSONArray().put(action("doc","DOCUMENT")),null,false) } };assertEquals(0L,count("workflow_business_reviews"));evidence("B03") }
    @Test fun B04UncertainClaimRemainsInHistory()=io { val d=result(uncertain=true);blocked("CLAIM_REVIEW_ONLY") { apply(d,JSONArray().put(action("doc","DOCUMENT"))) };assertEquals(0,snap().getJSONArray("items").length());assertEquals(1,snap().getJSONArray("proposals").length());evidence("B04") }
    @Test fun B05ManualDoneAfterDispatchIsProtected()=io { val d=result();GeneralLocalStoreTest.host(scoped,keys).use { it.edit(recording,it.snapshot().getJSONObject(0),"PUBLIC_SYNTHETIC_PROTECTED_NOTE","done") };blocked("ANALYSIS_BASE_REVISION_CHANGED") { apply(d,JSONArray().put(action("doc","DOCUMENT"))) };assertEquals("done",snap().getString("manual_status"));assertEquals("PUBLIC_SYNTHETIC_PROTECTED_NOTE",snap().getString("manual_text"));evidence("B05") }
    @Test fun B06NewErrorHeadBlocksOlderProposal()=io { val d=result();val r=WireV0Codec.placeholder(request()).toMutableMap();r["resultRevision"]=2L;r["state"]="ERROR";r["error"]=mapOf("code" to "SYNTHETIC_FAILED","message" to "synthetic error fixture");r["resultDigest"]=WireV0Codec.digest("result",r);drive.bytes[file]=StrictJson.bytes(r);drive.versions[file]=drive.versions.getValue(file)+1;pipeline().use { assertEquals("ERROR",it.poll(job.jobId)) }
        blocked("ANALYSIS_RESULT_SUPERSEDED") { apply(d,JSONArray().put(action("doc","DOCUMENT"))) };assertEquals(0,snap().getJSONArray("items").length());evidence("B06") }
    @Test fun B07NewProposalHeadCannotRegressToOldResult()=io { val old=result();val fresh=result(2);blocked("ANALYSIS_RESULT_SUPERSEDED") { apply(old,JSONArray().put(action("doc","DOCUMENT"))) };apply(fresh,JSONArray().put(action("doc","DOCUMENT")));assertEquals(2,snap().getJSONArray("proposals").length());assertEquals(1,snap().getJSONArray("items").length());evidence("B07") }
    @Test fun B08ExactBatchRetryReusesReceipt()=io { val d=result();val expected=snap();val actions=JSONArray().put(action("doc","DOCUMENT"));val first=apply(d,actions,expected=expected);val second=apply(d,actions,expected=expected);assertEquals(first,second);assertEquals(1,snap().getJSONArray("items").length());assertEquals(1L,count("workflow_business_reviews"));evidence("B08") }
    @Test fun B09WholeBatchRollsBackWhenLaterActionIsInvalid()=io { val d=result();val before=snap();blocked("CLAIM_REVIEW_ONLY") { apply(d,JSONArray().put(action("doc","DOCUMENT")).put(action("todo","DOCUMENT"))) };assertEquals(before.getLong("board_revision"),snap().getLong("board_revision"));assertEquals(0,snap().getJSONArray("items").length());assertEquals(0L,count("workflow_business_reviews"));evidence("B09") }
    @Test fun B10ManualDoneItemCannotBeOverwritten()=io { val item=store().use { it.createItem(recording,snap(),"DOCUMENT","직접 완료 서류","","",initialStatus="done") };dispatch();val d=result();blocked("MANUAL_ITEM_PROTECTED") { apply(d,JSONArray().put(action("doc","DOCUMENT","waiting",item,1))) };assertEquals("done",snap().getJSONArray("items").getJSONObject(0).getString("status"));evidence("B10") }
    @Test fun B11ManualEditedItemCannotBeOverwritten()=io { val item=store().use { s->s.createItem(recording,snap(),"TODO","수동 제목",""," ").also { s.editItem(it,1,"수동 새 제목","waiting","직접 기한","") } };dispatch();val d=result();blocked("MANUAL_ITEM_PROTECTED") { apply(d,JSONArray().put(action("todo","TODO","open",item,2))) };assertEquals("수동 새 제목",snap().getJSONArray("items").getJSONObject(0).getString("title"));evidence("B11") }
    @Test fun B12DoneRequiresAdditionalExplicitConfirmation()=io { val d=result();blocked("EXPLICIT_COMPLETION_REQUIRED") { apply(d,JSONArray().put(action("doc","DOCUMENT","done"))) };assertEquals(0,snap().getJSONArray("items").length());evidence("B12") }
    @Test fun B13DocumentDoneDoesNotCompleteWholeBusiness()=io { val d=result();apply(d,JSONArray().put(action("doc","DOCUMENT","done").put("completion_confirmed",true)));assertEquals("done",snap().getJSONArray("items").getJSONObject(0).getString("status"));assertNotEquals("done",snap().getString("task_status"));assertNotEquals("done",snap().optString("manual_status"));evidence("B13") }
    @Test fun B14AvailabilityRevocationStopsReview()=io { val d=result();port().use { it.execute("UPDATE folder_grants SET active=0") };blocked("ANALYSIS_SOURCE_UNAVAILABLE") { apply(d,JSONArray().put(action("doc","DOCUMENT"))) };assertEquals(0,snap().getJSONArray("items").length());evidence("B14") }
    @Test fun B15UnrelatedBoardEditStopsStaleSelection()=io { val d=result();val old=snap();store().use { it.createItem(recording,old,"TODO","수동 작업","","") };blocked("BUSINESS_REVISION_CONFLICT") { apply(d,JSONArray().put(action("doc","DOCUMENT")),expected=old) };assertEquals(1,snap().getJSONArray("items").length());evidence("B15") }
    @Test fun B16CodePointEvidenceMapsToUtf16WithoutChangingQuote()=io { val d=result();val review=store().use { it.businessReview(recording,d) };val q=review.getJSONArray("claims").getJSONObject(0).getJSONArray("evidence").getJSONObject(0);assertEquals(q.getInt("start")+1,q.getInt("utf16_start"));assertEquals(q.getString("quote"),raw.substring(q.getInt("utf16_start"),q.getInt("utf16_end")));evidence("B16","offset_unit" to "unicode_code_points") }
    @Test fun B17ReopenPreservesApprovalAndOriginalEvidence()=io { val d=result();val original=snap().getJSONArray("proposals").getJSONObject(0).toString();apply(d,JSONArray().put(action("doc","DOCUMENT")),"합성 직접 확인");val reopened=snap();assertEquals(original,reopened.getJSONArray("proposals").getJSONObject(0).toString());assertEquals(1,reopened.getJSONArray("reviewed_history").length());assertEquals(1L,count("workflow_business_reviews"));evidence("B17") }
    @Test fun B18PendingRequestRemainsBesidePriorReviewedSummary()=io { store().use { s->s.reviewOwnSummary(recording,s.snapshot(recording),"PUBLIC_SYNTHETIC_PREVIOUS_REVIEW");val req=s.prepareAnalysis(recording,s.snapshot(recording));s.transitionRequest(req,"ERROR",reason="SYNTHETIC_ERROR") }
        val input=CanonicalReviewedDailySource(scoped){settings.snapshot()}.snapshot(ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul")));assertEquals(1,input.expectedCount);assertEquals(1,input.sources.size);assertEquals(1,input.pendingCount);assertFalse(input.fullyReviewed);assertTrue(input.partialPreview().contains("분석 실패"));assertTrue(input.partialPreview().contains("PUBLIC_SYNTHETIC_PREVIOUS_REVIEW"));evidence("B18","partial" to input.partialPreview()) }
    @Test fun B19ReauthenticationEpochCannotApproveOldRequest()=io { val d=result();val epoch=session.begin();session.activate(epoch,"fixture@example.invalid","fixture@example.invalid","BUSINESS-SYN-OWNER","memory-new-session");blocked("ANALYSIS_ACTOR_CHANGED") { apply(d,JSONArray().put(action("doc","DOCUMENT"))) };assertEquals(0,snap().getJSONArray("items").length());evidence("B19") }
    @Test fun B20PreexistingManualDoneAndLockProtectProgress()=io { GeneralLocalStoreTest.host(scoped,keys).use { it.edit(recording,it.snapshot().getJSONObject(0),"PUBLIC_SYNTHETIC_LOCKED_NOTE","done") };dispatch();val d=result();blocked("MANUAL_PROGRESS_PROTECTED") { apply(d,JSONArray().put(action("progress","PROGRESS","in_progress"))) };assertEquals("done",snap().getString("manual_status"));assertEquals(0,snap().getJSONArray("progress_history").length());evidence("B20") }
    @Test fun B21ContentReturnStillRejectsOldSourceVersion()=io { val d=result();source.put(name="통화 녹음 합성상대_01000000001_20261010_100000.wav",alternate=true);GeneralLocalStoreTest.host(scoped,keys).use { it.importSelected(source) };source.put(name="통화 녹음 합성상대_01000000001_20261010_100000.wav");GeneralLocalStoreTest.host(scoped,keys).use { it.importSelected(source) };assertEquals(3,snap().getLong("source_version"));blocked("ANALYSIS_BASE_REVISION_CHANGED") { apply(d,JSONArray().put(action("doc","DOCUMENT"))) };evidence("B21") }
    @Test fun B22UnconfirmedPersonAndTimeKeepProposalSeparate()=io { source.put("two","통화 녹음 합성별도_01000000001_20261010_110000.wav");pipeline().use { p->val b=p.importAndSubmit(source.documents.getValue("two"),true);recording=b.second.getJSONObject("state").getString("sourceId");job=b.first;file=b.second.getJSONObject("files").getJSONObject("result").getString("fileId") };val d=result();blocked("ANALYSIS_IDENTITY_TIME_REVIEW_REQUIRED") { apply(d,JSONArray().put(action("doc","DOCUMENT"))) };assertTrue(snap().isNull("person_id"));assertTrue(snap().isNull("original_started_at_ms"));assertEquals(0,snap().getJSONArray("items").length());evidence("B22") }
    @Test fun B23UncertainAudioRangeCannotBecomeExplicitFact()=io { val d=result(overlap=true);blocked("CLAIM_REVIEW_ONLY") { apply(d,JSONArray().put(action("doc","DOCUMENT"))) };val review=store().use { it.businessReview(recording,d) };assertTrue(review.getJSONArray("claims").getJSONObject(1).getBoolean("selectable"));evidence("B23") }
    @Test fun B24OnlyExplicitTargetIsUpdatedAndKeepsHistory()=io { val a=store().use { it.createItem(recording,snap(),"DOCUMENT","같은 이름 문서","1판","") };store().use { it.createItem(recording,snap(),"DOCUMENT","같은 이름 문서","2판","") };dispatch();val d=result();apply(d,JSONArray().put(action("doc","DOCUMENT","waiting",a,1)));val items=snap().getJSONArray("items");assertEquals(2,items.length());assertEquals(1L,count("workflow_item_history"));assertEquals(1,(0 until items.length()).count { items.getJSONObject(it).getString("status")=="waiting" });evidence("B24") }
    private fun daily()=CanonicalReviewedDailySource(scoped){settings.snapshot()}.snapshot(ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul")))
    @Test fun B25UnknownSecondCallRemainsInPartialExpectedInventory()=io { store().use { it.reviewOwnSummary(recording,snap(),"확인한 합성 첫 통화") };source.put("two","통화 녹음 합성별도_01000000001_20261010_110000.wav");GeneralLocalStoreTest.host(scoped,keys).use { it.importSelected(source) };val input=daily();assertEquals(2,input.expectedCount);assertEquals(1,input.sources.size);assertEquals(1,input.unresolved.size);assertFalse(input.fullyReviewed);assertTrue(input.partialPreview().contains("통화 날짜 미확인"));evidence("B25") }
    @Test fun B26MissingAliasCannotDisappearFromPartialReport()=io { store().use { it.reviewOwnSummary(recording,snap(),"확인한 합성 첫 통화") };port().use { it.execute("DELETE FROM local_files WHERE recording_id=?",recording) };val input=daily();assertEquals(1,input.expectedCount);assertEquals(1,input.pendingCount);assertEquals(recording,input.unresolved.single().id);assertEquals("NATIVE_BINDING_MISSING",input.unresolved.single().reason);assertFalse(input.fullyReviewed);evidence("B26") }
    @Test fun B27CoreFailureStillAppearsBesidePreviousOwnReview()=io { store().use { it.reviewOwnSummary(recording,snap(),"확인한 합성 이전 통화") };port().use { it.execute("UPDATE processing SET state='FAILED' WHERE recording_id=?",recording) };val input=daily();assertEquals(1,input.sources.size);assertEquals("ANALYSIS_FAILED",input.unresolved.single().reason);assertFalse(input.fullyReviewed);evidence("B27") }
    @Test fun B28AddingReviewedDocumentPreservesPreexistingManualDone()=io { GeneralLocalStoreTest.host(scoped,keys).use { it.edit(recording,it.snapshot().getJSONObject(0),"PUBLIC_SYNTHETIC_PREEXISTING_DONE_NOTE","done") };dispatch();val d=result();val before=snap();apply(d,JSONArray().put(action("doc","DOCUMENT","waiting")),"별도로 직접 확인한 합성 요약");val after=snap();for(k in listOf("manual_text","manual_status","completed_lock","edited_lock","user_revision","lock_revision"))assertEquals(before.get(k),after.get(k));assertEquals(1,after.getJSONArray("items").length());evidence("B28") }
    @Test fun B29ExplicitManualProgressRemainsEditableWithoutAnalysisUnlock()=io { val d=result();apply(d,JSONArray().put(action("progress","PROGRESS","in_progress")));store().use { it.editBusinessProgress(recording,snap(),"waiting","합성 승인 대기로 직접 수정",true) };assertEquals("waiting",snap().getString("task_status"));assertEquals(2,snap().getJSONArray("progress_history").length());dispatch();val next=result();blocked("MANUAL_PROGRESS_PROTECTED") { apply(next,JSONArray().put(action("progress","PROGRESS","in_progress"))) };assertEquals("waiting",snap().getString("task_status"));evidence("B29") }
    @Test fun B30UnconfirmedDirectProgressCannotChangeManualStatus()=io { blocked("USER_PROGRESS_CONFIRMATION_REQUIRED") { store().use { it.editBusinessProgress(recording,snap(),"done","합성",false) } };assertEquals(0,snap().getJSONArray("progress_history").length());assertNotEquals("done",snap().getString("task_status"));evidence("B30") }
    private fun field(a:RecordingWorkflowActivity,n:String)=RecordingWorkflowActivity::class.java.getDeclaredField(n).apply { isAccessible=true }.get(a)
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun idle(a:RecordingWorkflowActivity) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(field(a,"busy")==false)return;Thread.sleep(10) };fail("UI did not settle") }
    private fun openReview():Pair<org.robolectric.android.controller.ActivityController<UiHost>,AlertDialog> {
        io { result() };val c=Robolectric.buildActivity(UiHost::class.java,Intent(context,UiHost::class.java).putExtra("recording_id",recording)).setup();idle(c.get())
        views(field(c.get(),"root") as View).filterIsInstance<Button>().single { it.tag=="workflow_review_business" }.performClick();idle(c.get());val dialog=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(dialog.isShowing);return c to dialog
    }
    private fun screen(v:View,name:String) { val w=822;val h=1782;v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(android.graphics.Color.WHITE);v.draw(Canvas(b));File("build/reports/analysis-business/screens").apply { mkdirs() }.resolve("$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) } }
    @Test fun U01NormalReviewCancellationKeepsAllRecords() { val (c,d)=openReview();val before=io { snap().toString() };screen(d.window!!.decorView,"analysis-review-host");d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();idle(c.get());assertEquals(before,io { snap().toString() });c.pause().stop().destroy();evidence("U01") }
    @Test fun U02UncheckedMeaningCannotWriteBusinessReceipt() { val (c,d)=openReview();val choices=views(d.window!!.decorView).filterIsInstance<CheckBox>();choices.first { it.text.toString()=="이 항목을 직접 확인해 저장" }.isChecked=true;d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(c.get());assertEquals(0L,io { count("workflow_business_reviews") });c.pause().stop().destroy();evidence("U02") }
    @Test fun U03NormalConfirmStoresOnlySelectedDocumentAndSummary() { val (c,d)=openReview();val choices=views(d.window!!.decorView).filterIsInstance<CheckBox>();choices.first { it.text.toString()=="이 항목을 직접 확인해 저장" }.isChecked=true
        choices.single { it.text.toString().startsWith("이 요약도") }.isChecked=true;choices.single { it.text.toString().startsWith("선택한 내용의") }.isChecked=true
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(c.get());val s=io { snap() };assertEquals(1,s.getJSONArray("items").length());assertEquals("DOCUMENT",s.getJSONArray("items").getJSONObject(0).getString("kind"));assertEquals(1,s.getJSONArray("reviewed_history").length());assertEquals(0,s.getJSONArray("progress_history").length());assertEquals(1L,io { count("workflow_business_reviews") });screen(field(c.get(),"root") as View,"business-after-review-host");c.pause().stop().destroy();evidence("U03") }
    @Test fun U04DestroyedReviewDialogCallbackWritesNothing() { val (c,d)=openReview();views(d.window!!.decorView).filterIsInstance<CheckBox>().forEach { it.isChecked=true };c.pause().stop().destroy();d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertEquals(0L,io { count("workflow_business_reviews") });evidence("U04") }
    @Test fun U05Font200KeepsScrollableReviewAndExplicitControls() { RuntimeEnvironment.setFontScale(2f);val (c,d)=openReview();val all=views(d.window!!.decorView);assertTrue(all.any { it is ScrollView });assertTrue(all.filterIsInstance<CheckBox>().all { !it.isChecked });screen(d.window!!.decorView,"analysis-review-font200-host");c.pause().stop().destroy();evidence("U05","fontScale" to 2) }
    @Test fun U06DirectProgressButtonRequiresConfirmationAndStoresHistory() { val c=Robolectric.buildActivity(UiHost::class.java,Intent(context,UiHost::class.java).putExtra("recording_id",recording)).setup();idle(c.get());views(field(c.get(),"root") as View).filterIsInstance<Button>().single { it.tag=="workflow_edit_progress" }.performClick();shadowOf(Looper.getMainLooper()).idle();val d=ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        views(d.window!!.decorView).filterIsInstance<Spinner>().single().setSelection(2);d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(0,io { snap().getJSONArray("progress_history").length() });views(d.window!!.decorView).filterIsInstance<CheckBox>().single().isChecked=true;d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle(c.get());assertEquals("in_progress",io { snap().getString("task_status") });assertEquals(1,io { snap().getJSONArray("progress_history").length() });c.pause().stop().destroy();evidence("U06") }
}
