package kr.callbrief

import android.content.Context
import android.content.ContextWrapper
import kr.callagent.drive.*
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Public bundled WAV sandbox. Real Android JNI/Keystore/SQLite; native transport is in-memory.
 * Never installs a global session/provider, reads actual accounts or touches normal user stores. */
internal class AndroidLocalSttPilot(context:Context,exercise:String="public-v1"):AutoCloseable {
    init{check(BuildConfig.SYNTHETIC_TOOLS_ENABLED){"SYNTHETIC_TOOLS_DISABLED"};require(exercise.matches(Regex("[a-z0-9-]{1,40}")))}
    private val base=object:ContextWrapper(context.applicationContext) {
        override fun getApplicationContext():Context=this
        override fun getNoBackupFilesDir()=File(baseContext.noBackupFilesDir,"android-local-stt-public/$exercise").apply{mkdirs()}
        override fun getFilesDir()=File(baseContext.filesDir,"android-local-stt-public/$exercise").apply{mkdirs()}
        override fun getSharedPreferences(name:String,mode:Int)=baseContext.getSharedPreferences("public_android_stt_${exercise}_$name",mode)
        override fun getDatabasePath(name:String)=File(noBackupFilesDir,"databases/$name").apply{parentFile!!.mkdirs()}
    }
    private val owner="PUBLIC_ANDROID_STT_SANDBOX_"+exercise.replace('-','_')
    val scoped=AccountStorageContext(base,owner)
    private val settings=SecureSettings(scoped)
    private val session=GoogleConnectionSession(object:GoogleConnectionSession.Persistence {
        override fun read()=settings.snapshot().optJSONObject("publicPilotSession")?:JSONObject()
        override fun write(value:JSONObject){settings.update{it.put("publicPilotSession",JSONObject(value.toString()))}}
    })
    private val stop=AtomicBoolean(false)
    private val source=PublicSource(base)
    val engine=AndroidWhisperEngine(scoped)
    @Volatile private var activeRunner:AndroidWhisperRunner?=null
    private var clock=System.currentTimeMillis()
    init {
        // Explicitly fake authorization contract in this private sandbox; no Google SDK/token.
        val e=session.begin(session.view().ownerId.isNotBlank());session.activate(e,"public-stt@example.invalid","public-stt@example.invalid",owner,"PUBLIC_SYNTHETIC_NOT_A_GOOGLE_TOKEN")
    }
    private val scope get()=checkNotNull(DriveALocalScope.current(session))
    private class PublicSource(private val context:Context):SelectedRecordingSource {
        override val rootUri="content://ieum-public-android-stt/tree/bundled"
        override val explicitlySyntheticScope=true
        private val docs=listOf("public-ko.wav","public-en.wav").map{n->SourceDocument(n,"$rootUri/document/$n",n,context.assets.open("local-stt/$n").use{it.available().toLong()},"audio/wav",1790812800000L)}
        override fun checkSelected(){}
        override fun inventory()=SourceInventory(docs,docs.map{it.documentId}.toSet(),true)
        override fun stat(document:SourceDocument)=docs.single{it.documentId==document.documentId}
        override fun excluded(document:SourceDocument)=false
        override fun checkReadable(document:SourceDocument){check(stat(document)==document){"PUBLIC_AUDIO_METADATA_CHANGED"}}
        override fun open(document:SourceDocument)=context.assets.open("local-stt/"+document.documentId).also{checkReadable(document)}
    }
    private class MemoryNativeTransport:DriveTransport {
        val specs=linkedMapOf<String,DriveTransport.FileSpec>();val bytes=linkedMapOf<String,ByteArray>();var counter=0
        override fun generateIds(count:Int)=(0 until count).map{"public_android_native_"+(++counter)}
        override fun create(spec:DriveTransport.FileSpec,content:ByteArray):DriveTransport.Metadata {specs[spec.id]=spec;bytes[spec.id]=content.clone();return metadata(spec.id)}
        override fun metadata(id:String):DriveTransport.Metadata {val s=specs[id]?:throw DriveTransport.DriveException(DriveTransport.Failure.NOT_FOUND);return DriveTransport.Metadata(s.id,s.parentId,s.name,s.mimeType,s.ownerBinding,"1",false,true,true,bytes.getValue(id).size.toLong())}
        override fun download(id:String,max:Int)=DriveTransport.Download(bytes.getValue(id).clone())
    }
    private class NativePort(private val p:GoogleNativePipeline):RecordingAnalysisPort {
        override val requiresNativeBinding=true
        override fun submit(recording:String)=p.submitExisting(recording).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun submitBound(recording:String,observer:(LocalJobStore.Job)->Unit)=p.submitExisting(recording,observer).let{RecordingAnalysisPort.Sent(it.outcome.name,it.jobId)}
        override fun driveAExport(job:String)=p.driveAExport(job);override fun driveAFrozenRequest(job:String)=p.driveAFrozenRequest(job);override fun driveAReopenedReceipt(job:String)=p.driveAReopenedReceipt(job)
        override fun jobs()=p.jobs();override fun poll(job:String)=p.poll(job)
        override fun retry(job:String):RecordingAnalysisPort.Sent=error("PUBLIC_STT_NO_NEW_JOB_RETRY")
        override fun cancel(){};override fun close(){}
    }
    private fun prepare(classify:Boolean=true) {
        check(!stop.get()){"ANDROID_STT_STOPPED"};engine.prepare()
        settings.update{it.put("treeUri",source.rootUri)}
        GeneralLocalStore(scoped).use { s ->
            if(s.setupState()=="EMPTY")s.initializeEmpty()
            if(s.snapshot().length()==0)s.importSelected(source)
            val rows=s.snapshot()
            if(classify)for(i in 0 until rows.length()){val row=rows.getJSONObject(i);if(row.isNull("original_started_at_ms"))s.confirmTime(row.getString("file_id"),row,1790848800000L+i*60000)}
        }
        if(classify)RecordingWorkflowStore(scoped).use { s ->
            val rows=GeneralLocalStore.catalog(scoped).listFiles();var selected:String?=null
            for(i in 0 until rows.length()){val id=rows.getJSONObject(i).getString("file_id");val row=s.snapshot(id)
                selected=if(row.isNull("case_id"))s.confirmWork(id,row,"공개 합성 담당","공개 합성 회사","공개 음성 시험","",selected) else row.getString("case_id")
            }
        }
        for(d in source.inventory().documents)SyntheticApprovedSource.approve(settings,session,source,d,true)
    }
    /** User-initiated sandbox: first analyse without invented identity/time; then explicitly
     * bind the published fixture's labels/time and reuse the same STT in the existing accept path. */
    fun cycleUnclassified(reopenResults:Boolean=false):JSONObject {
        prepare(false);if(reopenResults)clock+=900001
        val before=engine.calls;val frozenScope=scope
        fun fence():String {val s=settings.snapshot();return scope.key+":"+AndroidAutomationRuntime.policyHash(s)+":"+s.optLong("dailyReportPolicyEpoch",0)+":"+DriveRelay.sha256(StrictJson.bytes(StrictJson.parse((s.optJSONObject("nativeSyntheticApprovals")?:JSONObject()).toString().toByteArray())))}
        val frozenAuthority=fence()
        val current={ !stop.get() && DriveALocalScope.current(session)==frozenScope && fence()==frozenAuthority }
        val transcriber=AndroidUnclassifiedTranscriber(engine)
        UnclassifiedAudioStore(scoped,source,transcriber,current,{AndroidAutomationRuntime.policyHash(settings.snapshot())},frozenAuthority).use { intake ->
            val rows=GeneralLocalStore.catalog(scoped).listFiles();val stages=JSONArray();var case:String?=null
            for(i in 0 until rows.length()) {
                val id=rows.getJSONObject(i).getString("file_id")
                val existing=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use { db ->
                    db.query("SELECT id FROM unclassified_audio_requests WHERE state='BOUND'").map{it["id"].toString()}.singleOrNull{intake.view(it).getString("sourceId")==id}
                }
                val request=existing ?: RecordingWorkflowStore(scoped).use{intake.prepare(id,it.snapshot(id))}
                if(existing==null) {
                    val unclassified=RecordingWorkflowStore(scoped).use{it.snapshot(id)}
                    check(listOf("person_id","task_id","case_id","original_started_at_ms").all{unclassified.isNull(it)}){"PUBLIC_UNCLASSIFIED_CONTEXT_ALREADY_BOUND"}
                    check(intake.analyse(request){stop.get()}=="RESULT_READY"){"PUBLIC_UNCLASSIFIED_ANALYSIS_HELD"}
                    stages.put(intake.view(request).put("contextBeforeBinding",JSONObject().put("personId",JSONObject.NULL).put("caseId",JSONObject.NULL).put("taskId",JSONObject.NULL).put("originalTime",JSONObject.NULL)))
                    GeneralLocalStore(scoped).use{s->s.confirmTime(id,RecordingWorkflowStore(scoped).use{it.snapshot(id)},1790848800000L+i*60000)}
                    RecordingWorkflowStore(scoped).use{s->case=s.confirmWork(id,s.snapshot(id),"공개 합성 담당","공개 합성 회사","공개 미분류 음성 시험","",case);intake.confirmBinding(request,s.snapshot(id),true)}
                } else stages.put(intake.view(request))
            }
            val transport=MemoryNativeTransport()
            GoogleNativePipeline(base,session,settings,{source},transport,postingCapability={true}).use { p ->
                AndroidAutomationStore(scoped,scope.owner,scope.epoch,current,{AndroidAutomationRuntime.policyHash(settings.snapshot())},{s->source.inventory().documents.any{it.uri==s.getString("actual_uri")}}, {"Etc/UTC"},{clock}).use { store ->
                    val provider=LocalRecordingAutomationProvider(store,session,intake.boundRunner(),LocalRecordingTrust(engine.runtimeSha,engine.modelSha,transcriber.publicAudio,current),sourceReady=intake::canRelease)
                    NativeAutomationRequests(base,session){NativePort(p)}.use { requests->AndroidAutomationCoordinator(store,requests,provider){stop.get()}.cycle() }
                    return report(store.jobs(),transport.counter,engine.calls-before).put("unclassifiedStages",stages).put("analysisBeforeBinding",true)
                }
            }
        }
    }
    /** Deliberately advances only the local poll clock for replay observation, never a lease. */
    fun cycle(reopenResults:Boolean=false):JSONObject {
        prepare();if(reopenResults)clock+=900001;val before=engine.calls
        fun fence():String {val s=settings.snapshot();return AndroidAutomationRuntime.policyHash(s)+":"+s.optLong("dailyReportPolicyEpoch",0)+":"+DriveRelay.sha256(StrictJson.bytes(StrictJson.parse((s.optJSONObject("nativeSyntheticApprovals")?:JSONObject()).toString().toByteArray())))}
        val frozenScope=scope;val frozenFence=fence()
        val current={ !stop.get()&&DriveALocalScope.current(session)==frozenScope&&fence()==frozenFence }
        val transport=MemoryNativeTransport()
        GoogleNativePipeline(base,session,settings,{source},transport,postingCapability={true}).use { p ->
            AndroidAutomationStore(scoped,scope.owner,scope.epoch,current,{AndroidAutomationRuntime.policyHash(settings.snapshot())},{s->source.inventory().documents.any{it.uri==s.getString("actual_uri")}},{"Etc/UTC"},{clock}).use { store ->
                AndroidWhisperRunner(scoped,engine,current,stop).use { runner ->
                    activeRunner=runner
                    val provider=LocalRecordingAutomationProvider(store,session,runner,LocalRecordingTrust(engine.runtimeSha,engine.modelSha,source.inventory().documents.map{engine.pin.getJSONObject("publicAudio").getString(it.name)}.toSet(),current))
                    NativeAutomationRequests(base,session){NativePort(p)}.use { requests ->AndroidAutomationCoordinator(store,requests,provider){stop.get()}.cycle() }
                    activeRunner=null
                    return report(store.jobs(),transport.counter,engine.calls-before)
                }
            }
        }
    }
    private fun report(jobs:List<JSONObject>,nativeIds:Int,calls:Int):JSONObject=AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use{db->
        fun rows(table:String)=db.query("SELECT * FROM $table").map{r->JSONObject().apply{r.forEach{(k,v)->put(k,v?:JSONObject.NULL)}}}
        val reports=rows("automation_cumulative_history");val result=rows("automation_results")
        val bindings=JSONArray();for(j in jobs){if(j.optString("handoff").isNotBlank()){val h=JSONObject(j.getString("handoff"));val req=JSONObject(h.getString("frozenRequestJson"));bindings.put(JSONObject().put("job",req.getString("jobId")).put("handoffSha",DriveRelay.sha256(j.getString("handoff").toByteArray())).put("requestSha",h.getString("frozenRequestSha256")).put("folderId",h.getString("folderId")).put("audioId",req.getString("audioFileId")).put("requestId",req.getString("requestFileId")).put("resultId",req.getString("resultFileId")).put("leaseEpoch",req.getLong("leaseEpoch")))}}
        JSONObject().put("scope","PUBLIC_SYNTHETIC_ANDROID_JNI_REAL_SQLITE_KEYSTORE_NATIVE_TRANSPORT_IN_MEMORY").put("androidProcessId",android.os.Process.myPid()).put("androidAPI",android.os.Build.VERSION.SDK_INT).put("androidABI",engine.abi).put("actualAndroidSTTCalls",calls)
            .put("runtimeSha",engine.runtimeSha).put("modelSha",engine.modelSha).put("modelBytes",32152673).put("historyCount",reports.size).put("taskCount",rows("workflow_items").size).put("history",JSONArray(reports)).put("results",JSONArray(result)).put("jobs",JSONArray(jobs)).put("bindings",bindings).put("newFakeNativeIDs",nativeIds)
            .put("realGMS",false).put("realDrive",false).put("privateAudio",false).put("microphone",false).put("paidAPI",false).put("fixtureTranscript",false).put("semanticHighReview",false)
    }
    private fun <T> integrated(block:(UnclassifiedAudioStore,IntegratedContextStore,()->Boolean)->T):T {
        prepare(false);val frozenScope=scope
        fun authority():String {val s=settings.snapshot();return scope.key+":"+AndroidAutomationRuntime.policyHash(s)+":"+s.optLong("dailyReportPolicyEpoch",0)+":"+DriveRelay.sha256(StrictJson.bytes(StrictJson.parse((s.optJSONObject("nativeSyntheticApprovals")?:JSONObject()).toString().toByteArray())))}
        val frozen=authority();val current={ !stop.get() && DriveALocalScope.current(session)==frozenScope && authority()==frozen }
        return UnclassifiedAudioStore(scoped,source,AndroidUnclassifiedTranscriber(engine),current,{AndroidAutomationRuntime.policyHash(settings.snapshot())},frozen).use{intake->
            IntegratedContextStore(scoped,intake,owner,current,settings::snapshot).use{context->block(intake,context,current)}
        }
    }
    private fun integrationRequests(intake:UnclassifiedAudioStore):List<String> =AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use{db->
        val rows=GeneralLocalStore.catalog(scoped).listFiles()
        (0 until rows.length()).map{i->val id=rows.getJSONObject(i).getString("file_id")
            db.query("SELECT u.id FROM unclassified_audio_requests u JOIN workflow_analysis_requests w ON w.id=u.id WHERE w.recording_id=? ORDER BY w.created_ms DESC",id).firstOrNull()?.get("id")?.toString()
                ?: RecordingWorkflowStore(scoped).use{intake.prepare(id,it.snapshot(id))}
        }
    }
    fun integratedAnalyse():JSONObject=integrated{intake,context,_->
        val before=engine.calls;val stages=JSONArray()
        for(r in integrationRequests(intake)){check(intake.analyse(r){stop.get()} in setOf("RESULT_READY","BOUND")){"INTEGRATION_ANALYSIS_HELD"};context.normalize(r);stages.put(intake.view(r))}
        JSONObject().put("phase","CANDIDATE_ONLY").put("actualAndroidSTTCalls",engine.calls-before).put("stages",stages).put("candidate",context.candidate()).put("userReviewCount",context.reviewCount())
    }
    fun integratedBind(confirmed:Boolean):JSONObject=integrated{intake,context,current->
        check(confirmed){"INTEGRATION_USER_RELATION_REVIEW_REQUIRED"};val requests=integrationRequests(intake);var selected:String?=null
        for((i,r) in requests.withIndex()) {
            val v=intake.view(r);val id=v.getString("sourceId")
            RecordingWorkflowStore(scoped).use{w->val old=w.snapshot(id)
                if(intake.status(r)!="BOUND") {
                    check(intake.status(r)=="RESULT_READY"){"INTEGRATION_ANALYSIS_REQUIRED"}
                    GeneralLocalStore(scoped).use{s->s.confirmTime(id,old,1790848800000L+i*60000)}
                    selected=w.confirmWork(id,w.snapshot(id),"공개 합성 담당","공개 합성 회사","공개 통합 시험 업무","",selected)
                    intake.confirmBinding(r,w.snapshot(id),true)
                } else selected=old.getString("case_id")
                val text=v.getString("transcript");val quote=v.getJSONObject("analysis").getString("summary");val start=text.codePointCount(0,text.indexOf(quote))
                context.confirmRelation(r,w.snapshot(id),start,start+quote.codePointCount(0,quote.length),true)
            };context.normalize(r)
        }
        val transport=MemoryNativeTransport()
        GoogleNativePipeline(base,session,settings,{source},transport,postingCapability={true}).use{p->AndroidAutomationStore(scoped,scope.owner,scope.epoch,current,{AndroidAutomationRuntime.policyHash(settings.snapshot())},{s->source.inventory().documents.any{it.uri==s.getString("actual_uri")}}, {"Etc/UTC"}).use{store->
            val runner=intake.boundRunner();val trust=LocalRecordingTrust(engine.runtimeSha,engine.modelSha,AndroidUnclassifiedTranscriber(engine).publicAudio,current)
            NativeAutomationRequests(base,session){NativePort(p)}.use{req->AndroidAutomationCoordinator(store,req,LocalRecordingAutomationProvider(store,session,runner,trust,sourceReady=intake::canRelease)).cycle()}
        }}
        JSONObject().put("phase","BOUND_PROPOSAL_NOT_USER_APPROVED").put("candidate",context.candidate()).put("userReviewCount",context.reviewCount())
    }
    suspend fun integratedReviewAndReport(candidateSha:String,confirmed:Boolean):JSONObject {
        var sourcePort:ReviewedDailySource?=null;var currentScope:(()->Boolean)?=null
        val approved=integrated{intake,context,current->
            check(confirmed){"INTEGRATION_USER_CONTENT_REVIEW_REQUIRED"};val requests=integrationRequests(intake)
            if(context.reviewCount()==0)context.approveSelection(requests.associateWith{r->val v=intake.view(r);RecordingWorkflowStore(scoped).use{it.snapshot(v.getString("sourceId"))} to v.getJSONObject("analysis").getString("summary")},candidateSha,true)
            else check(context.reviewCount()==requests.size){"INTEGRATION_PARTIAL_USER_REVIEW"}
            // The only filename-policy exception is this exact bundled-public sandbox; source
            // exclusion and canonical USER_REVIEW_SUMMARY receipt checks still run normally.
            val approved=requests.map{r->val v=intake.view(r);Triple(v.getString("sourceId"),v.getJSONObject("receipt").getJSONObject("source").getLong("source_version"),v.getString("audioSha"))}.toSet()
            sourcePort=CanonicalReviewedDailySource(scoped,{id,version,sha->current() && Triple(id,version,sha) in approved && sha in AndroidUnclassifiedTranscriber(engine).publicAudio},settings::snapshot)
            currentScope=current
            JSONObject().put("phase","EXPLICIT_USER_REVIEW_COMMITTED").put("reviewCount",context.reviewCount()).put("candidate",context.candidate()).put("androidProcessId",android.os.Process.myPid())
        }
        val current=checkNotNull(currentScope);val source=checkNotNull(sourcePort)
        val env=ReportEnvironment(java.time.Instant.parse("2026-10-01T21:00:00Z"),java.time.ZoneId.of("Asia/Seoul"),elapsed=1000,boot=1)
        DailyReportLedger(scoped,owner).use{ledger->if(!ledger.config().enabled)ledger.setEnabled(true,env.now,env.zone)
            MorningReportCoordinator(ledger.morning(current),CanonicalMorningSnapshotPort(source),LocalReviewedMorningSync(scoped,owner,current,source),MorningDryRun,{env},current).tick(ledger.config().generation)
            val repo=ledger.morning(current);val reports=repo.reports()
            approved.put("reports",JSONArray(reports.map{r->JSONObject().put("kind",r.window.kind.name).put("revision",r.revision).put("state",r.state).put("body",repo.body(r.key,r.revision))}))
                .put("outbox",JSONArray(repo.outbox().map{JSONObject().put("id",it.id).put("revision",it.revision).put("state",it.state.name)}))
                .put("syncState",repo.syncStatus()).put("durableCursor",repo.ingestCursor()).put("syncScope","LOCAL_REVIEWED_STORE_ONLY_NO_EXTERNAL_COLLECTION")
                .put("simulatedReportNow",env.now.toString()).put("actualAndroidSTTCalls",engine.calls).put("externalSends",0)
        }
        return approved
    }
    /** Public-only fixture roundtrip. Real JNI source packs; zero OAuth/HTTP/model calls. */
    fun siwcPublicCandidates():JSONObject=integrated{intake,_,current->
        val results=JSONArray();var fixtureCalls=0
        AndroidAutomationStore(scoped,scope.owner,scope.epoch,current,{AndroidAutomationRuntime.policyHash(settings.snapshot())},{s->source.inventory().documents.any{it.uri==s.getString("actual_uri")}}, {"Etc/UTC"}).use{store->
            SiwcNativeReviewStore(scoped,owner,intake,store,current,settings::snapshot).use{native->
                for(r in integrationRequests(intake)) {
                    val old=kr.co.callsummary.core.android.AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use{db->db.query("SELECT id FROM siwc_native_candidates WHERE request_id=?",r).singleOrNull()?.get("id")?.toString()}
                    if(old!=null){check(intake.available(r));val saved=native.candidate(old);if(saved.getBoolean("userApproved"))native.reviewedReceipt(old);results.put(saved);continue}
                    val ticket=native.prepare(r);val accountScope="PUBLIC_SYNTHETIC_SIWC_FIXTURE:"+scope.key
                    val port=SiwcPublicFixturePort(ticket.contract);val valid={current() && native.isCurrent(ticket)}
                    SiwcInferenceJobs(scoped,accountScope,port,valid).use{jobs->
                        val a=SiwcNativeRoundTrip.analysis(ticket,accountScope);val va=SiwcNativeRoundTrip.validateAnalysis(ticket)
                        check(jobs.run(a,va).state=="COMPLETE") { "SIWC_PUBLIC_ANALYSIS_NOT_COMPLETE" }
                        val c=SiwcNativeRoundTrip.context(ticket,jobs,a);val vc=SiwcNativeRoundTrip.validateContext(ticket,jobs,a)
                        check(jobs.run(c,vc).state=="COMPLETE") { "SIWC_PUBLIC_CONTEXT_NOT_COMPLETE" }
                        SiwcResultFiles(scoped,accountScope,jobs,valid).use{files->files.publish(kr.callagent.drive.StrictJson.bytes(ticket.contract.pack),a,c,va,vc)
                            results.put(native.receive(ticket,files,a,c,fixture=true))}
                    };fixtureCalls+=port.calls
                }
            }
        }
        JSONObject().put("phase","SIWC_FIXTURE_FILE_CANDIDATES_ONLY").put("candidates",results).put("fixtureCompletions",fixtureCalls)
            .put("actualAstraCalls",0).put("actualOAuthGrants",0).put("actualAndroidSTTCalls",engine.calls)
    }
    fun siwcPublicReview(digests:Map<String,String>,confirmed:Boolean):JSONObject=integrated{intake,_,current->
        check(confirmed && digests.isNotEmpty()){"SIWC_EXPLICIT_USER_REVIEW_REQUIRED"};val receipts=JSONArray()
        AndroidAutomationStore(scoped,scope.owner,scope.epoch,current,{AndroidAutomationRuntime.policyHash(settings.snapshot())},{s->source.inventory().documents.any{it.uri==s.getString("actual_uri")}}, {"Etc/UTC"}).use{store->
            SiwcNativeReviewStore(scoped,owner,intake,store,current,settings::snapshot).use{native->
                val saved=digests.map{(id,digest)->check(native.candidate(id).getString("digest")==digest){"SIWC_REVIEW_CHANGED"};native.reviewedReceipt(id)}
                if(saved.all{it!=null})return@integrated JSONObject().put("phase","EXISTING_USER_SIWC_FIXTURE_REVIEW_REOPENED").put("receipts",JSONArray(saved))
                val selection=digests.map { (id,digest) ->
                    val row=kr.co.callsummary.core.android.AndroidSqlPort(scoped,GeneralLocalStore.DATABASE,scoped.assets.open("call_core_schema.sql")).use{db->db.query("SELECT request_id FROM siwc_native_candidates WHERE id=?",id).single()}
                    native.prepare(row["request_id"].toString()) to (id to digest)
                }.toMap()
                native.approveSelection(selection,true).forEach{receipts.put(it)}
            }
        }
        JSONObject().put("phase","EXPLICIT_USER_SIWC_FIXTURE_REVIEW").put("receipts",receipts).put("actualAstraCalls",0).put("actualOAuthGrants",0)
    }
    fun cancel(){stop.set(true);activeRunner?.cancel();engine.cancel()}
    override fun close(){activeRunner=null}
}
