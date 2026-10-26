package kr.callbrief

import android.content.Context
import androidx.work.*
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Only previously published requests inside their original source lease. No lease renewal. */
internal object AutomationPendingResults {
    fun known(j:JSONObject)=(j.optString("state") in setOf("WAITING_RESULT","UNKNOWN","DISPATCHING") || j.optString("state")=="WAITING_LOCAL_RESUME" && j.optString("receipt").isNotBlank()) && j.optString("request_id").isNotBlank() && j.optString("handoff").isNotBlank()
    fun deadline(j:JSONObject):Long?=runCatching {
        val h=JSONObject(j.getString("handoff"));val raw=h.getString("frozenRequestJson");check(DriveRelay.sha256(raw.toByteArray(Charsets.UTF_8))==h.getString("frozenRequestSha256"))
        check(h.getString("localRequestId")==j.getString("request_id"))
        val request=JSONObject(raw);check(request.getString("sourceId")==j.getString("recording_id") && request.getString("audioSha")==j.getString("audio_sha"))
        check(request.getJSONObject("ownerBinding").getString("ownerId")==j.getString("owner"))
        val context=JSONObject(request.getString("contextJson"));val expiry=context.get("leaseExpiresMs")
        check(expiry is Long || expiry is Int);(expiry as Number).toLong().also{check(it in 1..9007199254740991L)}
    }.getOrNull()
    fun active(j:JSONObject,now:Long)=known(j) && deadline(j)?.let{it>now}==true
}
internal interface AutomaticResultWorkRegistry {fun once(name:String,request:OneTimeWorkRequest)}
internal object AutomaticResultReconciliation {
    const val TAG="ieum.automation.existing-results.v1"
    @Volatile private var registry:AutomaticResultWorkRegistry?=null
    @Volatile private var reconcileForHost:((Context,()->Boolean)->JSONObject)?=null
    fun installForHost(work:AutomaticResultWorkRegistry?=null,reconcile:((Context,()->Boolean)->JSONObject)?=null){registry=work;reconcileForHost=reconcile}
    /** Rearm a previously failed auth wait after explicit existing-account confirmation.
     * Local journal read only: never discovers/prepares/enrolls a source or renews a lease. */
    fun rearmExisting(context:Context,session:GoogleConnectionSession,now:Long=System.currentTimeMillis()) {
        val scope=DriveALocalScope.current(session) ?: return
        val scoped=AccountStorageContext(AutomationConnectionBindings.base(context),scope.owner)
        if(!ProtectedDatabase.file(scoped,GeneralLocalStore.DATABASE).exists())return
        val jobs=ProtectedDatabase.open(scoped,GeneralLocalStore.DATABASE,null).use{p->val db=p.database()
            if(!db.rawQuery("SELECT name FROM sqlite_master WHERE name='automation_jobs' AND type='table'",null).use{it.moveToFirst()})return@use emptyList<JSONObject>()
            db.rawQuery("SELECT owner,epoch,state,request_id,handoff,recording_id,audio_sha,receipt FROM automation_jobs WHERE owner=? AND epoch=?",arrayOf(scope.owner,scope.epoch.toString())).use{c->
                val rows=mutableListOf<JSONObject>()
                while(c.moveToNext())rows+=JSONObject().apply{for(i in 0 until c.columnCount)put(c.getColumnName(i),when(c.getType(i)){
                    android.database.Cursor.FIELD_TYPE_NULL->JSONObject.NULL
                    android.database.Cursor.FIELD_TYPE_INTEGER->c.getLong(i)
                    android.database.Cursor.FIELD_TYPE_STRING->c.getString(i)
                    else->error("EXISTING_RESULT_JOURNAL_REVIEW")
                })}
                rows
            }
        }
        ensure(context,session,jobs,now)
    }
    private fun sameBinding(j:JSONObject,scope:DriveALocalScope)=runCatching{StrictJson.`object`(StrictJson.parse(j.getString("handoff").toByteArray()))["scope"]==scope.binding()}.getOrDefault(false)
    fun ensure(context:Context,session:GoogleConnectionSession,jobs:List<JSONObject>,now:Long=System.currentTimeMillis()) {
        val scope=DriveALocalScope.current(session) ?: session.previouslyVerifiedScope() ?: return
        if(jobs.none{it.optString("owner")==scope.owner && it.optLong("epoch",-1)==scope.epoch && sameBinding(it,scope) && AutomationPendingResults.active(it,now+30000)})return
        val request=OneTimeWorkRequestBuilder<AutomationPendingResultWorker>().setInitialDelay(30,TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .setInputData(workDataOf("owner" to scope.owner,"epoch" to scope.epoch,"native_scope" to scope.key)).addTag(TAG).build()
        val name="$TAG.${scope.key}"
        val capture=registry
        if(capture!=null)capture.once(name,request)
        else WorkManager.getInstance(context).enqueueUniqueWork(name,ExistingWorkPolicy.KEEP,request)
    }
    fun receive(context:Context,stopped:()->Boolean)=reconcileForHost?.invoke(context,stopped) ?: AndroidAutomationRuntime.reconcileExistingResults(context,stopped)
}
/** Bounded while the existing native session remains active. Work success is never analysis success. */
class AutomationPendingResultWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    override fun doWork():Result {
        if(isStopped)return Result.failure(workDataOf("automation_state" to "LOCAL_WORK_STOPPED"))
        val session=GoogleConnectionSession.get(applicationContext)
        val auth=ExistingGoogleSessionRecoveryRuntime.recover(applicationContext,session,inputData.getString("owner").orEmpty(),inputData.getLong("epoch",-1),inputData.getString("native_scope")){isStopped}
        if(!auth.ready)return if(auth.retryable && runAttemptCount<6)Result.retry()else Result.failure(workDataOf("automation_state" to auth.state,"reason" to auth.reason))
        val scope=DriveALocalScope.current(session) ?: return Result.failure(workDataOf("automation_state" to "WAITING_USER_REAUTH"))
        if(scope.owner!=inputData.getString("owner") || scope.epoch!=inputData.getLong("epoch",-1) || scope.key!=inputData.getString("native_scope"))
            return Result.failure(workDataOf("automation_state" to "STALE_NATIVE_SCOPE"))
        if(runAttemptCount>=6)return Result.failure(workDataOf("automation_state" to "RESULT_POLL_BACKOFF_LIMIT"))
        return try {
            val state=AutomaticResultReconciliation.receive(applicationContext){isStopped}
            if(!isStopped && state.optInt("pendingExistingResults")>0)Result.retry()
            else {
                val value=state.optString("state","WAITING_RESULT")
                if(value in setOf("WAITING_AUTH","WAITING_NATIVE_GRANT","HELD","LOCAL_WORK_STOPPED"))Result.failure(workDataOf("automation_state" to value))
                else Result.success(workDataOf("automation_state" to value))
            }
        } catch(_:Exception){if(isStopped || runAttemptCount>=6)Result.failure(workDataOf("automation_state" to "RESULT_RECONCILE_HELD")) else Result.retry()}
    }
}
