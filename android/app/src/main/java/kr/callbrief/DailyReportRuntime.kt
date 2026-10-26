package kr.callbrief

import android.content.Context
import kr.co.callsummary.core.android.ProtectedDatabase
import java.io.File
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kr.callbrief.kakao.android.KakaoConfiguration
import kr.callbrief.kakao.android.KakaoReportClient
import kr.callbrief.kakao.core.DailyReport
import kr.callbrief.kakao.core.DeliveryCoordinator
import kr.callbrief.kakao.core.DeliveryResult
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal fun interface ReviewedDailySource { fun snapshot(window:ReportWindow):DailyReportInput }
internal interface DailyReportSender {
    fun gate():DeliveryResult?
    suspend fun send(report:DailyReport):DeliveryResult
}

/** Reads canonical app rows and explicit ACCEPT receipts; never worker proposal text or audio. */
internal class CanonicalReviewedDailySource(private val context:Context,
    private val policy:()->JSONObject={ SecureSettings(context).snapshot() }) : ReviewedDailySource {
    override fun snapshot(window:ReportWindow):DailyReportInput {
        val state=policy()
        val policyHash=DeliveryCoordinator.sha256(listOf(state.optBoolean("personalPhoneDisabled"),state.optBoolean("personalAliasDisabled"),
            state.optJSONArray("exclusions")?.toString().orEmpty(),state.optJSONObject("identities")?.toString().orEmpty(),
            state.optJSONObject("aliasMappings")?.toString().orEmpty(),state.optString("treeUri"),state.optBoolean("pendingRevocation"),
            state.optLong("dailyReportPolicyEpoch",0)).joinToString("\n"))
        if(state.optBoolean("pendingRevocation"))return DailyReportInput(ReportReadiness.WAITING_REVIEW,emptyList(),0,1,policyHash,
            listOf(ReportPendingSource("inventory",0,"",0,0,0,"INVENTORY_UNAVAILABLE","POLICY_REVIEW",false)))
        if(ProtectedDatabase.legacyFiles(context,GeneralLocalStore.DATABASE).any { it.exists() })
            error("TEXT_STORE_LEGACY_REVIEW_REQUIRED")
        val file=ProtectedDatabase.file(context,GeneralLocalStore.DATABASE)
        if(listOf(file,File(file.path+".bak"),File(file.path+".new")).none {it.exists()})
            return DailyReportInput(ReportReadiness.NO_RECORDINGS,emptyList(),0,0,policyHash)
        val store=ProtectedDatabase.open(context,GeneralLocalStore.DATABASE,null)
        return try {
            val db=store.database()
            ProtectedDatabase.read(db) {
                ProtectedDatabase.requireSealedPersistence(db)
                var pending=0;var review=0;var expected=0;val sources=mutableListOf<ReviewedReportSource>()
                val unresolved=mutableListOf<ReportPendingSource>()
                val hasProposals=db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='google_native_proposal_heads'",null).use { it.moveToFirst() }
                val rows=db.rawQuery("""SELECT r.recording_id,r.source_version,r.effective_summary_version,r.original_started_at_ms,
                    r.display_name,r.byte_count,r.mime_type,r.modified_at_ms,r.presence,r.head_revision,r.user_revision,r.lock_revision,
                    r.person_id,r.company_id,r.manual_status,(r.manual_text IS NOT NULL) AS manual_text_present,
                    v.audio_sha256,g.active AS grant_active,l.actual_uri,a.archived,p.state AS processing_state,
                    r.task_id AS expected_task_id,t.task_revision,t.status AS task_status,t.case_id,b.revision AS board_revision,
                    EXISTS(SELECT 1 FROM canonical_storage cs WHERE cs.audio_sha256=v.audio_sha256 AND cs.available=1) AS stored_copy
                    FROM recordings r LEFT JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version
                    LEFT JOIN folder_grants g ON g.grant_id=r.grant_id LEFT JOIN local_files l ON l.recording_id=r.recording_id
                    LEFT JOIN processing p ON p.recording_id=r.recording_id AND p.source_version=r.source_version
                    LEFT JOIN board_state b ON b.owner_key=r.owner_key LEFT JOIN tasks t ON t.task_id=r.task_id
                    LEFT JOIN local_visibility a ON a.recording_id=r.recording_id WHERE r.owner_key=?
                    AND (r.original_started_at_ms IS NULL OR (r.original_started_at_ms>=? AND r.original_started_at_ms<?))
                    ORDER BY r.recording_id""".trimIndent(),arrayOf(GeneralLocalStore.OWNER,window.from.toEpochMilli().toString(),window.until.toEpochMilli().toString()))
                rows.use { c ->while(c.moveToNext()) {
                    fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k)).orEmpty()
                    fun n(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
                    fun missing(k:String)=c.isNull(c.getColumnIndexOrThrow(k))
                    // Only a known revocation/archive can exclude a row; missing relations remain inventory.
                    if(!missing("archived") && n("archived")==1L || !missing("grant_active") && n("grant_active")==0L)continue
                    val at=if(c.isNull(c.getColumnIndexOrThrow("original_started_at_ms")))null else Instant.ofEpochMilli(n("original_started_at_ms"))
                    val ref=RecordingRef(Uri.parse(s("actual_uri")),s("display_name"),n("byte_count"),s("mime_type"),at,n("modified_at_ms"))
                    if(LocalPrivacy.blocked(ref,state))continue // Exclude before reading summary content.
                    expected++
                    var heldIndex=-1;var heldAsPending=false
                    fun hold(reason:String,analysisPending:Boolean=false,requestId:String="",digest:String="") {
                        if(heldIndex<0) {
                            if(analysisPending)pending++ else review++
                            heldIndex=unresolved.size;heldAsPending=analysisPending
                            unresolved+=ReportPendingSource(s("recording_id"),n("source_version"),s("audio_sha256"),n("head_revision"),n("user_revision"),n("lock_revision"),reason,s("processing_state"),at!=null,requestId,digest)
                        } else {
                            val old=unresolved[heldIndex]
                            if(analysisPending && !heldAsPending) { review--;pending++ }
                            unresolved[heldIndex]=old.copy(reason=if(analysisPending && !heldAsPending)reason else old.reason,
                                requestId=requestId.ifBlank { old.requestId },resultDigest=digest.ifBlank { old.resultDigest })
                            heldAsPending=heldAsPending || analysisPending
                        }
                    }
                    if(listOf("audio_sha256","grant_active","actual_uri","processing_state","board_revision","archived").any(::missing) ||
                        s("actual_uri").isBlank() || (!missing("expected_task_id") && missing("task_revision"))) { hold("NATIVE_BINDING_MISSING",true);continue }
                    if(at==null || LocalPrivacy.collectionDecision(ref,state)!=LocalCollectionDecision.ALLOW || s("person_id").isBlank() || s("expected_task_id").isBlank() || s("case_id").isBlank()) { hold("IDENTITY_TIME_UNCONFIRMED");continue }
                    val id=s("recording_id");val source=n("source_version");val audio=s("audio_sha256")
                    val latestRequest=CurrentAnalysisResult.latestRequest(db,id,source,audio)
                    val latestDigest=latestRequest?.let { request->db.rawQuery("SELECT result_digest FROM workflow_analysis_result_cursor WHERE request_id=?",arrayOf(request.getString("id"))).use { x->if(x.moveToFirst())x.getString(0) else "" } }.orEmpty()
                    val received=CurrentAnalysisResult.read(context,db,id,source,audio,latestRequest)
                    fun holdAcceptedResult(reviewedDigest:String) {
                        if(received==null)return
                        val digest=received.optString("digest");val requestId=latestRequest!!.getString("id")
                        when(received.getString("state")) {
                            "ERROR"->hold("CURRENT_REQUEST_FAILED",true,requestId,digest)
                            "PENDING","CANCELLED"->hold("CURRENT_REQUEST_PENDING",true,requestId,digest)
                            "PROPOSAL"->if(digest.isBlank() || digest!=reviewedDigest)hold("RESULT_REVIEW_PENDING",false,requestId,digest)
                            else->hold("RESULT_REVIEW_PENDING",false,requestId,digest)
                        }
                    }
                    val summary=if(c.isNull(c.getColumnIndexOrThrow("effective_summary_version")))0 else n("effective_summary_version")
                    val ownReview=if(db.rawQuery("SELECT name FROM sqlite_master WHERE name='workflow_reviewed_summaries'",null).use { it.moveToFirst() })
                        db.rawQuery("SELECT s.* FROM workflow_reviewed_summaries s JOIN workflow_user_receipts e ON e.event_id=s.receipt AND e.recording_id=s.recording_id AND e.operation='USER_REVIEW_SUMMARY' WHERE s.recording_id=? AND s.source_version=? AND s.audio_sha=? ORDER BY s.revision DESC LIMIT 1",arrayOf(id,source.toString(),audio)).use { own ->
                            if(!own.moveToFirst() || (s("presence")!="PRESENT"&&n("stored_copy")!=1L) || listOf("user_revision","lock_revision","head_revision").any { own.getLong(own.getColumnIndexOrThrow(it))!=n(it) } ||
                                s("person_id").isBlank() || s("expected_task_id").isBlank() || s("case_id").isBlank())null
                            else {
                                val parts=mutableListOf("직접 확인 요약\n"+own.getString(own.getColumnIndexOrThrow("text")))
                                db.rawQuery("SELECT kind,title,status,edition,due_text FROM workflow_items WHERE recording_id=? ORDER BY created_ms,id",arrayOf(id)).use { item ->while(item.moveToNext()) {
                                    val kind=if(item.getString(0)=="DOCUMENT")"필요 서류" else "todo"
                                    parts+="$kind: ${item.getString(1)} · 직접 저장 상태 ${item.getString(2)}"+
                                        item.getString(3).takeIf { it.isNotBlank() }?.let { " · 판 $it" }.orEmpty()+
                                        item.getString(4).takeIf { it.isNotBlank() }?.let { " · 직접 입력 기한 $it" }.orEmpty()
                                } }
                                db.rawQuery("SELECT s.kind,count(*) FROM workflow_sources s JOIN workflow_source_links l ON l.source_key=s.source_key WHERE l.recording_id=? GROUP BY s.kind ORDER BY s.kind",arrayOf(id)).use { related ->while(related.moveToNext())parts+="사용자가 연결한 ${related.getString(0)} 근거 ${related.getInt(1)}개 · 자동 의미 확정 없음" }
                                val text=parts.joinToString("\n\n")
                                ReviewedReportSource(id,audio,source,own.getLong(own.getColumnIndexOrThrow("revision")),kr.callbrief.kakao.core.DeliveryCoordinator.sha256(text),own.getString(own.getColumnIndexOrThrow("receipt")),
                                    n("head_revision"),n("user_revision"),n("lock_revision"),text,n("task_revision"),s("task_status"),s("manual_status"),n("board_revision"),s("person_id"),s("company_id"),s("case_id"),"USER_REVIEWED_LOCAL_NOT_MODEL_ATTESTATION")
                            }
                        } else null
                    if(ownReview!=null) {
                        if(runCatching { ownReview.validate() }.isSuccess)sources+=ownReview else hold("RESULT_REVIEW_PENDING")
                        val reviewedDigest=db.rawQuery("SELECT proposal_digest FROM workflow_reviewed_summaries WHERE recording_id=? AND revision=?",arrayOf(id,ownReview.summaryVersion.toString())).use { x->if(x.moveToFirst())x.getString(0).orEmpty() else "" }
                        if(s("processing_state")=="FAILED")hold("ANALYSIS_FAILED",true)
                        holdAcceptedResult(reviewedDigest)
                        when(latestRequest?.optString("state")) {
                            "ERROR"->hold("CURRENT_REQUEST_FAILED",true,latestRequest.getString("id"),latestDigest)
                            "UNKNOWN"->hold("CURRENT_REQUEST_UNKNOWN",true,latestRequest.getString("id"),latestDigest)
                            "REVIEW"->if(latestDigest.isBlank() || latestDigest!=reviewedDigest)hold("RESULT_REVIEW_PENDING",false,latestRequest.getString("id"),latestDigest)
                            null->Unit
                            else->hold("CURRENT_REQUEST_PENDING",true,latestRequest!!.getString("id"),latestDigest)
                        };continue
                    }
                    if(received?.optString("state")=="ERROR")holdAcceptedResult("")
                    if(s("processing_state") in listOf("PENDING","TRANSCRIBING","FAILED","WAITING_SOURCE")) {
                        val proposal=summary==0L && hasProposals && db.rawQuery("SELECT 1 FROM google_native_proposal_heads WHERE recording_id=?",arrayOf(id)).use { it.moveToFirst() }
                        if(proposal)hold("RESULT_REVIEW_PENDING") else hold(if(s("processing_state")=="FAILED" || latestRequest?.optString("state")=="ERROR")"ANALYSIS_FAILED" else "ANALYSIS_PENDING",true);continue
                    }
                    if(s("processing_state") in listOf("HELD_UNKNOWN_TIME","HELD_UNLINKED","RETIRED")) { hold("IDENTITY_TIME_UNCONFIRMED");continue }
                    if(s("processing_state")!="REVIEW") { hold("RESULT_REVIEW_PENDING");continue }
                    if(summary==0L) {
                        val proposal=hasProposals && db.rawQuery("SELECT 1 FROM google_native_proposal_heads WHERE recording_id=?",arrayOf(id)).use { it.moveToFirst() }
                        if(proposal)hold("RESULT_REVIEW_PENDING") else hold("ANALYSIS_PENDING",true);continue
                    }
                    if(s("presence")!="PRESENT" && n("stored_copy")!=1L) { hold("SOURCE_UNAVAILABLE",true);continue }
                    db.rawQuery("""SELECT s.summary_text,s.summary_sha256,
                        (SELECT event_id FROM receipts r WHERE r.operation='ACCEPT' AND r.recording_id=s.recording_id
                         AND r.summary_version=s.summary_version AND r.outcome IN ('SUMMARY_ACCEPTED_BY_USER','HISTORICAL_SUMMARY_RESTORED')
                         ORDER BY created_at_ms DESC LIMIT 1) AS receipt
                        FROM summary_versions s WHERE s.recording_id=? AND s.summary_version=? AND s.source_version=? AND s.audio_sha256=?""".trimIndent(),
                        arrayOf(id,summary.toString(),source.toString(),audio)).use { result ->
                        if(!result.moveToFirst() || result.isNull(2)) { hold("RESULT_REVIEW_PENDING");return@use }
                        // A subsequent manual edit needs a new explicit canonical review, rather than a stale generated report.
                        if(n("manual_text_present")==1L) { hold("MANUAL_REVIEW_REQUIRED");return@use }
                        val r=ReviewedReportSource(id,audio,source,summary,result.getString(1),result.getString(2),
                            n("head_revision"),n("user_revision"),n("lock_revision"),result.getString(0),n("task_revision"),
                            s("task_status"),s("manual_status"),n("board_revision"),s("person_id"),s("company_id"),s("case_id"))
                        if(runCatching { r.validate() }.isSuccess)sources+=r else hold("RESULT_REVIEW_PENDING")
                        holdAcceptedResult("") // A local core ACCEPT does not review an independent inbound request.
                        if(latestRequest!=null && latestRequest.optString("state")!="REVIEW")hold(if(latestRequest.optString("state")=="ERROR")"CURRENT_REQUEST_FAILED" else "CURRENT_REQUEST_PENDING",true,latestRequest.getString("id"),latestDigest)
                    }
                } }
                val readiness=when { pending>0 -> ReportReadiness.WAITING_ANALYSIS;review>0 -> ReportReadiness.WAITING_REVIEW
                    expected==0 -> ReportReadiness.NO_RECORDINGS;else -> ReportReadiness.REVIEWED }
                DailyReportInput(readiness,sources,expected,pending+review,policyHash,unresolved)
            }
        } finally { store.close() }
    }
}

/** Automatic Kakao delivery is policy-held, including builds with configured live flags.
 * The separately owned user-initiated sendFinalized interface remains available in the module. */
internal class KakaoDailyReportSender(@Suppress("UNUSED_PARAMETER") context:Context):DailyReportSender {
    override fun gate()=DeliveryResult(DeliveryResult.Status.DISABLED)
    override suspend fun send(report:DailyReport)=DeliveryResult(DeliveryResult.Status.DISABLED)
}

internal class DailyReportCoordinator(private val ledger:DailyReportLedger,private val source:ReviewedDailySource,
    private val sender:DailyReportSender,private val environment:()->ReportEnvironment,
    private val scopeStillCurrent:()->Boolean={true}) {
    fun reconcile():List<SavedDailyReport> {
        val env=environment();val config=ledger.config();if(!config.enabled || !scopeStillCurrent())return ledger.list()
        ledger.recover(env)
        var cursor=config.cursor ?: env.now.atZone(env.zone).toLocalDate().minusDays(1)
        // Reconcile at most 14 due days per run; cursor continues on the next heartbeat.
        repeat(14) {
            val r=ledger.allocate(ReportWindow(cursor,env.zone),config.generation)
            if(r==null) { cursor=cursor.plusDays(1);ledger.setCursor(cursor,env.zone);return@repeat }
            if(env.now.isBefore(r.window.due)) { ledger.setCursor(cursor,env.zone);return ledger.list() }
            cursor=cursor.plusDays(1);ledger.setCursor(cursor,env.zone)
        }
        // The next due day is created on the next run, keeping this catch-up strictly bounded.
        return ledger.list()
    }
    fun read(window:ReportWindow):DailyReportInput=source.snapshot(window)
    fun review(id:String,expectedBasis:String,text:String,privacyConfirmed:Boolean) {
        check(scopeStillCurrent());val config=ledger.config();val r=requireNotNull(ledger.get(id));val input=source.snapshot(r.window)
        check(!r.window.backlogReview) { "BACKLOG_RANGE_REVIEW_REQUIRED_NO_DAILY_SEND" }
        check(!environment().now.isBefore(r.window.until)) { "PREVIOUS_CALENDAR_DAY_NOT_CLOSED" }
        check(scopeStillCurrent())
        check(scopeStillCurrent());ledger.freeze(id,input,expectedBasis,text,privacyConfirmed,config.generation)
    }
    suspend fun tick() {
        reconcile();val generation=ledger.config().generation
        for(r in ledger.list().reversed()) {
            val env=environment();if(!ledger.enabled(generation) || !scopeStillCurrent())return
            if(env.now.isBefore(r.window.until) || r.delivery==ReportDelivery.CANCELLED)continue
            val input=try { source.snapshot(r.window) } catch(_:Exception) {
                DailyReportInput(ReportReadiness.SOURCE_FAILURE,emptyList(),0,1,DeliveryCoordinator.sha256("SOURCE_FAILURE"),
                    listOf(ReportPendingSource("inventory",0,"",0,0,0,"INVENTORY_UNAVAILABLE","SOURCE_FAILURE",false)))
            }
            ledger.observe(r.id,input,env,generation)
            val current=requireNotNull(ledger.get(r.id))
            if(r.window.backlogReview)continue // Range allocation is not completion; no catch-up dispatch.
            if(env.now.isBefore(r.window.due) || current.readiness==ReportReadiness.STALE_REVIEW)continue
            if(!input.fullyReviewed || current.basisHash.isBlank() || current.basisHash!=input.basisHash)continue
            if(!env.online || env.doze || !env.batteryOkay)continue
            if(current.delivery !in listOf(ReportDelivery.SEND_WAITING,ReportDelivery.RETRY))continue
            val gate=sender.gate()
            if(gate!=null) { ledger.waiting(r.id,when(gate.status) {
                DeliveryResult.Status.DISABLED -> "카카오 실제 전송 비활성 · 검토한 보고는 앱에 보관됨"
                else -> gate.messageKo()
            });continue }
            val body=try { ledger.text(r.id) } catch(_:Exception) { ledger.waiting(r.id,"보고 저장소·키 확인 필요 · 전송 중단");continue }
            val claim=ledger.claim(r.id,input,env,generation) ?: continue
            val finalInput=source.snapshot(r.window)
            if(!scopeStillCurrent() || !ledger.valid(r.id,claim,generation,environment()) || finalInput.basisHash!=input.basisHash || !finalInput.fullyReviewed) {
                ledger.rejectClaim(r.id,claim,"dispatch 직전 계정·동의·원본·검토 상태 변경 · 전송 보류");continue
            }
            val result=try { sender.send(DailyReport(ledger.profile,r.window.date,r.window.zone,body,true,false)) }
                catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
                catch(_:Exception) { DeliveryResult(DeliveryResult.Status.UNKNOWN) }
            ledger.complete(r.id,claim,result,environment(),generation)
        }
    }
}

internal object DailyReportRuntime {
    data class Scope(val context:Context,val profile:String,val accountEpoch:Long)
    fun scope(context:Context):Scope {
        val verified=GoogleConnectionSession.get(context).dailyReportScope();val owner=verified.ownerId
        return Scope(if(owner.isBlank())context.applicationContext else AccountStorageContext(context.applicationContext,owner),
            ScopedNaverMailSettingsActivity.ownerNamespace(owner),verified.epoch)
    }
    fun current(context:Context,scope:Scope)=runCatching { val now=DailyReportRuntime.scope(context);now.profile==scope.profile && now.accountEpoch==scope.accountEpoch }.getOrDefault(false)
    fun environment(context:Context,trigger:String="RECONCILE"):ReportEnvironment {
        val cm=context.getSystemService(ConnectivityManager::class.java);val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        val power=context.getSystemService(PowerManager::class.java)
        val battery=context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return ReportEnvironment(Instant.now(),ZoneId.systemDefault(),caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true,
            power.isDeviceIdleMode,!power.isPowerSaveMode && (battery<0 || battery>=15),SystemClock.elapsedRealtime(),
            Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,0).toLong(),trigger)
    }
}
