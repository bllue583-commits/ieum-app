package kr.callbrief

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kr.callagent.drive.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real native intake/JCE/SQLite/relay/GUI over fake selected provider + Google + dot fixtures only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="ko-rKR-w360dp-h800dp-xhdpi",shadows=[ShadowTextKeys::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoogleNativeBridgeTest : ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private lateinit var session:GoogleConnectionSession
    private lateinit var scoped:AccountStorageContext
    private lateinit var settings:SecureSettings
    private lateinit var source:GeneralLocalStoreTest.Source
    private lateinit var keys:SyntheticPilotIntegrationTest.HostKeys
    private lateinit var drive:GoogleConnectionRegressionTest.FakeDrive
    private var clock=1000L
    private val executor=Executors.newSingleThreadExecutor()
    private fun <T> io(f:()->T)=executor.submit<T> { f() }.get(30,TimeUnit.SECONDS)
    @Before fun start() {
        context=RuntimeEnvironment.getApplication();session=GoogleConnectionSession(GoogleConnectionRegressionTest.Memory()) {clock}
        val e=session.begin();session.activate(e,"native.synthetic@example.com","native.synthetic@example.com","verified_drive_owner_A","synthetic-memory-token")
        GoogleConnectionSession.installForHost(session);scoped=AccountStorageContext(context,session.ownerBinding());keys=SyntheticPilotIntegrationTest.HostKeys();drive=GoogleConnectionRegressionTest.FakeDrive()
        source=GeneralLocalStoreTest.Source().apply {put()}
        val key=javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        settings=SecureSettings(scoped,object:SecureSettings.KeyAccess {override fun existing()=key;override fun createForFirstEmptyStore()=key})
        settings.update {it.put("treeUri",source.rootUri)}
    }
    @After fun stop() {GoogleConnectionSession.resetForHost();executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    private fun pipeline()=GoogleNativePipeline(context,session,settings,{source},drive,postingCapability={true}) { c->GeneralLocalStoreTest.host(c,keys) }
    private fun ready(p:GoogleNativePipeline){assertEquals("EMPTY",p.setupState());p.initializeEmpty();assertEquals("READY",p.setupState())}
    private fun d()=source.documents.getValue("one")
    private fun db()=TextStoreTestSupport.open(scoped,GeneralLocalStore.DATABASE)
    private fun rows()=io {GeneralLocalStore.catalog(scoped).listFiles()}
    private fun count(t:String)=db().use {it.rawQuery("SELECT COUNT(*) FROM $t",null).use {c->c.moveToFirst();c.getLong(0)}}
    private fun blocked(f:()->Unit){try {f();fail("unsafe operation admitted")}catch(_:IllegalStateException){}catch(_:IllegalArgumentException){}catch(_:kr.co.callsummary.vault.VaultException){}catch(_:android.database.sqlite.SQLiteException){}}
    private fun evidence(id:String,vararg fields:Pair<String,Any>) {
        val prohibited=listOf("합성 검토 내용입니다.","합성 요약 제안 · 자동 확정 없음","SYNTHETIC_USER_NOTE_DONE","synthetic-memory-token")
        scoped.noBackupFilesDir.walkTopDown().filter {it.isFile}.forEach { f->
            val bytes=f.readBytes();for(t in prohibited) assertFalse("native plaintext at-rest",String(bytes,Charsets.UTF_8).contains(t))
        }
        val r=JSONObject().put("id",id).put("scope","SYNTHETIC_NATIVE_API35_HOST_NOT_GOOGLE_OR_DOT_EXECUTION").put("pass",true)
        fields.forEach {r.put(it.first,it.second)};val dir=File("build/reports/google-native-bridge").apply {mkdirs()};File(dir,"$id.json").writeText(r.toString(2))
    }
    private fun request(resultFile:String)=drive.bytes.values.mapNotNull {runCatching {StrictJson.`object`(StrictJson.parse(it))}.getOrNull()}.single {it["kind"]=="CALL_ANALYSIS_REQUEST" && it["resultFileId"]==resultFile}
    private fun proposal(file:String,revision:Long=1):ByteArray {
        val req=request(file);val raw="합성 검토 내용입니다.";val sha=DriveRelay.sha256(raw.toByteArray())
        val provenance=linkedMapOf<String,Any?>("source_kind" to "synthetic_contract_fixture","language" to "ko","run_id" to "native_fixture_only","audio_sha256" to req["audioSha"],"model_name" to null,"model_sha256" to null,"engine_sha256" to null,"execution_observed" to false,"reference_used_in_inference" to false,"quality_state" to "raw_review_required","run_started_at_utc" to null,"run_finished_at_utc" to null,"runner_sha256" to null,"source_commit" to null,"dataset" to null,"dataset_revision" to null,"license" to null)
        val src=mapOf("source_id" to req["sourceId"],"kind" to "call_transcript","raw_text" to raw,"text_sha256" to sha,"recorded_at" to null,"timestamp_basis" to "unknown","case_id" to null,"case_link_confirmed" to false,"speaker_assignment_verified" to false,"uncertain_ranges" to emptyList<Any>(),"provenance" to provenance)
        val packet=linkedMapOf<String,Any?>("schema_version" to 1L,"job_id" to req["jobId"],"base_revision" to req["baseRevision"],"scope" to "synthetic_contract_test","offset_unit" to "unicode_code_points","automatic_summary_allowed" to false,"root_semantic_review_required" to true,"sources" to listOf(src))
        packet["source_pack_sha256"]=DriveRelay.sha256(StrictJson.bytes(packet))
        val candidate=mapOf("schema_version" to 1L,"job_id" to req["jobId"],"base_revision" to req["baseRevision"],"source_pack_sha256" to packet["source_pack_sha256"],"summary" to "합성 요약 제안 · 자동 확정 없음","claims" to emptyList<Any>())
        val r=WireV0Codec.placeholder(req).toMutableMap();r["state"]="PROPOSAL";r["resultRevision"]=revision;r["stt"]=mapOf("execution" to "FIXTURE","text" to raw,"textSha" to sha,"evidenceRef" to "synthetic_fixture_only")
        r["high"]=mapOf("execution" to "FIXTURE","packetJson" to String(StrictJson.bytes(packet)),"candidateJson" to String(StrictJson.bytes(candidate)),"evidenceRef" to "synthetic_fixture_only");r["resultDigest"]=WireV0Codec.digest("result",r)
        return StrictJson.bytes(r).also {assertTrue(WireV0Codec.candidateStructureValid(it,StrictJson.bytes(req)));drive.bytes[file]=it;drive.versions[file]=drive.versions.getValue(file)+1}
    }
    @Test fun B01WithoutVerifiedGoogleSessionNativeFactoryIsNeverOpened(){session.localSignOut();var opened=0;io {blocked {GoogleNativePipeline(context,session,settings,{source},drive,postingCapability={true}){opened++;GeneralLocalStoreTest.host(it,keys)}}};assertEquals(0,opened);evidence("B01")}
    @Test fun B02NormalPathEncryptsRegistersAndUploadsSameSourceBytes(){io {pipeline().use {p->ready(p);val (r,b)=p.importAndSubmit(d(),true);assertEquals(DriveRelay.Outcome.SENT,r.outcome);assertTrue(b.getJSONObject("state").getString("sourceId").startsWith("local_recording_"));assertEquals(session.ownerBinding(),b.getString("driveOwnerPermissionId"));assertEquals(session.nativeSessionBinding().toString(),b.getJSONObject("sessionBinding").toString())
        val audio=b.getJSONObject("files").getJSONObject("audio").getString("fileId");assertArrayEquals(source.bytes.getValue("one"),drive.bytes.getValue(audio));assertEquals(1L,count("vault2_refs"));assertEquals(1L,count("google_native_leases"));assertEquals(0L,count("eum_google_sources"))
    }};assertTrue(source.opens>0);evidence("B02","vault_refs" to 1,"ad_hoc_source_rows" to 0,"real_network" to 0)}
    @Test fun B03NoSyntheticConfirmationDoesNotReadOrRegister(){io {pipeline().use {p->ready(p);blocked {p.importAndSubmit(d(),false)}}};assertEquals(0,source.opens);assertEquals(0,drive.counter);assertEquals(0L,count("recordings"));evidence("B03")}
    @Test fun B04ExcludedBeforeCollectionHasZeroReadsAndUploads(){source.excludedIds+=d().documentId;io {pipeline().use {p->ready(p);blocked {p.importAndSubmit(d(),true)}}};assertEquals(0,source.opens);assertEquals(0,drive.counter);assertEquals(0L,count("recordings"));evidence("B04")}
    @Test fun B05RevokedFolderBeforeCollectionHasZeroReads(){source.granted=false;io {pipeline().use {p->ready(p);blocked {p.importAndSubmit(d(),true)}}};assertEquals(0,source.opens);assertEquals(0,drive.counter);evidence("B05")}
    @Test fun B06UnknownTimePersonAndTaskStayUnknownInTrustedRegistration(){io {pipeline().use {p->ready(p);val b=p.importAndSubmit(d(),true).second;val s=b.getJSONObject("state");assertFalse(s.getBoolean("counterpartConfirmed"));assertTrue(s.getBoolean("syntheticAudioApproved"));val ctx=JSONObject(s.getString("contextJson"));assertTrue(ctx.isNull("personId"));assertTrue(ctx.isNull("taskId"));assertTrue(ctx.isNull("originalStartedAtMs"));assertNotEquals(GeneralLocalStore.OWNER,s.getJSONObject("ownerBinding").getString("ownerId"));assertFalse(b.toString().contains("synthetic-memory-token"))}};evidence("B06")}
    @Test fun B07RepeatedNativeImportReusesJobLeaseAndCipher(){io {pipeline().use {p->ready(p);val first=p.importAndSubmit(d(),true);val second=p.importAndSubmit(d(),true);assertEquals(DriveRelay.Outcome.DUPLICATE,second.first.outcome);assertEquals(first.first.jobId,second.first.jobId);assertEquals(first.second.getJSONObject("state").getLong("leaseEpoch"),second.second.getJSONObject("state").getLong("leaseEpoch"))}};assertEquals(4,drive.counter);assertEquals(1L,count("vault2_refs"));evidence("B07")}
    @Test fun B08AtoBtoAUsesNativeSourceVersionsAndLeases(){io {pipeline().use {p->ready(p);p.importAndSubmit(d(),true);source.put(alternate=true);p.importAndSubmit(d(),true);source.put();val b=p.importAndSubmit(d(),true).second;assertEquals(3L,b.getJSONObject("state").getLong("sourceVersion"));assertEquals(3L,b.getJSONObject("state").getLong("leaseEpoch"))}};assertEquals(3L,count("vault2_refs"));assertEquals(3L,count("source_versions"));evidence("B08")}
    @Test fun B09AcceptedProposalIsLinkedToNativeRecordingWithoutCanonicalSummaryWrite(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);val file=r.second.getJSONObject("files").getJSONObject("result").getString("fileId");proposal(file);assertEquals("LEGACY_CANDIDATE",p.poll(r.first.jobId));assertEquals(1L,count("google_native_proposals"));assertEquals(0L,count("summary_versions"))}};val row=rows().getJSONObject(0);assertEquals("합성 요약 제안 · 자동 확정 없음",row.getString("google_proposal_summary"));assertTrue(row.isNull("summary"));assertTrue(row.isNull("original_date"));evidence("B09","canonical_summary_writes" to 0)}
    @Test fun B10ManualDoneAfterDispatchKeepsDoneAndStoresConflictProposal(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);GeneralLocalStoreTest.host(scoped,keys).use {n->val row=n.snapshot().getJSONObject(0);n.edit(row.getString("file_id"),row,"SYNTHETIC_USER_NOTE_DONE","done")};proposal(r.second.getJSONObject("files").getJSONObject("result").getString("fileId"));p.poll(r.first.jobId)}};val row=rows().getJSONObject(0);assertEquals("done",row.getString("manual_status"));assertEquals("SYNTHETIC_USER_NOTE_DONE",row.getString("manual_note"));assertTrue(row.getBoolean("google_proposal_conflict"));assertTrue(row.has("google_proposal_summary"));evidence("B10")}
    @Test fun B11ArchiveBeforeReplyKeepsArchiveAndHidesProposal(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);GeneralLocalStoreTest.host(scoped,keys).use {n->val row=n.snapshot().getJSONObject(0);n.archive(row.getString("file_id"),row,true)};proposal(r.second.getJSONObject("files").getJSONObject("result").getString("fileId"));p.poll(r.first.jobId)}};val row=rows().getJSONObject(0);assertTrue(row.getBoolean("archived"));assertFalse(row.has("google_proposal_summary"));assertEquals(1L,count("google_native_proposals"));evidence("B11")}
    @Test fun B12ExpiredLeaseBlocksReplyBeforeDownload(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);db().use {it.execSQL("UPDATE google_native_leases SET expires_ms=0")};val n=drive.downloads;blocked {p.poll(r.first.jobId)};assertEquals(n,drive.downloads)}};evidence("B12")}
    @Test fun B13PolicyExclusionChangeBlocksLateReplyBeforeDownload(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);source.excludedIds+=d().documentId;val n=drive.downloads;blocked {p.poll(r.first.jobId)};assertEquals(n,drive.downloads)}};evidence("B13")}
    @Test fun B14FolderGrantLossBlocksLateReplyBeforeDownload(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);source.granted=false;val n=drive.downloads;blocked {p.poll(r.first.jobId)};assertEquals(n,drive.downloads)}};evidence("B14")}
    @Test fun B15GoogleTokenExpiryBlocksNativeKeyAndResultRead(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);clock+=900_001;val reads=keys.accesses.get();blocked {p.poll(r.first.jobId)};assertEquals(reads,keys.accesses.get());assertEquals(0,drive.downloads)}};evidence("B15")}
    @Test fun B16DifferentOwnerCannotReuseNativePipeline(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);val e=session.begin();session.activate(e,"other@example.com","other@example.com","verified_drive_owner_B","fake-token-B");blocked {p.poll(r.first.jobId)}}};assertEquals(0,drive.downloads);evidence("B16")}
    @Test fun B17ForgedLeaseOwnerCannotBeTrusted(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);db().use {it.execSQL("UPDATE google_native_leases SET owner_id='arbitrary_untrusted'")};blocked {p.poll(r.first.jobId)}}};assertEquals(0,drive.downloads);evidence("B17")}
    @Test fun B18SourceVersionChangeRejectsOldReply(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);source.put(alternate=true);p.importAndSubmit(d(),true);val n=drive.downloads;blocked {p.poll(r.first.jobId)};assertEquals(n,drive.downloads)}};evidence("B18")}
    @Test fun B19ErrorRetryKeepsFailureAndNativeSourceLink(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);drive.failure(r.second.getJSONObject("files").getJSONObject("result").getString("fileId"));assertEquals("ERROR",p.poll(r.first.jobId));val next=p.retry(r.first.jobId);assertEquals(DriveRelay.Outcome.SENT,next.outcome);assertEquals(r.second.getJSONObject("state").getString("sourceId"),p.registration(next.jobId).getJSONObject("state").getString("sourceId"));assertEquals("CANCELLED",p.poll(r.first.jobId));assertEquals(1L,count("drive_profiles_receipts"))}};evidence("B19")}
    @Test fun B20ProposalFailureRollsBackProviderReceiptAndPointerTogether(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);val file=r.second.getJSONObject("files").getJSONObject("result").getString("fileId");proposal(file);db().use {it.execSQL("CREATE TRIGGER force_proposal_failure BEFORE INSERT ON google_native_proposals BEGIN SELECT RAISE(ABORT,'SYNTHETIC_TEST_COMMIT_FAILURE'); END")};blocked {p.poll(r.first.jobId)};assertEquals(0L,count("drive_profiles_receipts"));assertEquals(0L,count("google_native_proposal_heads"));db().use {it.execSQL("DROP TRIGGER force_proposal_failure")};assertEquals("LEGACY_CANDIDATE",p.poll(r.first.jobId))}};evidence("B20")}
    @Test fun B21MissingKeystoreKeyBlocksResultBeforeDownload(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);keys.state=kr.co.callsummary.vault.VaultKeys.State.MISSING;blocked {p.poll(r.first.jobId)};assertEquals(0,drive.downloads)}};evidence("B21")}
    @Test fun B22MalformedCandidateNeverProvidesDisplayedSummary(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);val file=r.second.getJSONObject("files").getJSONObject("result").getString("fileId");val v=StrictJson.`object`(StrictJson.parse(proposal(file))).toMutableMap();val h=StrictJson.`object`(v["high"]).toMutableMap();h["candidateJson"]="{\"summary\":\"UNVALIDATED\"}";v["high"]=h;v["resultDigest"]=WireV0Codec.digest("result",v);drive.bytes[file]=StrictJson.bytes(v);p.poll(r.first.jobId)}};assertTrue(rows().getJSONObject(0).isNull("google_proposal_summary"));evidence("B22")}
    @Test fun B25LinkedEvidenceIsAbsentFromRequestWithoutSeparateConsent():Unit=io { pipeline().use { p->ready(p);val first=p.importAndSubmit(d(),true);val id=first.second.getJSONObject("state").getString("sourceId");RecordingWorkflowStore(scoped).use { s->s.addRelated(id,s.snapshot(id),"SMS","PUBLIC_SYNTHETIC_RELATED_BODY","","","합성 문자") };val next=p.submitExisting(id);val context=JSONObject(p.registration(next.jobId).getJSONObject("state").getString("contextJson"));assertFalse(context.has("relatedEvidence"));assertFalse(context.toString().contains("PUBLIC_SYNTHETIC_RELATED_BODY")) } }
    @Test fun B26OnlyConsentedExactEvidenceIsBoundIntoFrozenContext():Unit=io { pipeline().use { p->ready(p);val first=p.importAndSubmit(d(),true);val id=first.second.getJSONObject("state").getString("sourceId");RecordingWorkflowStore(scoped).use { s->s.addRelated(id,s.snapshot(id),"MAIL","PUBLIC_SYNTHETIC_CONSENTED_BODY","","시간 미확인","합성 메일",includeAnalysis=true) };val next=p.submitExisting(id);val b=p.registration(next.jobId);val context=JSONObject(b.getJSONObject("state").getString("contextJson"));val related=context.getJSONArray("relatedEvidence");assertEquals(1,related.length());val body=related.getJSONObject(0);assertEquals(DriveRelay.sha256(body.getString("rawText").toByteArray()),body.getString("contentSha"));assertFalse(body.getBoolean("dataIsInstruction"));assertFalse(b.toString().contains("synthetic-memory-token")) } }
    @Test fun B27EvidenceRevocationBlocksOldResultBeforeDownload():Unit=io { pipeline().use { p->ready(p);val first=p.importAndSubmit(d(),true);val id=first.second.getJSONObject("state").getString("sourceId");val key=RecordingWorkflowStore(scoped).use { s->s.addRelated(id,s.snapshot(id),"MAIL","PUBLIC_SYNTHETIC_REVOKED_BODY","","","합성",includeAnalysis=true) };val next=p.submitExisting(id);RecordingWorkflowStore(scoped).use { it.setEvidenceAnalysisConsent(id,key,false) };val before=drive.downloads;blocked { p.poll(next.jobId) };assertEquals(before,drive.downloads) } }
    @Test fun B28ActualLegacyCandidateManualSyncMapsToReadOnlyReview():Unit=io { pipeline().use { p->ready(p);val first=p.importAndSubmit(d(),true);proposal(first.second.getJSONObject("files").getJSONObject("result").getString("fileId"));kr.co.callsummary.core.android.AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use { port->val result=Code16DotManualSync(session,p,port,null).syncExplicitSynthetic(setOf(first.first.jobId),1).single();assertEquals("READ_ONLY_RESULT_RECEIVED_CONFIRMATION_REQUIRED",result.state);assertEquals(0L,count("summary_versions"));assertEquals(1L,count("google_native_proposals")) } } }
    @Test fun B29UserCanBindExactProposalQuoteToExplicitDocumentWithoutModelAccept():Unit=io { pipeline().use { p->ready(p);val first=p.importAndSubmit(d(),true);val id=first.second.getJSONObject("state").getString("sourceId");proposal(first.second.getJSONObject("files").getJSONObject("result").getString("fileId"));p.poll(first.first.jobId);RecordingWorkflowStore(scoped).use { s->val snap=s.snapshot(id);val candidate=snap.getJSONArray("proposals").getJSONObject(0);val item=s.createItem(id,snap,"DOCUMENT","합성 직접 확인 서류","","",quote="합성 검토 내용입니다.",proposalDigest=candidate.getString("digest"));assertTrue(item.isNotBlank());assertEquals(1,s.snapshot(id).getJSONArray("items").length());assertEquals(0L,count("summary_versions"));blocked { s.createItem(id,s.snapshot(id),"TODO","합성","","",quote="없는 인용",proposalDigest=candidate.getString("digest")) };assertEquals(1,s.snapshot(id).getJSONArray("items").length()) } } }
    @Test fun B30NativeObservationCarriesExactRevisionAndOldReceiptIsHistory():Unit=io {
        pipeline().use { p ->
            ready(p)
            val submitted=p.importAndSubmit(d(),true)
            val job=submitted.first.jobId
            val file=submitted.second.getJSONObject("files").getJSONObject("result").getString("fileId")
            val pending=drive.bytes.getValue(file).clone()
            val first=p.pollObservation(job)
            assertEquals("PENDING",first.disposition)
            assertEquals(0L,first.revision)
            assertEquals(WireV0Codec.digest("result",StrictJson.`object`(StrictJson.parse(pending))),first.digest)
            drive.failure(file)
            val failure=p.pollObservation(job)
            assertEquals("ERROR",failure.disposition)
            assertEquals(1L,failure.revision)
            proposal(file,2)
            // The explicit poll button receives the new result first. Manual sync deliberately
            // refuses a previously known ERROR until the caller polls or starts an explicit retry.
            val current=p.pollObservation(job)
            assertEquals("LEGACY_CANDIDATE",current.disposition)
            assertEquals(2L,current.revision)
            kr.co.callsummary.core.android.AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use { sql ->
                val sync=Code16DotManualSync(session,p,sql,null)
                assertEquals("READ_ONLY_RESULT_RECEIVED_CONFIRMATION_REQUIRED",sync.syncExplicitSynthetic(setOf(job),1).single().state)
                assertEquals(2L,sync.resultObservation(job)!!.revision)
                assertEquals("DUPLICATE_PROPOSAL",sync.resultObservation(job)!!.disposition)
            }
            drive.bytes[file]=pending
            drive.versions[file]=drive.versions.getValue(file)+1
            val old=p.pollObservation(job)
            assertEquals("HISTORY_ONLY",old.disposition)
            assertEquals(0L,old.revision)
            assertEquals(3L,count("drive_profiles_receipts"))
            assertEquals(0L,count("summary_versions"))
        }
        evidence("B30","actual_service_calls" to 0)
    }
    @Test fun B31NativeHeadFencesEarlierUiCommitBeforeCursorExists():Unit=io {
        pipeline().use { p ->
            ready(p)
            val submitted=p.importAndSubmit(d(),true)
            val job=submitted.first.jobId
            val record=submitted.second.getJSONObject("state").getString("sourceId")
            val file=submitted.second.getJSONObject("files").getJSONObject("result").getString("fileId")
            val intent=RecordingWorkflowStore(scoped).use { store ->
                store.prepareAnalysis(record,store.snapshot(record)).also { store.transitionRequest(it,"SENT",job) }
            }
            drive.failure(file)
            val earlier=p.pollObservation(job)
            proposal(file,2)
            val newer=p.pollObservation(job)
            RecordingWorkflowStore(scoped).use { store ->
                assertEquals("SENT",store.commitResultObservation(intent,earlier))
                assertEquals("OLD_RESULT_HISTORY_PRESERVED",store.request(intent).getString("reason"))
                assertEquals("REVIEW",store.commitResultObservation(intent,newer))
                assertEquals("REVIEW",store.commitResultObservation(intent,earlier))
            }
            assertEquals(0L,count("summary_versions"))
        }
        evidence("B31","earlier_result_did_not_advance_ui" to true)
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
    private fun screen(v:View,name:String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1600,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1600)
        val b=android.graphics.Bitmap.createBitmap(720,1600,android.graphics.Bitmap.Config.ARGB_8888);v.draw(android.graphics.Canvas(b))
        val dir=File("build/reports/native-google-screens").apply {mkdirs()};java.io.FileOutputStream(File(dir,"$name.png")).use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun settle(a:MainActivity){repeat(300){shadowOf(Looper.getMainLooper()).idle();if(MainActivity::class.java.getDeclaredField("catalogLoading").apply {isAccessible=true}.get(a)==false)return;Thread.sleep(10)};fail("UI not settled")}
    @Test fun B23ProposalVisibleInUnknownDateAndBusinessViewsWithDonePreserved(){io {pipeline().use {p->ready(p);val r=p.importAndSubmit(d(),true);GeneralLocalStoreTest.host(scoped,keys).use {n->val row=n.snapshot().getJSONObject(0);n.edit(row.getString("file_id"),row,"USER_NATIVE_NOTE","done")};proposal(r.second.getJSONObject("files").getJSONObject("result").getString("fileId"));p.poll(r.first.jobId)}}
        val c=Robolectric.buildActivity(MainActivity::class.java).setup();val a=c.get();settle(a);val root=a.findViewById<View>(android.R.id.content)
        views(root).filterIsInstance<Button>().single {it.tag=="unknown_date_filter"}.performClick();settle(a)
        assertTrue(views(root).filterIsInstance<TextView>().any {it.tag=="google_native_proposal_summary" && it.text.toString()=="합성 요약 제안 · 자동 확정 없음"})
        screen(root,"date-native-proposal-host")
        views(root).filterIsInstance<Button>().single {it.tag=="primary_tab_1"}.performClick();settle(a);assertTrue(views(root).any {it.tag=="google_native_proposal_summary"});screen(root,"business-native-proposal-host");assertEquals("done",rows().getJSONObject(0).getString("manual_status"));c.pause().stop().destroy();evidence("B23")
    }
    @Test fun B24GoogleTrialButtonIsEnabledWithoutGrantAndExplainsNextAction(){session.localSignOut();val c=Robolectric.buildActivity(NativeGoogleTrialActivity::class.java).setup();val buttons=views(c.get().findViewById(android.R.id.content)).filterIsInstance<Button>();val b=buttons.single {it.text.toString()=="Google 계정·Drive 연결 확인"};assertTrue(b.isEnabled);b.performClick();assertEquals(ConnectionCenterActivity::class.java.name,shadowOf(c.get()).nextStartedActivity.component!!.className);c.pause().stop().destroy();evidence("B24")}
}
