package kr.callbrief

import android.content.Context
import kr.callagent.drive.AuthorizationPort
import kr.co.callsummary.core.android.ProtectedDatabase
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Adapter to 01a135d6's authoritative port. No HTTP, enrollment journal, parser or
 * native receipt implementation is duplicated here. Configuration/gate are supplied by that owner. */
internal class AutomationProcessingProvider(private val store:AndroidAutomationStore,
    private val processing:AutomationProcessingPort,private val availability:()->AutomationGate):AutomationProvider {
    override fun gate()=availability()
    private fun request(handoff:String)=JSONObject(handoff).getString("localRequestId")
    private fun accepted(outcome:AutomationProcessingPort.Outcome,handoff:String):AutomationDispatch {
        val job=JSONObject(JSONObject(handoff).getString("frozenRequestJson")).getString("jobId")
        check(outcome.job==null || outcome.job==job) {"AUTOMATION_PROCESSING_JOB_CHANGED"}
        return when(outcome.state) {
            "REGISTERED","PROCESSOR_PENDING","PROVENANCE_PENDING","DUPLICATE_PROPOSAL","READ_ONLY_CANDIDATE" -> {
                check(outcome.job==job) {"AUTOMATION_ACCEPTANCE_RECEIPT_MISSING"}
                AutomationDispatch("ACCEPTED","PROCESSING_PORT_"+job,"AUTHENTICATED_PROCESSING_PORT_ACCEPTED")
            }
            "PROCESSOR_NOT_CONFIGURED" -> AutomationDispatch("WAITING",reason="ANALYSIS_PROVIDER_NOT_CONFIGURED")
            else -> AutomationDispatch("UNKNOWN",reason=outcome.state)
        }
    }
    override fun dispatch(key:String,frozenHandoff:String):AutomationDispatch {
        check(gate().state=="READY") {"ANALYSIS_PROVIDER_APPROVAL_REQUIRED"}
        return accepted(processing.enrollPublished(request(frozenHandoff),store.frozenBusinessContext(key)),frozenHandoff)
    }
    override fun reconcile(key:String,frozenHandoff:String)=accepted(processing.pollPublished(request(frozenHandoff)),frozenHandoff)
    override fun receive(key:String,receipt:String,frozenHandoff:String):AutomationObservation {
        val outcome=processing.pollPublished(request(frozenHandoff))
        val expectedJob=JSONObject(JSONObject(frozenHandoff).getString("frozenRequestJson")).getString("jobId")
        check(receipt=="PROCESSING_PORT_"+expectedJob && (outcome.job==null || outcome.job==expectedJob)) {"AUTOMATION_PROCESSING_RECEIPT_CHANGED"}
        val observation=outcome.observation
        if(observation==null)return when(outcome.state) {
            "PROCESSOR_PENDING","RESULT_READ_UNCERTAIN","PROCESSOR_NOT_CONFIGURED" -> AutomationObservation.Waiting(outcome.state)
            else -> AutomationObservation.Failed(outcome.state)
        }
        if(observation.disposition !in setOf("PROVENANCE_PENDING","DUPLICATE_PROPOSAL","READ_ONLY_CANDIDATE"))
            return AutomationObservation.Failed(observation.disposition)
        return store.readNativeObservation(key,observation)
    }
}

internal class NativeAutomationRequests(private val context:Context,private val session:GoogleConnectionSession,
    private val factory:()->RecordingAnalysisPort={NativeRecordingAnalysisPort(context,session)}):AutomationRequests {
    private val flow=AnalysisRequestFlow(context,session,factory)
    override fun prepare(recording:String,snapshot:JSONObject)=flow.prepare(recording,snapshot)
    override fun publish(request:String)=flow.submit(request)
    override fun state(request:String)=flow.requestState(request)
    override fun handoff(request:String):String {
        val port=ExistingDriveARequestPort(context,session,factory)
        val scope=checkNotNull(port.scope()) { "AUTOMATION_AUTH_REQUIRED" }
        return DriveACandidateContract.build(request,scope,port.snapshot(request))
    }
    override fun close()=flow.close()
}

/** One explicitly supplied provider factory, no empty native callback and no model credentials
 * in SecureSettings/WorkManager input. The default is observable UNCONFIGURED, with zero transfer. */
internal object AutomationProviderRegistry {
    @Volatile var create:((Context,GoogleConnectionSession,AndroidAutomationStore)->AutomationProvider)?=null
    fun open(context:Context,session:GoogleConnectionSession,store:AndroidAutomationStore):AutomationProvider {
        create?.let{return it(context,session,store)}
        val base=AutomationConnectionBindings.base(context)
        if(AutomationConnectionBindings.processing(base,session)==null)return UnconfiguredAutomationProvider
        val processing=ProductionAutomationProcessingPort(base,session,{AutomationConnectionBindings.processing(base,session)})
        return AutomationProcessingProvider(store,processing){
            if(AutomationConnectionBindings.processing(base,session)==null)AutomationGate("UNAPPROVED","ANALYSIS_PROVIDER_BINDING_WITHDRAWN")
            else AutomationGate("READY","APPROVED_OWNER_PROCESSING_CONNECTION")
        }
    }
}

internal object AndroidAutomationRuntime {
    fun policyHash(state:JSONObject):String {
        val p=JSONObject()
        listOf("treeUri","personalPhoneDisabled","personalAliasDisabled","exclusions","identities","aliasMappings","pendingRevocation").forEach {
            if(state.has(it))p.put(it,state.get(it))
        }
        return AndroidAutomationStore.hash(kr.callagent.drive.StrictJson.`object`(kr.callagent.drive.StrictJson.parse(p.toString().toByteArray())))
    }
    /** Existing exact synthetic permission only. General personal source collection remains held.
     * This performs metadata/grant/exclusion checks, never audio open or a new approval. */
    fun sourceAllowed(context:Context,session:GoogleConnectionSession,s:JSONObject):Boolean=runCatching {
        check(session.state()==AuthorizationPort.State.ACTIVE)
        val settings=SecureSettings(context);val state=settings.snapshot()
        check(!state.optBoolean("pendingRevocation") && state.optString("treeUri")==s.getString("root_uri"))
        val d=SourceDocument(s.getString("actual_document_id"),s.getString("actual_uri"),s.getString("display_name"),s.getLong("byte_count"),s.getString("mime_type"),s.getLong("modified_at_ms"))
        val source=SafSelectedRecordingSource(context,settings,s.getString("root_uri"))
        SyntheticApprovedSource(source,d,session,settings).checkReadable(d)
        true
    }.getOrDefault(false)
    fun reconcile(context:Context,cancelled:()->Boolean={false})=run(context,false,cancelled)
    fun reconcileExistingResults(context:Context,cancelled:()->Boolean={false})=run(context,true,cancelled)
    /** Discovery retains the scope captured before quiet recovery, through scheduled and native I/O. */
    fun reconcileDiscovery(context:Context,owner:String,epoch:Long,nativeScope:String,cancelled:()->Boolean={false}):JSONObject {
        val session=GoogleConnectionSession.get(context)
        fun current()=DriveALocalScope.current(session)?.let{it.owner==owner && it.epoch==epoch && it.key==nativeScope}==true
        if(cancelled())return AutomaticRecordingDiscovery.outcome("LOCAL_WORK_STOPPED")
        if(!current())return AutomaticRecordingDiscovery.outcome("STALE_NATIVE_SCOPE")
        return run(AutomationConnectionBindings.base(context),false,{cancelled() || !current()},::current)
    }
    private fun run(context:Context,existingResultsOnly:Boolean,cancelled:()->Boolean,expectedScope:(()->Boolean)?=null):JSONObject {
        fun blocked():JSONObject? {
            if(expectedScope?.invoke()==false)return AutomaticRecordingDiscovery.outcome("STALE_NATIVE_SCOPE")
            if(cancelled())return AutomaticRecordingDiscovery.outcome("LOCAL_WORK_STOPPED")
            return null
        }
        blocked()?.let{return it}
        val scheduled=if(existingResultsOnly)JSONObject().put("state","SKIPPED_EXISTING_RESULT_WORK") else runCatching{ScheduledDotProofRuntime.reconcile(context,cancelled)}.getOrElse{JSONObject().put("state","HELD").put("reason","SCHEDULED_TEXT_CONFIG_REVIEW")}
        blocked()?.let{return it}
        val session=GoogleConnectionSession.get(context);val view=session.dailyReportScope()
        val scoped=if(view.ownerId.isBlank())context else AccountStorageContext(context,view.ownerId)
        if(session.state()!=AuthorizationPort.State.ACTIVE)
            return JSONObject().put("state","WAITING_AUTH").put("reason","GOOGLE_DRIVE_NATIVE_AUTH_REQUIRED").put("analysisCompleted",false).put("kakaoSent",false).put("scheduledTextProof",scheduled)
        // A worker never initializes or repairs a missing key/audio store.
        if(!ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE).exists())
            return JSONObject().put("state","WAITING_LOCAL_SOURCE").put("reason","AUTHORIZED_ENCRYPTED_SOURCE_NOT_AVAILABLE")
        return try {
            val settings=SecureSettings(scoped)
            var zone:String?=null
            val reportScope=DailyReportRuntime.scope(context)
            DailyReportLedger(reportScope.context,reportScope.profile).use { l ->
                val c=l.config();if(c.enabled && c.zone.isNotBlank())zone=ZoneId.of(c.zone).id
            }
            val current={ val v=session.dailyReportScope();!cancelled() && (expectedScope?.invoke()!=false) && v.ownerId==view.ownerId && v.epoch==view.epoch }
            AndroidAutomationStore(scoped,view.ownerId,view.epoch,current,{policyHash(settings.snapshot())},
                {sourceAllowed(scoped,session,it)},{zone}).use { store ->
                NativeAutomationRequests(context,session).use { requests ->
                    val jobs=AndroidAutomationCoordinator(store,requests,AutomationProviderRegistry.open(scoped,session,store),cancelled).cycle(receiveExistingOnly=existingResultsOnly)
                    if(!cancelled()) {
                        bridgeReportQueue(context,store)
                        if(!existingResultsOnly)AutomaticResultReconciliation.ensure(context,session,jobs)
                    }
                    blocked() ?: JSONObject().put("state",if(jobs.isEmpty())"WAITING_AUTHORIZED_SOURCE" else jobs.last().getString("state"))
                        .put("reason",if(jobs.isEmpty())"NO_ADMITTED_SOURCE" else jobs.last().getString("reason"))
                        .put("pendingExistingResults",store.pendingResults().size).put("jobs",jobs.size).put("analysisCompleted",jobs.isNotEmpty() && jobs.all{it.getString("state")=="APPLIED_SAFE_DERIVED" && it.getString("reason")=="VERIFIED_SAFE_DERIVED_APPLIED"})
                        .put("kakaoSent",false).put("scheduledTextProof",scheduled)
                }
            }
        } catch(e:Exception) {
            blocked()?.let{return it}
            val code=e.message?.takeIf{it.matches(Regex("[A-Z0-9_]{1,100}"))} ?: "AUTOMATION_${e.javaClass.simpleName.uppercase()}"
            JSONObject().put("state","FAILED").put("reason",code).put("analysisCompleted",false).put("kakaoSent",false).put("scheduledTextProof",scheduled)
        }
    }
    /** Link to the existing report slot, retaining automatic provenance in the separate input
     * queue. The report owner consumes reportBatch; do not call freeze(...,privacyConfirmed=true)
     * or manufacture a manual review to satisfy its older reviewed-only sender contract. */
    fun bridgeReportQueue(context:Context,store:AndroidAutomationStore) {
        val scope=DailyReportRuntime.scope(context)
        DailyReportLedger(scope.context,scope.profile).use { ledger ->
            val cfg=ledger.config();if(!cfg.enabled || !DailyReportRuntime.current(context,scope))return
            for(q in store.reportQueue()) {
                if(q.getString("day").isBlank() || q.getString("state") in setOf("HELD_SOURCE","SUPERSEDED_BY_RESULT"))continue
                val window=ReportWindow(LocalDate.parse(q.getString("day")),ZoneId.of(q.getString("zone")))
                ledger.allocate(window,cfg.generation)?.let { store.linkReport(q.getString("id"),it.id) }
            }
        }
        DailyReportSchedule.ensure(context)
    }
}

/** Extends the existing data projection, with no additional screen or tab. */
internal object AutomationCatalogProjection {
    fun add(context:Context,db:AndroidSqlPort,item:JSONObject,row:Map<String,Any?>) {
        if(db.query("SELECT name FROM sqlite_master WHERE name='automation_jobs'").isEmpty())return
        val j=db.query("SELECT * FROM automation_jobs WHERE recording_id=? AND source_version=? ORDER BY epoch DESC,updated_ms DESC LIMIT 1",row["recording_id"],row["source_version"]).singleOrNull() ?: return
        item.put("automation_state",j["state"]).put("automation_reason",j["reason"])
            .put("automation_status_label",AutomationStatusText.label(j["state"].toString(),j["reason"].toString()))
        if(item.optBoolean("archived"))return
        if(j["state"]!="APPLIED_SAFE_DERIVED")return
        val session=GoogleConnectionSession.get(context)
        if(session.ownerBinding()!=j["owner"] || session.epoch()!=(j["epoch"] as Number).toLong() || session.state()!=AuthorizationPort.State.ACTIVE)return
        if(AndroidAutomationRuntime.policyHash(SecureSettings(context).snapshot())!=j["policy_hash"])return
        val base=JSONObject(j["snapshot_json"].toString())
        if(listOf("user_revision","lock_revision","head_revision").any{(row[it] as? Number)?.toLong()!=base.optLong(it)})return
        val native=db.query("SELECT r.*,l.root_uri,l.actual_uri,l.actual_document_id,t.task_revision,t.manual_locks AS task_manual_locks,t.status AS task_status,g.active AS grant_active FROM recordings r JOIN local_files l ON l.recording_id=r.recording_id JOIN folder_grants g ON g.grant_id=r.grant_id LEFT JOIN tasks t ON t.task_id=r.task_id WHERE r.recording_id=?",row["recording_id"]).singleOrNull() ?: return
        if(native["grant_active"]!=1L || (native["task_manual_locks"] as? Number)?.toLong()!=0L || native["task_status"]=="done" || (native["task_revision"] as? Number)?.toLong()!=base.optLong("task_revision"))return
        if(!AndroidAutomationRuntime.sourceAllowed(context,session,JSONObject(native)))return
        if(!item.isNull("manual_note") || item.optString("manual_status")=="done" || item.has("review_receipt") || j["state"]!="APPLIED_SAFE_DERIVED")return
        val h=db.query("SELECT h.summary,h.cumulative_text FROM automation_cumulative_heads c JOIN automation_cumulative_history h ON h.id=c.result_id WHERE c.recording_id=?",row["recording_id"]).singleOrNull() ?: return
        item.put("automation_cumulative_summary",h["cumulative_text"]).put("summary",h["summary"])
            .put("summary_origin","AUTOMATIC_DERIVED_VERIFIED_POLICY_NOT_USER_REVIEW")
    }
}

internal object AutomationStatusText {
    fun label(state:String,reason:String)=when(state) {
        "WAITING_PROVIDER"->"분석 실행자 미설정 · 자동 분석 대기"
        "WAITING_APPROVAL"->"분석 실행 권한·비용 승인 대기"
        "WAITING_NATIVE"->"원음·계정 연결 확인 필요 · $reason"
        "WAITING_CONTEXT"->"기존 업무·통화 시각 연결 확인 필요"
        "HELD_SOURCE"->"제외·철회 또는 원본 변경으로 자동 처리 보류"
        "UNKNOWN","UNKNOWN_NATIVE","DISPATCHING"->"분석 접수 여부 확인 필요 · 중복 전달 보류"
        "FAILED"->"자동 분석 실패 · $reason"
        "WAITING_RESULT"->"분석 실행자 접수됨 · 결과 대기"
        "REVIEW_REQUIRED"->"분석 제안 보관 · 근거 또는 직접 수정 충돌 확인 필요"
        "APPLIED_SAFE_DERIVED"->if(reason=="SAFE_SUMMARY_APPLIED_SOME_TASKS_REQUIRE_REVIEW")"누적 요약 자동 반영 · 일부 할일 검토 필요" else "검증 정책에 따라 누적 반영됨 · 로컬 보고에서 확인"
        else->"허용된 원음 접수 · 자동 분석 작업 대기"
    }
}
