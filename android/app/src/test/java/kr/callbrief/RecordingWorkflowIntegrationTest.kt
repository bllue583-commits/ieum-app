package kr.callbrief

import android.content.Context
import kr.callbrief.core.PrivacyRules
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Production workflow/native SQLite/JCE on generated files. No accounts/providers/network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordingWorkflowIntegrationTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var scoped:Context
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var session:GoogleConnectionSession
    private lateinit var source:GeneralLocalStoreTest.Source
    private var recording=""
    private val phone="010"+"0".repeat(8) // Generated parser fixture, never an actual contact.
    private val worker=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T):T=try { worker.submit<T>{f()}.get(30,TimeUnit.SECONDS) } catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
    private class Memory:GoogleConnectionSession.Persistence { var json=JSONObject();override fun read()=JSONObject(json.toString());override fun write(value:JSONObject){json=JSONObject(value.toString())} }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();keys=SyntheticPilotIntegrationTest.HostKeys();session=GoogleConnectionSession(Memory());scoped=context
        source=GeneralLocalStoreTest.Source().apply { put(name="통화 녹음 합성 거래처_${phone}_20261010_100000.wav") }
        io { seed() }
    }
    private fun seed() {
        GeneralLocalStoreTest.host(scoped,keys).use { store ->
            store.initializeEmpty();store.importSelected(source);val r=store.snapshot().getJSONObject(0);recording=r.getString("file_id")
            store.confirmTime(recording,r,Instant.parse("2026-10-10T01:00:00Z").toEpochMilli())
            val next=store.snapshot().getJSONObject(0);store.edit(recording,next,"PUBLIC_SYNTHETIC_MANUAL_NOTE","done")
        }
    }
    private fun activeOwner() {
        val epoch=session.begin();session.activate(epoch,"fixture@example.invalid","fixture@example.invalid","SYN-OWNER-A","fixture-memory-token")
        scoped=AccountStorageContext(context,session.ownerBinding());keys=SyntheticPilotIntegrationTest.HostKeys();seed()
    }
    private fun snap()=RecordingWorkflowStore(scoped).use { it.snapshot(recording) }
    private fun work()=RecordingWorkflowStore(scoped).use { it.confirmWork(recording,it.snapshot(recording),"합성 담당자","합성 회사","합성 업무",phone) }
    private fun port()=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql"))
    private fun fails(code:String,f:()->Unit) { try { f();fail("Expected $code") } catch(e:Exception) { assertTrue(e.message.orEmpty(),e.message.orEmpty().contains(code)) } }
    @After fun stop() { worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    @Test fun F01ExplicitIdentityChangesKeepManualDoneAndNote()=io {
        work();val s=snap();assertEquals("합성 담당자",s.getString("person_label"));assertEquals("합성 업무",s.getString("work_label"));assertEquals("done",s.getString("manual_status"));assertEquals("PUBLIC_SYNTHETIC_MANUAL_NOTE",s.getString("manual_text"));assertTrue(s.getLong("completed_lock")==1L)
    }
    @Test fun F02SamePhoneDoesNotMergePeopleOrCases()=io {
        val first=work();source.put("two","통화 녹음 합성 두번째_${phone}_20261010_100200.wav")
        val second=GeneralLocalStoreTest.host(scoped,keys).use { it.importSelected(source);val all=it.snapshot();(0 until all.length()).map { n->all.getJSONObject(n) }.single { r->r.getString("actual_uri").endsWith("/two") }.getString("file_id") }
        RecordingWorkflowStore(scoped).use { s->val next=s.confirmWork(second,s.snapshot(second),"합성 두번째","합성 회사","별도 업무",phone);assertNotEquals(first,next);assertNotEquals(s.snapshot(recording).getString("person_id"),s.snapshot(second).getString("person_id")) }
    }
    @Test fun F03OnlyExplicitExistingCaseSelectionReusesIdentity()=io {
        val first=work();RecordingWorkflowStore(scoped).use { s->assertEquals(first,s.confirmWork(recording,s.snapshot(recording),"","","","",first));assertEquals("합성 담당자",s.snapshot(recording).getString("person_label")) }
    }
    @Test fun F04SameNumberSmsRepeatedImportHasOneSourceAndLink()=io {
        work();RecordingWorkflowStore(scoped).use { s->val a=s.addRelated(recording,s.snapshot(recording),"SMS","합성 문자 근거",phone,"시간 미확인","합성 문자");val b=s.addRelated(recording,s.snapshot(recording),"SMS","합성 문자 근거",phone,"시간 미확인","합성 문자");assertEquals(a,b);val r=s.snapshot(recording).getJSONArray("related");assertEquals(1,r.length());assertEquals(1,r.getJSONObject(0).getInt("same_phone"));assertEquals("done",s.snapshot(recording).getString("manual_status")) }
    }
    @Test fun F05DifferentNumberCannotLinkAsSameCounterpart()=io {
        work();RecordingWorkflowStore(scoped).use { s->fails("COUNTERPART_MISMATCH") { s.addRelated(recording,s.snapshot(recording),"SMS","합성 문장","010"+"0".repeat(7)+"1","","합성") };assertEquals(0,s.snapshot(recording).getJSONArray("related").length()) }
    }
    @Test fun F06UnknownNumberStaysIdentityUnconfirmed()=io {
        RecordingWorkflowStore(scoped).use { s->s.addRelated(recording,s.snapshot(recording),"SMS","합성 문장","","","합성");val related=s.snapshot(recording).getJSONArray("related").getJSONObject(0);assertEquals(0,related.getInt("same_phone"));assertEquals("USER_LINKED_IDENTITY_UNCONFIRMED",related.getString("relation")) }
    }
    @Test fun F07RelatedRawAndReceiptCanariesDoNotAppearOnDisk()=io {
        val canary="PUBLIC_SYNTHETIC_RELATED_CANARY_17";RecordingWorkflowStore(scoped).use { s->s.addRelated(recording,s.snapshot(recording),"MAIL",canary,"","","합성 메일") }
        val bytes=ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE).readBytes();assertFalse(String(bytes,Charsets.ISO_8859_1).contains(canary));assertEquals(canary,snap().getJSONArray("related").getJSONObject(0).getString("raw_text"))
    }
    @Test fun F08TodoDoneAndDocumentOpenReopenSeparately()=io {
        RecordingWorkflowStore(scoped).use { s->val todo=s.createItem(recording,s.snapshot(recording),"TODO","합성 회신","","기한 미확인");s.editItem(todo,1,"합성 회신","done","기한 미확인","");s.createItem(recording,s.snapshot(recording),"DOCUMENT","합성 수정 견적서","2판","") }
        val items=snap().getJSONArray("items");assertEquals(2,items.length());assertEquals("done",items.getJSONObject(0).getString("status"));assertEquals("open",items.getJSONObject(1).getString("status"));assertEquals("done",snap().getString("manual_status"))
    }
    @Test fun F09StaleTodoRevisionCannotOverwriteDone()=io {
        RecordingWorkflowStore(scoped).use { s->val todo=s.createItem(recording,s.snapshot(recording),"TODO","합성","","");s.editItem(todo,1,"합성","done","","");fails("ITEM_REVISION_CONFLICT") { s.editItem(todo,1,"오래된 합성","open","","") };assertEquals("done",s.snapshot(recording).getJSONArray("items").getJSONObject(0).getString("status")) }
    }
    @Test fun F10StaleRecordingSnapshotRollsBackNewWork()=io {
        val before=snap();work();RecordingWorkflowStore(scoped).use { s->fails("USER_REVISION_CONFLICT") { s.confirmWork(recording,before,"오래된 합성","","오래된 업무","") };assertEquals(1,s.cases().length()) }
    }
    @Test fun F11OwnSummaryIsHistoryAndDoesNotFakeCanonicalModelAccept()=io {
        RecordingWorkflowStore(scoped).use { s->s.reviewOwnSummary(recording,s.snapshot(recording),"직접 확인한 합성 요약");val r=s.snapshot(recording);assertEquals(1,r.getJSONArray("reviewed_history").length());assertTrue(r.isNull("effective_summary_version"));assertEquals("done",r.getString("manual_status"));assertEquals("PUBLIC_SYNTHETIC_MANUAL_NOTE",r.getString("manual_text")) }
    }
    @Test fun F12NewContentVersionKeepsOwnSummaryHistoryButNotCurrent()=io {
        RecordingWorkflowStore(scoped).use { s->s.reviewOwnSummary(recording,s.snapshot(recording),"직접 확인 합성 A") };source.put(alternate=true,name="통화 녹음 합성 거래처_${phone}_20261010_100000.wav")
        GeneralLocalStoreTest.host(scoped,keys).use { it.importSelected(source) };val r=snap();assertEquals(2,r.getLong("source_version"));assertEquals(1,r.getJSONArray("reviewed_history").length());assertTrue(r.isNull("reviewed_summary"));assertEquals("done",r.getString("manual_status"))
    }
    @Test fun F13OwnReviewedSummaryAndTypedChecklistFeedReportWithoutSender()=io {
        work();RecordingWorkflowStore(scoped).use { s->s.createItem(recording,s.snapshot(recording),"DOCUMENT","합성 도면","1판","");s.createItem(recording,s.snapshot(recording),"TODO","합성 회신","","기한 미확인");s.reviewOwnSummary(recording,s.snapshot(recording),"직접 확인한 합성 요약") }
        val input=CanonicalReviewedDailySource(scoped).snapshot(ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul")))
        assertEquals(ReportReadiness.REVIEWED,input.readiness);assertTrue(input.fullyReviewed);assertTrue(input.preview().contains("직접 확인 요약"));assertTrue(input.preview().contains("필요 서류"));assertTrue(input.preview().contains("todo"));assertEquals("USER_REVIEWED_LOCAL_NOT_MODEL_ATTESTATION",input.sources.single().summaryOrigin)
    }
    @Test fun F14UnconfirmedWorkCannotMakeCompleteReport()=io {
        RecordingWorkflowStore(scoped).use { it.reviewOwnSummary(recording,it.snapshot(recording),"합성 요약") };val input=CanonicalReviewedDailySource(scoped).snapshot(ReportWindow(LocalDate.parse("2026-10-10"),ZoneId.of("Asia/Seoul")));assertFalse(input.fullyReviewed)
    }
    private class FakePort(var failure:Boolean=false):RecordingAnalysisPort {
        var submits=0;var retries=0;var result="PENDING"
        var syncCalls=0;var syncResult="AWAITING_SUPPORTED_DOT_ENTRY";var exports=0
        override fun submit(recording:String):RecordingAnalysisPort.Sent { submits++;if(failure)throw IOException("synthetic ack loss");return RecordingAnalysisPort.Sent("SENT","SYN-JOB") }
        override fun poll(job:String)=result
        override fun retry(job:String):RecordingAnalysisPort.Sent { retries++;return RecordingAnalysisPort.Sent("SENT","SYN-JOB-RETRY") }
        override fun jobs()=emptyList<GoogleRelayStore.JobView>()
        override fun sync(job:String):String { syncCalls++;return syncResult }
        override fun syncObservation(job:String)=RecordingAnalysisPort.SyncObservation(sync(job),if(syncResult=="READ_ONLY_RESULT_RECEIVED_CONFIRMATION_REQUIRED")GoogleRelayStore.ResultObservation(job,"LEGACY_CANDIDATE","CALL_ANALYSIS_RESULT",0,1,"a".repeat(64))else null)
        override fun registration(job:String):JSONObject { exports++;return JSONObject().put("jobId",job).put("syntheticFixture",true) }
        override fun cancel() {} ;override fun close() {}
    }
    @Test fun F15PreparedMetadataWithoutGoogleOpensNoTransport()=io {
        val port=FakePort();AnalysisRequestFlow(context,session,{port}).use { f->val request=f.prepare(recording,snap());assertEquals("HELD_AUTH",f.submit(request));assertEquals(0,port.submits) }
    }
    @Test fun F16DoubleSubmitUsesOneDurableRequestAndOneTransport()=io {
        activeOwner();val port=FakePort();AnalysisRequestFlow(context,session,{port}).use { f->val request=f.prepare(recording,snap());assertEquals(request,f.prepare(recording,snap()));assertEquals("SENT",f.submit(request));assertEquals("SENT",f.submit(request));assertEquals(1,port.submits) }
    }
    @Test fun F17AckLossIsUnknownAndIsNeverAutoResent()=io {
        activeOwner();val port=FakePort(true);var request="";AnalysisRequestFlow(context,session,{port}).use { f->request=f.prepare(recording,snap());assertEquals("UNKNOWN",f.submit(request)) }
        AnalysisRequestFlow(context,session,{port}).use { assertEquals("UNKNOWN",it.submit(request)) };assertEquals(1,port.submits)
    }
    @Test fun F18CancelledRequestNeverSubmits()=io {
        activeOwner();val port=FakePort();val request=RecordingWorkflowStore(scoped).use { s->val r=s.prepareAnalysis(recording,s.snapshot(recording));s.transitionRequest(r,"CANCELLED");r }
        AnalysisRequestFlow(context,session,{port}).use { assertEquals("CANCELLED",it.submit(request)) };assertEquals(0,port.submits)
    }
    @Test fun F19InFlightSameProcessRequestDoesNotBecomeUnknown()=io {
        activeOwner();val port=FakePort();val request=RecordingWorkflowStore(scoped).use { s->val r=s.prepareAnalysis(recording,s.snapshot(recording));s.transitionRequest(r,"SUBMITTING");r }
        AnalysisRequestFlow(context,session,{port}).use { assertEquals("SUBMITTING",it.submit(request)) };assertEquals(0,port.submits)
    }
    @Test fun F20OldProcessClaimReopensUnknownAndDoesNotAutoSend()=io {
        activeOwner();val request=RecordingWorkflowStore(scoped).use { s->val r=s.prepareAnalysis(recording,s.snapshot(recording));s.transitionRequest(r,"SUBMITTING");r }
        port().use { it.execute("UPDATE workflow_analysis_claims SET process_token='synthetic-prior-process'") };val reopened=snap();assertEquals("UNKNOWN",reopened.getJSONArray("requests").getJSONObject(0).getString("state"));val remote=FakePort();AnalysisRequestFlow(context,session,{remote}).use { assertEquals("UNKNOWN",it.submit(request)) };assertEquals(0,remote.submits)
    }
    @Test fun F21SourceEditAfterPreparationBlocksBeforeTransport()=io {
        activeOwner();val port=FakePort();AnalysisRequestFlow(context,session,{port}).use { f->val request=f.prepare(recording,snap());GeneralLocalStoreTest.host(scoped,keys).use { s->s.edit(recording,s.snapshot().getJSONObject(0),"합성 변경","done") };fails("REQUEST_BINDING_CHANGED") { f.submit(request) };assertEquals(0,port.submits) }
    }
    @Test fun F22EvidenceAnalysisDefaultIsOffAndRevocationIsDurable()=io {
        RecordingWorkflowStore(scoped).use { s->val key=s.addRelated(recording,s.snapshot(recording),"MAIL","합성 원문","","","합성 메일");port().use { p->assertEquals(0L,p.query("SELECT allowed FROM workflow_source_analysis_policy").single()["allowed"]) };s.setEvidenceAnalysisConsent(recording,key,true);s.setEvidenceAnalysisConsent(recording,key,false) }
        port().use { p->assertEquals(1L,p.query("SELECT epoch FROM workflow_analysis_authority WHERE recording_id=?",recording).single()["epoch"]) };assertEquals(1,snap().getJSONArray("related").length())
    }
    @Test fun F23ExcludedSmsIsNotPersistedOrLinked()=io {
        work();val policy=JSONObject().put("exclusions",org.json.JSONArray().put(JSONObject().put("type","phone").put("hash",PrivacyRules.hash(phone))))
        RecordingWorkflowStore(scoped,policySnapshot={policy}).use { s->fails("EXCLUDED_BEFORE_READ") { s.addRelated(recording,s.snapshot(recording),"SMS","차단 합성 원문",phone,"","합성") };assertEquals(0,s.snapshot(recording).getJSONArray("related").length()) }
    }
    @Test fun F35ManualSyncDoesNotClaimDotRunWithoutSupportedEntry()=io {
        activeOwner();val remote=FakePort();AnalysisRequestFlow(context,session,{remote}).use { f->val id=f.prepare(recording,snap());f.submit(id);assertEquals("AWAITING_SUPPORTED_DOT_ENTRY",f.sync(id));assertEquals("SENT",RecordingWorkflowStore(scoped).use { it.request(id).getString("state") });assertEquals(1,remote.submits);assertEquals(1,remote.syncCalls) }
    }
    @Test fun F36ManualSyncReadOnlyResultMapsToReviewWithoutApplying()=io {
        activeOwner();val remote=FakePort().apply { syncResult="READ_ONLY_RESULT_RECEIVED_CONFIRMATION_REQUIRED" }
        AnalysisRequestFlow(context,session,{remote}).use { f->val id=f.prepare(recording,snap());f.submit(id);f.sync(id);assertEquals("REVIEW",RecordingWorkflowStore(scoped).use { it.request(id).getString("state") });assertTrue(snap().isNull("effective_summary_version"));assertEquals("done",snap().getString("manual_status")) }
    }
    @Test fun F37ExportChecksCurrentActorBeforeCallingNativePort()=io {
        activeOwner();val remote=FakePort();AnalysisRequestFlow(context,session,{remote}).use { f->val id=f.prepare(recording,snap());f.submit(id);assertEquals("SYN-JOB",JSONObject(f.export(id)).getString("jobId"));assertEquals(1,remote.exports);session.begin();fails("ACCOUNT_SESSION_CHANGED") { f.export(id) };assertEquals(1,remote.exports) }
    }
    @Test fun F38ExactMailEvidenceCanCreateExplicitDocumentWithoutAutoCompletion()=io {
        RecordingWorkflowStore(scoped).use { s->val key=s.addRelated(recording,s.snapshot(recording),"MAIL","합성 도면 2판을 보내 주세요.","","","합성");val item=s.createItem(recording,s.snapshot(recording),"DOCUMENT","합성 도면","2판","",key,"합성 도면 2판을 보내 주세요.");assertEquals(key,s.snapshot(recording).getJSONArray("items").getJSONObject(0).getString("source_key"));assertEquals("open",s.snapshot(recording).getJSONArray("items").getJSONObject(0).getString("status"));assertEquals("done",s.snapshot(recording).getString("manual_status"));assertTrue(item.startsWith("wf_item_")) }
    }
    @Test fun F39InventedEvidenceCannotCreateItemOrReceipt()=io {
        RecordingWorkflowStore(scoped).use { s->val key=s.addRelated(recording,s.snapshot(recording),"SMS","실제로 선택한 합성 문장","","","합성");val before=s.snapshot(recording).getJSONArray("manual_history").length();fails("EXACT_EVIDENCE_REQUIRED") { s.createItem(recording,s.snapshot(recording),"TODO","합성","","",key,"없는 문장") };assertEquals(0,s.snapshot(recording).getJSONArray("items").length());assertEquals(before,s.snapshot(recording).getJSONArray("manual_history").length()) }
    }
    @Test fun F40ExplicitDoneCreationIsOneAtomicReceiptAndInitialRevision()=io {
        RecordingWorkflowStore(scoped).use { s->s.createItem(recording,s.snapshot(recording),"TODO","합성 확인","","",initialStatus="done");val r=s.snapshot(recording);assertEquals("done",r.getJSONArray("items").getJSONObject(0).getString("status"));assertEquals(1,r.getJSONArray("items").getJSONObject(0).getLong("revision"));assertEquals(1,r.getJSONArray("manual_history").length()) }
    }
    private fun mailBatch(tag:String="SYN-RUN-1",body:String?="필요 서류: 합성 도면\ntodo: 합성 검토") : kr.callbrief.navermail.ScanBatch {
        val account=kr.callbrief.navermail.AccountBinding(GeneralLocalStore.OWNER,"fixture-mail@naver.com")
        val scope=kr.callbrief.navermail.MailScope(account,"unbound-case","unbound-person",setOf("counterpart@example.invalid"),setOf("계약"),LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-12"),relationshipConfirmedByUser=true)
        val h=kr.callbrief.navermail.HeaderEnvelope(kr.callbrief.navermail.MailRef(account.key,"INBOX",100,7),Instant.parse("2026-10-10T01:30:00Z"),"<synthetic@example.invalid>",setOf("counterpart@example.invalid"),setOf(account.address),"합성 계약 자료",PrivacyRules.hash("header-fixture"),"text/plain",origin=kr.callbrief.navermail.Origin.SYNTHETIC_FIXTURE)
        val candidate=kr.callbrief.navermail.MailCandidate(h,scope.fingerprint,kr.callbrief.navermail.Relation.CANDIDATE,listOf("사용자 선택 범위"),listOf("업무 의미 확인 필요"),if(body==null)kr.callbrief.navermail.BodyState.NOT_SELECTED else kr.callbrief.navermail.BodyState.PLAIN_AVAILABLE)
        return kr.callbrief.navermail.ScanBatch(account,scope,1,kr.callbrief.navermail.Origin.SYNTHETIC_FIXTURE,listOf(candidate),false,emptyList(),
            if(body==null)emptyMap() else mapOf(h.ref to kr.callbrief.navermail.PlainBody(h.ref,body,false,h.origin)),tag)
    }
    private fun mailInbox(record:String?=recording,allowed:()->Boolean={true})=WorkflowNaverInbox(scoped,GeneralLocalStore.OWNER,record,allowed)
    @Test fun F24SelectedNaverBodyIsDurableCandidateNotImplicitBusinessLink()=io {
        mailInbox().replace(mailBatch());val r=snap();assertEquals(1,r.getJSONArray("naver_candidates").length());assertEquals(0,r.getJSONArray("related").length());assertEquals("done",r.getString("manual_status"));assertEquals("필요 서류: 합성 도면\ntodo: 합성 검토",r.getJSONArray("naver_candidates").getJSONObject(0).getString("raw_text"))
    }
    @Test fun F25ExplicitNaverConfirmationLinksExactRawButDoesNotApplyProposals()=io {
        mailInbox().replace(mailBatch());RecordingWorkflowStore(scoped).use { s->val r=s.snapshot(recording);s.confirmNaverCandidate(recording,r,r.getJSONArray("naver_candidates").getJSONObject(0).getString("digest"));val next=s.snapshot(recording);assertEquals(1,next.getJSONArray("related").length());assertEquals(0,next.getJSONArray("items").length());assertEquals("done",next.getString("manual_status")) }
    }
    @Test fun F26DuplicateNaverReceiptAndConfirmationHaveOneSource()=io {
        val b=mailBatch();mailInbox().replace(b);mailInbox().replace(b)
        RecordingWorkflowStore(scoped).use { s->val digest=s.snapshot(recording).getJSONArray("naver_candidates").getJSONObject(0).getString("digest");val a=s.confirmNaverCandidate(recording,s.snapshot(recording),digest);val next=s.confirmNaverCandidate(recording,s.snapshot(recording),digest);assertEquals(a,next);assertEquals(1,s.snapshot(recording).getJSONArray("related").length());assertEquals(1,s.naverCandidates().length()) }
    }
    @Test fun F27GlobalMailboxCandidateNeedsExplicitRecordingSelection()=io {
        mailInbox(record=null).replace(mailBatch());assertEquals(0,snap().getJSONArray("naver_candidates").length())
        RecordingWorkflowStore(scoped).use { s->val candidates=s.naverCandidates();assertEquals(1,candidates.length());s.confirmNaverCandidate(recording,s.snapshot(recording),candidates.getJSONObject(0).getString("digest"));assertEquals(1,s.snapshot(recording).getJSONArray("related").length()) }
    }
    @Test fun F28HeaderOnlyNeverPretendsSelectedBodyExists()=io {
        mailInbox().replace(mailBatch(body=null));RecordingWorkflowStore(scoped).use { s->val c=s.snapshot(recording).getJSONArray("naver_candidates").getJSONObject(0);fails("MAIL_BODY_NOT_SELECTED") { s.confirmNaverCandidate(recording,s.snapshot(recording),c.getString("digest")) };assertEquals(0,s.snapshot(recording).getJSONArray("related").length()) }
    }
    @Test fun F29WrongBodyUidRollsBackEntireBatch()=io {
        val b=mailBatch();val ref=b.candidates.single().header.ref;val forged=b.copy(selectedBodies=mapOf(ref to b.selectedBodies.getValue(ref).copy(ref=ref.copy(uid=999))))
        fails("MAIL_BODY_BINDING_CHANGED") { mailInbox().replace(forged) };assertEquals(0,snap().getJSONArray("naver_candidates").length())
    }
    @Test fun F30OwnerChangeBeforeCommitRollsBackMailCandidate()=io {
        var checks=0;RecordingWorkflowStore(scoped).use { s->fails("MAIL_OWNER_CHANGED") { s.storeNaverBatch(mailBatch(),recording){++checks<2} };assertEquals(0,s.naverCandidates().length()) }
    }
    @Test fun F31DisconnectFencesAnalysisButKeepsConfirmedMailAndDone()=io {
        val b=mailBatch();mailInbox().replace(b);RecordingWorkflowStore(scoped).use { s->val c=s.snapshot(recording).getJSONArray("naver_candidates").getJSONObject(0);val key=s.confirmNaverCandidate(recording,s.snapshot(recording),c.getString("digest"));s.setEvidenceAnalysisConsent(recording,key,true);s.revokeNaverAccount(b.account.key);fails("MAIL_CONNECTION_REVIEW_REQUIRED") { s.setEvidenceAnalysisConsent(recording,key,true) };val r=s.snapshot(recording);assertEquals(1,r.getJSONArray("related").length());assertEquals(0,r.getJSONArray("naver_candidates").length());assertEquals("done",r.getString("manual_status")) }
    }
    @Test fun F32FreshConnectionCannotReviveOldCandidateApproval()=io {
        val b=mailBatch();mailInbox().replace(b);RecordingWorkflowStore(scoped).use { s->val old=s.naverCandidates().getJSONObject(0).getString("digest");s.revokeNaverAccount(b.account.key);mailInbox().replace(mailBatch("SYN-RUN-2"));fails("MAIL_CONNECTION_REVIEW_REQUIRED") { s.confirmNaverCandidate(recording,s.snapshot(recording),old) };val next=s.naverCandidates();assertEquals(1,next.length());assertNotEquals(old,next.getJSONObject(0).getString("digest"));s.confirmNaverCandidate(recording,s.snapshot(recording),next.getJSONObject(0).getString("digest"));assertEquals(1,s.snapshot(recording).getJSONArray("related").length()) }
    }
    private class MailFake(private val b:kr.callbrief.navermail.ScanBatch):kr.callbrief.navermail.ReadOnlyMailbox {
        override val origin=kr.callbrief.navermail.Origin.SYNTHETIC_FIXTURE
        override fun open(account:kr.callbrief.navermail.AccountBinding,secret:kr.callbrief.navermail.SecretChars)=object:kr.callbrief.navermail.ReadOnlyMailbox.Session {
            override fun examineInbox()=100L
            override fun search(scope:kr.callbrief.navermail.MailScope)=kr.callbrief.navermail.SearchResult(listOf(7L),false)
            override fun header(ref:kr.callbrief.navermail.MailRef)=b.candidates.single().header
            override fun selectedPlainBody(header:kr.callbrief.navermail.HeaderEnvelope)=b.selectedBodies.getValue(header.ref)
            override fun close(){}
        }
    }
    @Test fun F33ExistingMailIntakeDeliversImmutableFullBodyToHost()=io {
        val b=mailBatch();val intake=kr.callbrief.navermail.MailIntake(kr.callbrief.navermail.AccountActionPolicy.denied(),MailFake(b),mailInbox())
        val scanned=intake.scan(intake.select(b.scope),kr.callbrief.navermail.SecretChars(CharArray(8){'x'}));assertTrue(scanned.selectedBodies.isEmpty())
        val ready=intake.readSelected(scanned,setOf(scanned.candidates.single().header.ref),kr.callbrief.navermail.SecretChars(CharArray(8){'x'}));assertEquals(b.selectedBodies.values.single().text,ready.selectedBodies.values.single().text)
        try { (ready.selectedBodies as MutableMap).clear();fail("body receipts must be immutable") } catch(_:UnsupportedOperationException) {}
        assertEquals(2,snap().getJSONArray("naver_candidates").length()) // Header receipt + selected-body receipt.
    }
    @Test fun F34BackgroundStopKeepsAlreadyReceivedHostHistory()=io {
        val b=mailBatch();val intake=kr.callbrief.navermail.MailIntake(kr.callbrief.navermail.AccountActionPolicy.denied(),MailFake(b),mailInbox())
        val scanned=intake.scan(intake.select(b.scope),kr.callbrief.navermail.SecretChars(CharArray(8){'x'}));intake.readSelected(scanned,setOf(scanned.candidates.single().header.ref),kr.callbrief.navermail.SecretChars(CharArray(8){'x'}));intake.stopForBackground();assertEquals(2,snap().getJSONArray("naver_candidates").length())
        try { intake.readSelected(scanned,setOf(scanned.candidates.single().header.ref),kr.callbrief.navermail.SecretChars(CharArray(8){'x'}));fail("background receipt must be cancelled") } catch(_:kr.callbrief.navermail.MailFailure) {}
    }
}
