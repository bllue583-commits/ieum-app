package kr.callbrief

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.work.*
import kr.co.ieum.discovery.DiscoveryPump
import kr.co.ieum.discovery.DiscoveryTypes.*
import kr.co.ieum.discovery.DiscoveryTypes.Failure
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.*

/** Periodic scan of the selected recording folder into the encrypted vault, then hands new recordings to transcription. */
internal object AutomaticRecordingDiscovery {
    const val TAG="ieum.local.recording.discovery"
    /** One pass keeps working until the line is empty, but at most this long (inside WorkManager's 10 minute limit). */
    const val CYCLE_BUDGET_MS=75_000L
    /** Settings key: when the last pass over the folder finished (heartbeat lastDiscoveryMs, design B1). A time only. */
    const val LAST_DISCOVERY_MS="lastDiscoveryMs"
    private val running=ConcurrentHashMap.newKeySet<String>()
    @Volatile private var hostStore:((Context)->GeneralLocalStore)?=null
    @Volatile private var hostWork:((String,PeriodicWorkRequest)->Unit)?=null
    internal fun installForHost(store:((Context)->GeneralLocalStore)?=null,work:((String,PeriodicWorkRequest)->Unit)?=null) {
        check(BuildConfig.DEBUG);hostStore=store;hostWork=work
    }
    /** Stable local scope; the storage layout predates accounts and must not change. */
    private val scope=kr.co.callsummary.core.JsonCanonical.digest(mapOf("owner" to ""))

    internal val SCOPE get()=scope
    /** Work input holds the selection fence only. Never a credential or grant. */
    internal data class Binding(val selectionFence:String) {
        fun data()=workDataOf("selection_fence" to selectionFence)
    }
    private fun sha256(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    /** Changes whenever the folder, its revision or the privacy rules change, so a queued run for old rules stops. */
    private fun selectionFence(state:JSONObject):String? {
        val selected=selection(state) ?: return null
        if(state.optBoolean("pendingRevocation"))return null
        val policy=JSONObject()
        listOf("treeUri","personalPhoneDisabled","personalAliasDisabled","exclusions","identities","aliasMappings").forEach { if(state.has(it))policy.put(it,state.get(it)) }
        return sha256(listOf(selected.ownerId,selected.treeUri,selected.revision.toString(),policy.toString()).joinToString("\n"))
    }
    fun capture(context:Context):Binding? = selectionFence(SecureSettings(context).snapshot())?.let(::Binding)
    fun binding(input:Data):Binding? {
        val fence=input.getString("selection_fence") ?: return null
        return if(fence.matches(Regex("[0-9a-f]{64}")))Binding(fence) else null
    }
    fun outcome(state:String,reason:String="")=JSONObject().put("state",state).put("reason",reason)
    private fun blocked(context:Context,binding:Binding,stopped:()->Boolean):JSONObject? {
        if(stopped())return outcome("LOCAL_WORK_STOPPED")
        val state=SecureSettings(context).snapshot()
        if(selectionFence(state)!=binding.selectionFence)return outcome("HELD_SOURCE","DISCOVERY_SELECTION_OR_POLICY_CHANGED")
        val selected=selection(state) ?: return outcome("HELD_SOURCE","SELECTED_FOLDER_REQUIRED")
        if(runCatching{SafSelectedRecordingSource(context,SecureSettings(context),selected.treeUri).checkSelected()}.isFailure)
            return outcome("HELD_SOURCE","SELECTED_FOLDER_READ_GRANT_REQUIRED")
        return null
    }
    /** Fixed code for settings and work output: the state, plus the reason when there is one. */
    fun code(result:JSONObject):String=result.optString("state").let { st -> result.optString("reason").let { if(it.isBlank())st else st+":"+it } }
    /** Only a fixed failure code or the exception's class name, never its message (which may name a file). */
    fun errorCode(error:Throwable):String="ERROR:"+when(error) {
        is Failure->error.code
        is kr.co.callsummary.core.CoreException->error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "CORE"
        is IllegalStateException->error.message?.takeIf { it.matches(Regex("[A-Z0-9_]{1,80}")) } ?: "IllegalStateException"
        else->error.javaClass.simpleName.ifBlank { "Exception" }
    }
    /** Design 3.2 #4 (review pipeline #8): every attempt leaves its time and a code, also when it stops early or fails. */
    internal fun recordAttempt(context:Context,startedMs:Long,code:String,truncated:Boolean?=null,heldTooLarge:Int?=null) {
        runCatching { SecureSettings(context).update {
            it.put(SecureSettings.LAST_DISCOVERY_ATTEMPT_MS,startedMs).put(SecureSettings.LAST_DISCOVERY_OUTCOME,code)
            if(truncated!=null)it.put(SecureSettings.INVENTORY_TRUNCATED,truncated)
            if(heldTooLarge!=null)it.put(SecureSettings.HELD_UNSUPPORTED_COUNT,heldTooLarge)
        } }
    }
    /** One bounded pass per frozen binding. Its outcome code is always written to settings, even when it throws. */
    fun cycle(context:Context,binding:Binding?,stopped:()->Boolean,active:(NativeDiscoverySession?)->Unit={},observed:(DiscoveryPump.Cycle)->Unit={},fromFollowup:Boolean=false):JSONObject {
        val started=System.currentTimeMillis();val facts=PassFacts()
        try {
            return attempt(context,binding,stopped,active,observed,fromFollowup,facts).also { facts.code=code(it) }
        } catch(error:Throwable) {
            facts.code=if(stopped())"LOCAL_WORK_STOPPED:"+errorCode(error).removePrefix("ERROR:") else errorCode(error);throw error
        } finally { recordAttempt(context,started,facts.code ?: "ERROR:UNKNOWN",facts.truncated,facts.heldTooLarge) }
    }
    private class PassFacts { var code:String?=null;var truncated:Boolean?=null;var heldTooLarge:Int?=null }
    private fun attempt(context:Context,binding:Binding?,stopped:()->Boolean,active:(NativeDiscoverySession?)->Unit,observed:(DiscoveryPump.Cycle)->Unit,fromFollowup:Boolean,facts:PassFacts):JSONObject {
        if(binding==null)return outcome("WAITING_DISCOVERY_BINDING","DISCOVERY_WORK_REARM_REQUIRED")
        blocked(context,binding,stopped)?.let{return it}
        if(!running.add(binding.selectionFence))return outcome("DISCOVERY_ALREADY_RUNNING")
        try {
            val cancelled={stopped() || blocked(context,binding,{false})!=null}
            var scan:DiscoveryPump.Cycle?=null;var sawNew=false
            val local=open(context,{!cancelled()},stopped) ?: return outcome("WAITING_LOCAL_SOURCE","SELECTED_FOLDER_REQUIRED")
            local.use { session ->
                active(session)
                try {
                    if(cancelled())session.cancel()
                    val before=runCatching{session.knownFiles()}.getOrDefault(-1)
                    scan=session.cycle(32,4,CYCLE_BUDGET_MS)
                    sawNew=before>=0 && runCatching{session.knownFiles()}.getOrDefault(-1)>before
                    facts.truncated=session.lastListingTruncated
                    runCatching{session.heldTooLarge()}.getOrNull()?.let{facts.heldTooLarge=it}
                }finally{active(null)}
            }
            if(sawNew&&!cancelled())runCatching{scheduleFollowup(context,binding,fromFollowup)} // A4: look again soon instead of waiting for the next 15 minutes
            if(!cancelled())runCatching{kr.callbrief.pipeline.CallTranscriptionWork.enqueue(context)} // new recordings -> transcribe soon
            blocked(context,binding,stopped)?.let{return it}
            scan?.let(observed)
            // A pass that could not store anything says why (consent, vault key, folder grant) next to its code,
            // and only a pass without such a hold counts as the last successful one (lastDiscoveryMs).
            val hold=scan?.let { c -> when {
                c.folderPaused->"FOLDER_READ_GRANT_MISSING"
                else->c.localOutcomes.firstOrNull { it=="PERSONAL_DATA_COLLECTION_NOT_ACCEPTED" || it=="AUDIO_STORE_INITIALIZATION_REQUIRED" }
            } }
            if(hold==null)runCatching{SecureSettings(context).update{it.put(LAST_DISCOVERY_MS,System.currentTimeMillis())}}
            return outcome("DISCOVERY_CHECKED",hold.orEmpty())
        } finally {active(null);running.remove(binding.selectionFence)}
    }
    fun selection(state:JSONObject):Selection? {
        val tree=state.optString("treeUri");if(tree.isBlank())return null
        return Selection(GeneralLocalStore.OWNER+"_"+scope.take(20),tree,state.optLong("automaticDiscoverySelectionRevision",1L).coerceAtLeast(1L))
    }
    /** The run fence ([current]: settings, folder grant) is checked on every listing, stat and open; reads of an opened file only look at [stopped]. */
    internal fun guarded(delegate:SelectedRecordingSource,current:()->Boolean,stopped:()->Boolean={false})=object:SelectedRecordingSource by delegate {
        // Inside one page of decisions the fence (settings decrypt + grant check) is evaluated once (design 3.2 #3).
        private var batch=0;private var passed=false
        private fun permit(){
            if(batch>0&&passed)return
            check(current()) { "DISCOVERY_RUN_FENCE_CHANGED" };if(batch>0)passed=true
        }
        override fun checkSelected(){permit();delegate.checkSelected()}
        override fun inventory():SourceInventory {permit();return delegate.inventory().also{permit()}}
        override fun stat(document:SourceDocument):SourceDocument {permit();return delegate.stat(document).also{permit()}}
        override fun excluded(document:SourceDocument):Boolean {permit();return delegate.excluded(document)}
        override fun checkReadable(document:SourceDocument){permit();delegate.checkReadable(document)}
        override fun beginBatch(){if(batch++==0)passed=false;delegate.beginBatch()}
        override fun endBatch(){if(batch>0&&--batch==0)passed=false;delegate.endBatch()}
        override fun open(document:SourceDocument):java.io.InputStream {
            permit()
            return object:java.io.FilterInputStream(delegate.open(document)) {
                private fun running(){check(!stopped()) { "DISCOVERY_RUN_FENCE_CHANGED" }}
                override fun read():Int {running();return `in`.read()}
                override fun read(b:ByteArray,o:Int,n:Int):Int {running();return `in`.read(b,o,n)}
                override fun skip(n:Long):Long {running();return `in`.skip(n)}
            }
        }
    }
    fun open(context:Context,current:()->Boolean={true},stopped:()->Boolean={false}):NativeDiscoverySession? {
        val settings=SecureSettings(context);val selected=selection(settings.snapshot()) ?: return null
        val source=guarded(SafSelectedRecordingSource(context,settings,selected.treeUri,kr.callbrief.pipeline.FirstPass.FROM_MS),current,stopped)
        val store=hostStore?.invoke(context) ?: GeneralLocalStore(context) // Received protected SQL implementation is mandatory.
        return try {
            NativeDiscoverySession(store,source,selected,settings::snapshot,
                kr.co.ieum.discovery.DiscoveryTypes.Clock { Moment("boot_"+Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT),SystemClock.elapsedRealtime(),System.currentTimeMillis()) },syntheticFixture=source.explicitlySyntheticScope,minModifiedMs=kr.callbrief.pipeline.FirstPass.FROM_MS)
        }catch(error:Exception){store.close();throw error}
    }
    const val FOLLOWUP_NAME="ieum.discovery.followup"
    const val CALLLOG_NAME="ieum.discovery.calllog"
    /** NativeDiscoverySession keeps a new file back until it was seen twice and was quiet (its 2 minute "partial" rule), so look again after 150 s. */
    const val FOLLOWUP_DELAY_SECONDS=150L
    internal fun followupRequest(binding:Binding)=OneTimeWorkRequestBuilder<AutomaticRecordingDiscoveryWorker>()
        .setInitialDelay(FOLLOWUP_DELAY_SECONDS,TimeUnit.SECONDS).setInputData(binding.data()).addTag(TAG).addTag(FOLLOWUP_NAME).build()
    /** [fromFollowup]: called from the follow-up work itself, which REPLACE would cancel; its next one is appended instead. */
    internal fun scheduleFollowup(context:Context,binding:Binding,fromFollowup:Boolean=false) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(FOLLOWUP_NAME,
            if(fromFollowup)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,followupRequest(binding))
    }
    /** After the last call log change. A recording modified in the last 2 minutes is "partial" (NativeDiscoverySession),
     * so a pass 45 s after the call could never store it; 140 s lets the file settle first (design 3.2 #4, review #18). */
    const val CALLLOG_DELAY_SECONDS=140L
    /** A5: one-time work that waits for a change of the call log (a call ended), runs one cycle and registers itself again. */
    internal fun callLogRequest(binding:Binding?=null)=OneTimeWorkRequestBuilder<CallLogDiscoveryWorker>()
        .setConstraints(Constraints.Builder().addContentUriTrigger(android.provider.CallLog.Calls.CONTENT_URI,true)
            .setTriggerContentUpdateDelay(CALLLOG_DELAY_SECONDS,TimeUnit.SECONDS).build())
        .also { if(binding!=null)it.setInputData(binding.data()) }.addTag(TAG).addTag(CALLLOG_NAME).build()
    fun canWatchCallLog(context:Context)=context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)==android.content.pm.PackageManager.PERMISSION_GRANTED
    /** [policy] KEEP at app start; APPEND_OR_REPLACE from the running work itself (a content trigger fires once, so it is registered again every time). */
    fun ensureCallLogTrigger(context:Context,policy:ExistingWorkPolicy=ExistingWorkPolicy.KEEP):Boolean {
        if(!canWatchCallLog(context)||capture(context)==null)return false
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(CALLLOG_NAME,policy,callLogRequest())
        return true
    }
    /** Body of [CallLogDiscoveryWorker]: one cycle, its code kept (not swallowed), and the trigger registered again in
     * finally, also when the cycle threw or the work was stopped (design 3.2 #4, review #21). [rearm] is replaceable in tests. */
    internal fun runCallLogTrigger(context:Context,stopped:()->Boolean,active:(NativeDiscoverySession?)->Unit={},
                                   run:()->JSONObject={ cycle(context,capture(context),stopped,active) },
                                   rearm:()->Unit={ ensureCallLogTrigger(context,ExistingWorkPolicy.APPEND_OR_REPLACE) }):String {
        try { return code(run()) }
        catch(error:Exception) { return if(stopped())"LOCAL_WORK_STOPPED" else errorCode(error) } // the cycle already wrote it to settings
        finally { runCatching(rearm) }
    }
    /** v2.9 r10-1 (design 6.1): queued by [kr.callbrief.pipeline.call.CallSession] when a call that went off hook ends. One
     * expedited pass [AFTERCALL_WAIT_MS] later (an expedited job cannot carry an initial delay, so the worker waits itself):
     * it sees the new recording, and its follow-up ([FOLLOWUP_NAME], 150 s, the "partial" rule) stores it and queues the
     * transcription at once; a finished transcript queues the upload at once ([kr.callbrief.pipeline.CallSinkImpl]).
     * The call log trigger and the 15 minute pass stay as the safety net. */
    const val AFTERCALL_NAME="ieum.discovery.aftercall"
    const val AFTERCALL_WAIT_MS=15_000L
    internal fun afterCallRequest(binding:Binding)=OneTimeWorkRequestBuilder<AfterCallDiscoveryWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).setInputData(binding.data()).addTag(TAG).addTag(AFTERCALL_NAME).build()
    /** False when no folder is chosen (nothing to look at). APPEND_OR_REPLACE: a pass that runs for the call before is not
     * cut off; the new one follows it. */
    fun scheduleAfterCall(context:Context):Boolean {
        val binding=capture(context) ?: return false
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(AFTERCALL_NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,afterCallRequest(binding))
        return true
    }
    fun ensure(context:Context) {
        runCatching{ensureCallLogTrigger(context)}
        val binding=capture(context) ?: return
        val name=TAG+"."+scope
        val request=PeriodicWorkRequestBuilder<AutomaticRecordingDiscoveryWorker>(15,TimeUnit.MINUTES)
            .setInputData(binding.data()).addTag(TAG).build()
        val capture=hostWork
        if(capture!=null)capture(name,request)
        // UPDATE replaces the input of an older enqueue; an already running pass retains its old fence.
        else WorkManager.getInstance(context).enqueueUniquePeriodicWork(name,ExistingPeriodicWorkPolicy.UPDATE,request)
    }
}

class AutomaticRecordingDiscoveryWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    @Volatile private var active:NativeDiscoverySession?=null
    override fun doWork():Result {
        if(isStopped)return Result.failure(workDataOf("automation_state" to "LOCAL_WORK_STOPPED"))
        if(runAttemptCount>=6)return Result.failure(workDataOf("automation_state" to "DISCOVERY_CHECK_RETRY_LIMIT"))
        return try {
            val result=AutomaticRecordingDiscovery.cycle(applicationContext,AutomaticRecordingDiscovery.binding(inputData),{isStopped},{active=it},
                fromFollowup=tags.contains(AutomaticRecordingDiscovery.FOLLOWUP_NAME))
            val state=result.getString("state")
            val output=workDataOf("automation_state" to state,"automation_reason" to result.getString("reason"))
            if(state in setOf("LOCAL_WORK_STOPPED","WAITING_DISCOVERY_BINDING","HELD_SOURCE"))Result.failure(output) else Result.success(output)
        }catch(_:Exception){if(isStopped)Result.failure(workDataOf("automation_state" to "LOCAL_WORK_STOPPED"))else Result.retry()}
    }
    override fun onStopped(){active?.cancel();super.onStopped()}
}

/** A5: runs when the call log changes (140 s after the last change), looks for new recordings once, and registers itself again. */
class CallLogDiscoveryWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    @Volatile private var active:NativeDiscoverySession?=null
    override fun doWork():Result {
        // The folder may have changed since this work was registered: the binding is taken fresh, not from the input.
        val code=AutomaticRecordingDiscovery.runCallLogTrigger(applicationContext,{isStopped},{active=it})
        // A call that just ended (a missed one too) is in the call list soon, without the screen matching the log (r8-1).
        if(!isStopped && !BuildConfig.LOCAL_PREVIEW) runCatching { val app=applicationContext
            kr.callbrief.IeumApplication.store(app).reconcileCallLog(kr.callbrief.pipeline.FirstPass.readCallLog(app,SecureSettings(app).snapshot())) }
        // Success even on a failed pass: the next trigger is appended to this work and would fail with it. The code stays.
        return Result.success(workDataOf("automation_state" to code))
    }
    override fun onStopped(){active?.cancel();super.onStopped()}
}

/** v2.9 r10-1: one discovery pass right after a call ([AutomaticRecordingDiscovery.AFTERCALL_NAME]). */
class AfterCallDiscoveryWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    @Volatile private var active:NativeDiscoverySession?=null
    override fun doWork():Result {
        // The recorder closes its file when the call ends; the wait lets it finish writing before the folder is listed.
        val until=SystemClock.elapsedRealtime()+AutomaticRecordingDiscovery.AFTERCALL_WAIT_MS
        while(!isStopped && SystemClock.elapsedRealtime()<until) runCatching { Thread.sleep(500) }
        if(isStopped) return Result.failure(workDataOf("automation_state" to "LOCAL_WORK_STOPPED"))
        val code=try {
            // The folder may have changed since the call ended: the binding is taken fresh, as for the call log work.
            AutomaticRecordingDiscovery.code(AutomaticRecordingDiscovery.cycle(applicationContext,AutomaticRecordingDiscovery.capture(applicationContext),{isStopped},{active=it}))
        } catch(error:Exception) { if(isStopped) "LOCAL_WORK_STOPPED" else AutomaticRecordingDiscovery.errorCode(error) }
        // Success even on a failed pass: a later call is appended to this work and would fail with it. The code stays.
        return Result.success(workDataOf("automation_state" to code))
    }
    override fun onStopped(){active?.cancel();super.onStopped()}
    /** Before Android 12 an expedited work runs as a foreground service and must show a notification: a quiet one. */
    override fun getForegroundInfo():ForegroundInfo {
        val app=applicationContext
        val manager=app.getSystemService(android.app.NotificationManager::class.java)
        manager?.createNotificationChannel(android.app.NotificationChannel(AFTERCALL_CHANNEL,"이음 통화 확인",android.app.NotificationManager.IMPORTANCE_MIN))
        val note=android.app.Notification.Builder(app,AFTERCALL_CHANNEL).setSmallIcon(R.drawable.ic_stat_ieum)
            .setContentTitle("방금 끝난 통화의 녹음을 찾고 있어요").setOngoing(true).build()
        return ForegroundInfo(AFTERCALL_NOTICE_ID,note)
    }
    private companion object { const val AFTERCALL_CHANNEL="ieum.discovery.aftercall";const val AFTERCALL_NOTICE_ID=731 }
}
