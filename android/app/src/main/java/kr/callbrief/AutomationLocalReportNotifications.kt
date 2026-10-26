package kr.callbrief

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kr.co.callsummary.core.android.ProtectedDatabase
import kr.callagent.drive.DriveRelay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Local only, no permission prompt, login, remote message or source audio access. */
internal interface LocalReportNotificationPort {
    fun gate():String
    fun post(key:String,date:LocalDate)
}
internal class AndroidLocalReportNotifier(private val context:Context,private val current:()->Boolean):LocalReportNotificationPort {
    companion object {const val CHANNEL="ieum.daily.local.v1"}
    override fun gate():String {
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return "WAITING_NOTIFICATION_PERMISSION"
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE)return "WAITING_NOTIFICATION_CHANNEL"
        return "READY"
    }
    override fun post(key:String,date:LocalDate) {
        check(current() && gate()=="READY") {"LOCAL_NOTIFICATION_PERMISSION_OR_SCOPE_CHANGED"}
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"이음 일일보고",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,DailyReportActivity::class.java).setData(Uri.parse("callbrief://local-report/$key"))
            .putExtra("local_report_date",date.toString()).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,key.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_callbrief)
            .setContentTitle("이음 일일보고를 확인하세요").setContentText("앱에서 요약과 처리 상태를 확인할 수 있어요.")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).build()
        check(current() && gate()=="READY") {"LOCAL_NOTIFICATION_PERMISSION_OR_SCOPE_CHANGED"}
        manager.notify("ieum.local.$key",600,notification)
    }
}

internal class AutomationLocalReportCoordinator(private val store:AndroidAutomationStore,
    private val notifier:LocalReportNotificationPort,private val current:()->Boolean) {
    fun run(window:ReportWindow,batch:JSONObject,now:Instant):JSONObject {
        if(now.isBefore(window.due))return JSONObject().put("state","WAITING_06")
        check(current()) {"LOCAL_REPORT_SCOPE_CHANGED"}
        val (copy,alert)=store.saveLocalReport(window,batch,now.toEpochMilli())
        val old=store.localAlert(alert).getString("state")
        if(old in setOf("LOCAL_NOTIFY_REQUESTED","UNKNOWN"))return JSONObject().put("state",old).put("copyId",copy)
        val gate=notifier.gate()
        if(gate!="READY") {store.localAlertState(alert,gate,gate,now.toEpochMilli());return JSONObject().put("state",gate).put("copyId",copy)}
        if(!store.claimLocalAlert(alert,now.toEpochMilli()))return JSONObject().put("state",store.localAlert(alert).getString("state")).put("copyId",copy)
        if(!current()){store.localAlertState(alert,"WAITING_SCOPE","LOCAL_REPORT_SCOPE_CHANGED",now.toEpochMilli());return JSONObject().put("state","WAITING_SCOPE")}
        try {
            notifier.post(alert,window.date)
            store.localAlertState(alert,"LOCAL_NOTIFY_REQUESTED","ANDROID_NOTIFY_CALLED_NOT_DEVICE_READ_RECEIPT",now.toEpochMilli())
        } catch(_:Exception) {store.localAlertState(alert,"UNKNOWN","LOCAL_NOTIFICATION_UNCERTAIN_NO_REPEAT",now.toEpochMilli())}
        return JSONObject().put("state",store.localAlert(alert).getString("state")).put("copyId",copy).put("kakaoSent",false)
    }
}

/** Reuses the existing 06:00 WorkManager target and time/boot reconciliation. */
internal object AutomationLocalReports {
    fun run(context:Context,scope:DailyReportRuntime.Scope,ledger:DailyReportLedger,env:ReportEnvironment,generation:Long,current:()->Boolean):JSONObject {
        if(!current() || !ledger.enabled(generation))return JSONObject().put("state","CANCELLED")
        val row=ledger.list().filter{!it.window.backlogReview && !env.now.isBefore(it.window.due) && it.delivery!=ReportDelivery.CANCELLED}.maxByOrNull{it.window.due}
            ?: return JSONObject().put("state","WAITING_06")
        val session=GoogleConnectionSession.get(context);val view=session.dailyReportScope()
        val scoped=scope.context;val settings=SecureSettings(scoped)
        if(!ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE).exists())return JSONObject().put("state","WAITING_LOCAL_SOURCE")
        return AndroidAutomationStore(scoped,view.ownerId,view.epoch,current,{AndroidAutomationRuntime.policyHash(settings.snapshot())},
            {localSourceAllowed(scoped,session,it)},{row.window.zone.id}).use { store ->
            val batch=store.reportBatch(row.window)
            val canonical=runCatching{CanonicalReviewedDailySource(scoped).snapshot(row.window)}.getOrNull()
            val covered=buildSet {for(key in listOf("automatic","unresolved")){val a=batch.getJSONArray(key);for(i in 0 until a.length())add(a.getJSONObject(i).getString("recordingId"))}}
            val unresolved=batch.getJSONArray("unresolved")
            canonical?.unresolved?.filter{it.id !in covered}?.forEach{unresolved.put(JSONObject().put("recordingId",it.id).put("state",it.state).put("reason",it.reason))}
            val sourceFailure=canonical==null || canonical.readiness==ReportReadiness.SOURCE_FAILURE
            val manual=canonical?.sources?.size ?: 0
            if(batch.getJSONArray("automatic").length()==0 && unresolved.length()==0 && manual==0 && !sourceFailure)
                return@use JSONObject().put("state","NO_RECORDINGS")
            batch.put("manualReviewedCount",manual).put("sourceFailure",sourceFailure).put("origin","LOCAL_AUTOMATIC_COPY_NOT_USER_ACCEPT_OR_KAKAO_PAYLOAD")
                .put("complete",!sourceFailure && unresolved.length()==0 && (manual>0 || batch.getJSONArray("automatic").length()>0))
            AutomationLocalReportCoordinator(store,AndroidLocalReportNotifier(scoped,current),current).run(row.window,batch,env.now)
        }
    }
    /** Existing metadata permission permits local viewing without a live Google token. */
    fun localSourceAllowed(context:Context,session:GoogleConnectionSession,s:JSONObject):Boolean=runCatching {
        val settings=SecureSettings(context);val state=settings.snapshot()
        check(!state.optBoolean("pendingRevocation") && state.optString("treeUri")==s.getString("root_uri"))
        val d=SourceDocument(s.getString("actual_document_id"),s.getString("actual_uri"),s.getString("display_name"),s.getLong("byte_count"),s.getString("mime_type"),s.getLong("modified_at_ms"))
        val source=SafSelectedRecordingSource(context,settings,s.getString("root_uri"));source.checkSelected();check(!source.excluded(d) && source.stat(d)==d)
        val a=state.optJSONObject("nativeSyntheticApprovals")?.optJSONObject(SyntheticApprovedSource.approvalKey(source.rootUri,d))
        check(a!=null && a.optBoolean("syntheticCreatorConfirmed") && a.optString("ownerId")==session.ownerBinding() && a.optLong("authEpoch",-1)==session.epoch())
        true
    }.getOrDefault(false)
}

/** Read-only local copies. Permission/policy drift hides content, preserving the immutable copy. */
internal object AutomationLocalReportReader {
    private fun localStatus(s:String)=when(s) {
        "WAITING_NOTIFICATION_PERMISSION"->"알림 권한 없음 · 앱에 보고 보관"
        "WAITING_NOTIFICATION_CHANNEL"->"기기 알림이 꺼져 있음 · 앱에 보고 보관"
        "LOCAL_NOTIFY_REQUESTED"->"기기에 로컬 알림 표시 요청함 · 읽음 미확인"
        "UNKNOWN","INTENT"->"알림 표시 여부 확인 필요 · 자동 반복 보류"
        else->"오전 6시 로컬 알림 대기"
    }
    fun read(context:Context,owner:String,policySnapshot:()->String={AndroidAutomationRuntime.policyHash(SecureSettings(context).snapshot())},
        sourceVisible:(JSONObject)->Boolean={AutomationLocalReports.localSourceAllowed(context,GoogleConnectionSession.get(context),it)}):List<Pair<String,String>> {
        val sourceFile=ProtectedDatabase.file(context,GeneralLocalStore.DATABASE)
        if(owner.isBlank() || listOf(sourceFile,java.io.File(sourceFile.path+".bak"),java.io.File(sourceFile.path+".new")).none{it.exists()})return emptyList()
        return ProtectedDatabase.open(context,GeneralLocalStore.DATABASE,null).use{store->val db=store.database();ProtectedDatabase.read(db){
            if(!db.rawQuery("SELECT name FROM sqlite_master WHERE name='automation_local_reports'",null).use{it.moveToFirst()})return@read emptyList()
            val policy=policySnapshot();val session=GoogleConnectionSession.get(context)
            db.rawQuery("SELECT day,zone,policy_hash,digest,payload,from_ms,until_ms FROM automation_local_reports WHERE owner=? ORDER BY created_ms DESC,rowid DESC LIMIT 30",arrayOf(owner)).use{c->buildList{
                while(c.moveToNext()) {
                    val title="${c.getString(0)} · ${c.getString(1)} · 로컬 보관본"
                    val text=c.getString(4);check(DriveRelay.sha256(text.toByteArray())==c.getString(3)){"LOCAL_REPORT_DIGEST_MISMATCH"}
                    val batch=JSONObject(text);var visible=c.getString(2)==policy && session.ownerBinding()==owner;var stale=false
                    val alert=AndroidAutomationStore.hash(listOf("local-daily-alert-v1",owner,c.getString(0),c.getString(1),c.getLong(5),c.getLong(6)))
                    val alertState=db.rawQuery("SELECT state FROM automation_local_alerts WHERE id=?",arrayOf(alert)).use{q->if(q.moveToFirst())q.getString(0) else "WAITING_06"}
                    val a=batch.getJSONArray("automatic")
                    for(i in 0 until a.length()) {
                        val id=a.getJSONObject(i).getString("recordingId")
                        val row=db.rawQuery("SELECT r.*,l.root_uri,l.actual_uri,l.actual_document_id,g.active AS grant_active,v.archived FROM recordings r JOIN local_files l ON l.recording_id=r.recording_id JOIN folder_grants g ON g.grant_id=r.grant_id JOIN local_visibility v ON v.recording_id=r.recording_id WHERE r.recording_id=?",arrayOf(id)).use{q->
                            if(!q.moveToFirst())null else JSONObject().apply{for(j in 0 until q.columnCount)put(q.getColumnName(j),if(q.isNull(j))JSONObject.NULL else if(q.getType(j)==android.database.Cursor.FIELD_TYPE_INTEGER)q.getLong(j) else q.getString(j))}
                        }
                        if(row==null || row.optInt("archived")!=0 || row.optInt("grant_active")!=1 || !sourceVisible(row))visible=false
                        if(row!=null && listOf("userRevision" to "user_revision","lockRevision" to "lock_revision","headRevision" to "head_revision").any{(saved,current)->a.getJSONObject(i).optLong(saved)!=row.optLong(current)})stale=true
                    }
                    if(!visible){add(title to "개인정보·원본 설정이 변경되어 이 보관본 내용은 숨겼어요. 원본은 보존합니다.");continue}
                    val body=buildString{
                        append(localStatus(alertState)+"\n");if(stale)append("직접 수정 전의 보관본 · 현재 기록은 그대로 유지돼요.\n")
                        append(if(batch.optBoolean("complete"))"자동 누적 보고 · 사용자 검토 영수증 아님\n" else "부분 보고 · 전체 완료 아님\n")
                        for(i in 0 until a.length())append(a.getJSONObject(i).optString("cumulativeSummary")+"\n")
                        val pending=batch.getJSONArray("unresolved");for(i in 0 until pending.length()){val p=pending.getJSONObject(i);append("${p.optString("state")} · ${p.optString("reason")}\n")}
                        if(batch.optInt("manualReviewedCount")>0)append("직접 확인한 기존 보고 ${batch.optInt("manualReviewedCount")}개는 아래 보관 보고에서 볼 수 있어요.\n")
                        append("Kakao 무인 발송: 정책 미확인으로 비활성")
                    }
                    add(title to body)
                }
            }}
        }}
    }
}
