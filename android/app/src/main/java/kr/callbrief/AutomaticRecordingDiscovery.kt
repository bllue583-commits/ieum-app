package kr.callbrief

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.work.*
import kr.co.ieum.discovery.DiscoveryPump
import kr.co.ieum.discovery.DiscoveryTypes.*
import org.json.JSONObject
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Native intake followed by the durable automation coordinator. No login or grant request. */
internal object AutomaticRecordingDiscovery {
    const val TAG="ieum.local.recording.discovery"
    private val running=ConcurrentHashMap.newKeySet<String>()
    @Volatile private var hostStore:((Context)->GeneralLocalStore)?=null
    @Volatile private var hostWork:((String,PeriodicWorkRequest)->Unit)?=null
    internal fun installForHost(store:((Context)->GeneralLocalStore)?=null,work:((String,PeriodicWorkRequest)->Unit)?=null) {
        check(BuildConfig.DEBUG);hostStore=store;hostWork=work
    }
    private fun owner(context:Context)=(context as? AccountStorageContext)?.ownerId.orEmpty()
    private fun scope(context:Context)=kr.co.callsummary.core.JsonCanonical.digest(mapOf("owner" to owner(context)))
    /** Work input holds fences only. Never a credential, grant, new request or renewed lease. */
    internal data class Binding(val owner:String,val epoch:Long,val nativeScope:String,val selectionFence:String) {
        fun data()=workDataOf("local_owner" to owner,"auth_epoch" to epoch,"native_scope" to nativeScope,"selection_fence" to selectionFence)
    }
    private fun selectionFence(context:Context,state:JSONObject):String? {
        val selected=selection(context,state) ?: return null
        if(state.optBoolean("pendingRevocation"))return null
        return AndroidAutomationStore.hash(mapOf("selection" to listOf(selected.ownerId,selected.treeUri,selected.revision),
            "policy" to policyHash(state),"policyEpoch" to state.optLong("dailyReportPolicyEpoch",0),
            "approvals" to kr.callagent.drive.StrictJson.parse((state.optJSONObject("nativeSyntheticApprovals") ?: JSONObject()).toString().toByteArray())))
    }
    private fun policyHash(state:JSONObject)=AndroidAutomationRuntime.policyHash(state)
    fun capture(context:Context):Binding? {
        val session=GoogleConnectionSession.get(context);val localOwner=owner(context)
        val native=session.previouslyVerifiedScope()
        if(session.ownerBinding()!=localOwner || localOwner.isNotBlank() && native?.owner!=localOwner)return null
        val fence=selectionFence(context,SecureSettings(context).snapshot()) ?: return null
        return Binding(localOwner,session.epoch(),native?.key.orEmpty(),fence)
    }
    fun binding(input:Data):Binding? {
        val owner=input.getString("local_owner") ?: return null
        val fence=input.getString("selection_fence") ?: return null
        val epoch=input.getLong("auth_epoch",-1)
        val native=input.getString("native_scope") ?: return null
        if(epoch<0 || !fence.matches(Regex("[0-9a-f]{64}")) || owner.isNotBlank() && native.isBlank())return null
        return Binding(owner,epoch,native,fence)
    }
    fun outcome(state:String,reason:String="")=JSONObject().put("state",state).put("reason",reason).put("analysisCompleted",false).put("kakaoSent",false)
    private fun blocked(context:Context,binding:Binding,stopped:()->Boolean):JSONObject? {
        if(stopped())return outcome("LOCAL_WORK_STOPPED")
        val session=GoogleConnectionSession.get(context)
        if(owner(context)!=binding.owner || session.ownerBinding()!=binding.owner || session.epoch()!=binding.epoch ||
            session.previouslyVerifiedScope()?.key.orEmpty()!=binding.nativeScope)return outcome("STALE_NATIVE_SCOPE")
        val state=SecureSettings(context).snapshot()
        if(selectionFence(context,state)!=binding.selectionFence)return outcome("HELD_SOURCE","DISCOVERY_SELECTION_OR_POLICY_CHANGED")
        val selected=selection(context,state) ?: return outcome("HELD_SOURCE","SELECTED_FOLDER_REQUIRED")
        if(runCatching{SafSelectedRecordingSource(context,SecureSettings(context),selected.treeUri).checkSelected()}.isFailure)
            return outcome("HELD_SOURCE","SELECTED_FOLDER_READ_GRANT_REQUIRED")
        return null
    }
    /** Same entry for periodic work and foreground hints. One bounded pass per frozen binding. */
    fun cycle(context:Context,binding:Binding?,stopped:()->Boolean,active:(NativeDiscoverySession?)->Unit={},observed:(DiscoveryPump.Cycle)->Unit={}):JSONObject {
        if(binding==null)return outcome("WAITING_DISCOVERY_BINDING","DISCOVERY_WORK_REARM_REQUIRED")
        blocked(context,binding,stopped)?.let{return it}
        val key=binding.owner+"."+binding.epoch+"."+binding.nativeScope+"."+binding.selectionFence
        if(!running.add(key))return outcome("DISCOVERY_ALREADY_RUNNING")
        try {
            val cancelled={stopped() || blocked(context,binding,{false})!=null}
            if(binding.nativeScope.isNotBlank()) {
                val session=GoogleConnectionSession.get(context)
                val auth=ExistingGoogleSessionRecoveryRuntime.recover(AutomationConnectionBindings.base(context),session,binding.owner,binding.epoch,binding.nativeScope,cancelled)
                blocked(context,binding,stopped)?.let{return it}
                if(!auth.ready)return outcome(auth.state,auth.reason)
            }
            blocked(context,binding,stopped)?.let{return it}
            var scan:DiscoveryPump.Cycle?=null
            val local=open(context){!cancelled()} ?: return outcome("WAITING_LOCAL_SOURCE","SELECTED_FOLDER_REQUIRED")
            local.use { session ->
                active(session)
                try {if(cancelled())session.cancel();scan=session.cycle()}finally{active(null)}
            }
            if(!cancelled())runCatching{kr.callbrief.pipeline.CallTranscriptionWork.enqueue(context)} // new recordings -> transcribe soon
            blocked(context,binding,stopped)?.let{return it}
            val result=if(binding.nativeScope.isBlank())outcome("WAITING_AUTH","NO_PREVIOUS_VERIFIED_ACCOUNT")
                else AndroidAutomationRuntime.reconcileDiscovery(context,binding.owner,binding.epoch,binding.nativeScope,cancelled)
            blocked(context,binding,stopped)?.let{return it}
            if(!cancelled())scan?.let(observed)
            return result
        } finally {active(null);running.remove(key)}
    }
    fun selection(context:Context,state:org.json.JSONObject):Selection? {
        val tree=state.optString("treeUri");if(tree.isBlank())return null
        return Selection(GeneralLocalStore.OWNER+"_"+scope(context).take(20),tree,state.optLong("automaticDiscoverySelectionRevision",1L).coerceAtLeast(1L))
    }
    /** The run fence also applies between audio reads, not only around the complete scan. */
    internal fun guarded(delegate:SelectedRecordingSource,current:()->Boolean)=object:SelectedRecordingSource by delegate {
        private fun permit(){check(current()) { "DISCOVERY_RUN_FENCE_CHANGED" }}
        override fun checkSelected(){permit();delegate.checkSelected()}
        override fun inventory():SourceInventory {permit();return delegate.inventory().also{permit()}}
        override fun stat(document:SourceDocument):SourceDocument {permit();return delegate.stat(document).also{permit()}}
        override fun excluded(document:SourceDocument):Boolean {permit();return delegate.excluded(document)}
        override fun checkReadable(document:SourceDocument){permit();delegate.checkReadable(document)}
        override fun open(document:SourceDocument):java.io.InputStream {
            permit()
            return object:java.io.FilterInputStream(delegate.open(document)) {
                override fun read():Int {permit();return `in`.read()}
                override fun read(b:ByteArray,o:Int,n:Int):Int {permit();return `in`.read(b,o,n)}
                override fun skip(n:Long):Long {permit();return `in`.skip(n)}
            }
        }
    }
    fun open(context:Context,current:()->Boolean={true}):NativeDiscoverySession? {
        val settings=SecureSettings(context);val state=settings.snapshot();val selected=selection(context,state) ?: return null
        val delegate=SafSelectedRecordingSource(context,settings,selected.treeUri)
        // General SAF never acquires a synthetic flag. Only the existing exact approved source may.
        var source:SelectedRecordingSource=delegate;var actualSelection=selected
        if(state.optJSONObject("nativeSyntheticApprovals")?.length()?.let{it>0}==true) {
            val session=GoogleConnectionSession.get(context)
            val approved=runCatching {
                val owner=session.ownerBinding();val epoch=session.epoch()
                delegate.inventory().documents.filter { d ->
                    val row=state.optJSONObject("nativeSyntheticApprovals")?.optJSONObject(SyntheticApprovedSource.approvalKey(selected.treeUri,d))
                    row!=null && row.optBoolean("syntheticCreatorConfirmed") && row.optString("ownerId")==owner && row.optLong("authEpoch",-1)==epoch
                }.singleOrNull()
            }.getOrNull()
            if(approved!=null) {
                source=SyntheticApprovedSource(delegate,approved,session,settings)
                actualSelection=Selection(selected.ownerId+"_approved_"+SyntheticApprovedSource.approvalKey(selected.treeUri,approved).take(12),selected.treeUri,selected.revision)
            }
        }
        source=guarded(source,current)
        val store=hostStore?.invoke(context) ?: GeneralLocalStore(context) // Received protected SQL implementation is mandatory.
        return try {
            NativeDiscoverySession(store,source,actualSelection,settings::snapshot,
                kr.co.ieum.discovery.DiscoveryTypes.Clock { Moment("boot_"+Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT),SystemClock.elapsedRealtime()) },syntheticFixture=source.explicitlySyntheticScope)
        }catch(error:Exception){store.close();throw error}
    }
    fun ensure(context:Context) {
        ScheduledDotProofRuntime.ensure(context)
        val binding=capture(context) ?: return
        val name=TAG+"."+scope(context)
        val request=PeriodicWorkRequestBuilder<AutomaticRecordingDiscoveryWorker>(15,TimeUnit.MINUTES)
            .setInputData(binding.data()).addTag(TAG).build()
        val capture=hostWork
        if(capture!=null)capture(name,request)
        // UPDATE migrates legacy owner-only input; an already running pass retains its old fence.
        else WorkManager.getInstance(context).enqueueUniquePeriodicWork(name,ExistingPeriodicWorkPolicy.UPDATE,request)
    }
}

class AutomaticRecordingDiscoveryWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
    @Volatile private var active:NativeDiscoverySession?=null
    override fun doWork():Result {
        if(isStopped)return Result.failure(workDataOf("automation_state" to "LOCAL_WORK_STOPPED"))
        if(runAttemptCount>=6)return Result.failure(workDataOf("automation_state" to "DISCOVERY_CHECK_RETRY_LIMIT"))
        return try {
            val base=AutomationConnectionBindings.base(applicationContext)
            val selected=AccountStorageContext.selected(base)
            val automation=AutomaticRecordingDiscovery.cycle(selected,AutomaticRecordingDiscovery.binding(inputData),{isStopped},{active=it})
            val state=automation.getString("state")
            val output=workDataOf("automation_state" to state,"automation_reason" to automation.getString("reason"))
            if(state=="RETRY_EXISTING_AUTH")Result.retry()
            else if(state in setOf("LOCAL_WORK_STOPPED","STALE_NATIVE_SCOPE","WAITING_DISCOVERY_BINDING","WAITING_USER_REAUTH","WAITING_GOOGLE_ENVIRONMENT","HELD_SOURCE"))Result.failure(output)
            else Result.success(output) // A bounded check, never analysis or Kakao success.
        }catch(_:Exception){if(isStopped)Result.failure(workDataOf("automation_state" to "LOCAL_WORK_STOPPED"))else Result.retry()}
    }
    override fun onStopped(){active?.cancel();super.onStopped()}
}

/** Foreground change hints + timed metadata rescan; closes only this run, not canonical history. */
internal class AutomaticDiscoveryWatch(private val context:Context,private val updated:(DiscoveryPump.Cycle?)->Unit):AutoCloseable {
    private val closed=AtomicBoolean();private val queued=AtomicBoolean();private val worker=Executors.newSingleThreadScheduledExecutor()
    @Volatile private var active:NativeDiscoverySession?=null
    private val binding=AutomaticRecordingDiscovery.capture(context)
    private var authFailures=0
    private val tree=SecureSettings(context).snapshot().optString("treeUri")
    private val observer=object:ContentObserver(null){override fun onChange(selfChange:Boolean){request()}}
    init {
        if(tree.isNotBlank())context.contentResolver.registerContentObserver(Uri.parse(tree),true,observer)
        worker.scheduleWithFixedDelay(::request,0,20,TimeUnit.SECONDS)
    }
    fun request(){if(closed.get()||!queued.compareAndSet(false,true))return
        try{worker.execute {
            try {
                if(authFailures<6) {
                    val state=AutomaticRecordingDiscovery.cycle(context,binding,{closed.get()},{active=it}){if(!closed.get())updated(it)}
                    if(state.optString("state")=="RETRY_EXISTING_AUTH")authFailures++
                }
            }catch(_:Exception){if(!closed.get())updated(null)}finally{queued.set(false)}
        }}catch(error:RejectedExecutionException){queued.set(false);if(!closed.get())throw error}
    }
    override fun close(){if(!closed.compareAndSet(false,true))return
        try{active?.cancel();if(tree.isNotBlank())context.contentResolver.unregisterContentObserver(observer)}finally{worker.shutdownNow()}
    }
}
