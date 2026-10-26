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
    private fun prepare() {
        check(!stop.get()){"ANDROID_STT_STOPPED"};engine.prepare()
        settings.update{it.put("treeUri",source.rootUri)}
        GeneralLocalStore(scoped).use { s ->
            if(s.setupState()=="EMPTY")s.initializeEmpty()
            if(s.snapshot().length()==0)s.importSelected(source)
            val rows=s.snapshot()
            for(i in 0 until rows.length()){val row=rows.getJSONObject(i);if(row.isNull("original_started_at_ms"))s.confirmTime(row.getString("file_id"),row,1790848800000L+i*60000)}
        }
        RecordingWorkflowStore(scoped).use { s ->
            val rows=GeneralLocalStore.catalog(scoped).listFiles();var selected:String?=null
            for(i in 0 until rows.length()){val id=rows.getJSONObject(i).getString("file_id");val row=s.snapshot(id)
                selected=if(row.isNull("case_id"))s.confirmWork(id,row,"공개 합성 담당","공개 합성 회사","공개 음성 시험","",selected) else row.getString("case_id")
            }
        }
        for(d in source.inventory().documents)SyntheticApprovedSource.approve(settings,session,source,d,true)
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
    fun cancel(){stop.set(true);activeRunner?.cancel();engine.cancel()}
    override fun close(){activeRunner=null}
}
