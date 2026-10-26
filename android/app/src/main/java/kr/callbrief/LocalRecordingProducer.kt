package kr.callbrief

import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson
import kr.callagent.drive.WireV0Codec
import org.json.JSONArray
import org.json.JSONObject

/** Internal runner SPI. A JSON file/Intent cannot install a runner or create this authority.
 * The Mac host bridge is a test/local implementation, not an Android execution attestation. */
internal interface LocalRecordingRunner {
    fun submit(key:String,handoff:String,business:JSONObject):AutomationDispatch
    fun reconcile(key:String,handoff:String,business:JSONObject):AutomationDispatch
    fun receive(key:String,receipt:String,handoff:String,business:JSONObject):LocalRecordingRun?
}
internal enum class LocalExecutionKind { CURRENT_LOCAL_PROCESS, TRUSTED_LOCAL_REPLAY, SYNTHETIC_FIXTURE, IMPORTED_PRIOR_RUN }
internal class LocalExecutionObservation(val kind:LocalExecutionKind,val audioSha:String,val requestSha:String,
    val businessSha:String,val runtimeSha:String,val modelSha:String,rawOutput:ByteArray,val exitCode:Int) {
    private val raw=rawOutput.clone()
    fun rawOutput()=raw.clone()
    override fun toString()="LocalExecutionObservation[withheld]"
}
internal class LocalRecordingRun(payload:ByteArray,val observation:LocalExecutionObservation) {
    private val bytes=payload.clone()
    fun payload()=bytes.clone()
    override fun toString()="LocalRecordingRun[withheld]"
}
internal class LocalRecordingTrust(val runtimeSha:String,val modelSha:String,approvedPublicAudio:Set<String>,val current:()->Boolean) {
    val audioSha256=approvedPublicAudio.toSet()
    init {require(listOf(runtimeSha,modelSha).all{it.matches(Regex("[0-9a-f]{64}"))} && audioSha256.isNotEmpty() && audioSha256.size<=10 && audioSha256.all{it.matches(Regex("[0-9a-f]{64}"))})}
}

/** Same coordinator/accept path as other providers. No SQL, user command, Google/paid HTTP,
 * v0/RO1/V2 rewrite, credential issuance, native receipt fabrication or automatic registration. */
internal class LocalRecordingAutomationProvider(private val store:AndroidAutomationStore,
    private val session:GoogleConnectionSession,private val runner:LocalRecordingRunner?,private val trust:LocalRecordingTrust?,private val now:()->Long=System::currentTimeMillis):AutomationProvider {
    override fun gate()=when {
        runner==null||trust==null -> AutomationGate("UNCONFIGURED","LOCAL_RECORDING_RUNNER_NOT_CONFIGURED")
        DriveALocalScope.current(session)==null||!trust.current() -> AutomationGate("UNAPPROVED","LOCAL_RECORDING_AUTHORITY_NOT_CURRENT")
        else -> AutomationGate("READY","EXACT_PUBLIC_LOCAL_RECORDING_RUNNER")
    }
    private fun checked(key:String,handoff:String):JSONObject {
        check(gate().state=="READY"&&store.allowed(key)){"LOCAL_RECORDING_SOURCE_HELD"}
        val h=JSONObject(handoff);val scope=checkNotNull(DriveALocalScope.current(session))
        DriveACandidateContract.validate(handoff,h.getString("localRequestId"),scope)
        val r=JSONObject(h.getString("frozenRequestJson"));WireV0Codec.validateRequest(h.getString("frozenRequestJson").toByteArray())
        check(JSONObject(r.getString("contextJson")).getLong("leaseExpiresMs")>now()) {"LOCAL_RECORDING_LEASE_EXPIRED"}
        check(r.getString("audioSha") in checkNotNull(trust).audioSha256){"LOCAL_RECORDING_PUBLIC_AUDIO_REQUIRED"}
        return r
    }
    override fun dispatch(key:String,frozenHandoff:String):AutomationDispatch {
        checked(key,frozenHandoff);return checkNotNull(runner).submit(key,frozenHandoff,store.frozenBusinessContext(key))
    }
    override fun reconcile(key:String,frozenHandoff:String):AutomationDispatch {
        checked(key,frozenHandoff);return checkNotNull(runner).reconcile(key,frozenHandoff,store.frozenBusinessContext(key))
    }
    override fun receive(key:String,receipt:String,frozenHandoff:String):AutomationObservation {
        val request=checked(key,frozenHandoff);val business=store.frozenBusinessContext(key)
        val packet=checkNotNull(runner).receive(key,receipt,frozenHandoff,business)
            ?: return AutomationObservation.Waiting("LOCAL_RECORDING_RESULT_PENDING")
        val result=LocalRecordingResultConsumer.read(packet,frozenHandoff,business,request,checkNotNull(trust))
        checked(key,frozenHandoff) // Source/authority may change while the runner is waiting.
        return result
    }
}

/** Separate local profile. Provider names and observations remain truthful; HTTP-only trust
 * paths are deliberately not called. Deterministic extraction is the sole automatic policy. */
internal object LocalRecordingResultConsumer {
    private const val SCHEMA="ieum.local-recording-producer-result.v1"
    private const val POLICY="IEUM_EXTRACTIVE_SAFE_DERIVED_V1"
    private val binding=listOf("jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision","leaseEpoch","resultFileId","requestDigest")
    private fun objectValue(x:Any?)=StrictJson.`object`(x)
    private fun same(a:Any?,b:Any?)=StrictJson.bytes(a).contentEquals(StrictJson.bytes(b))
    private fun sha(x:String)=DriveRelay.sha256(x.toByteArray(Charsets.UTF_8))
    fun read(packet:LocalRecordingRun,handoff:String,business:JSONObject,request:JSONObject,trust:LocalRecordingTrust):AutomationObservation.Result {
        val bytes=packet.payload();check(bytes.size in 1..524288){"LOCAL_RECORDING_RESULT_SIZE"}
        val x=objectValue(StrictJson.parseReadOnlyEnvelope(bytes))
        check(x.keys==setOf("schema","kind","requestSha","businessContextSha","binding","revision","stt","analysis","execution","canonicalWriteAllowed","applied","dotInvoked","resultDigest")){"LOCAL_RECORDING_RESULT_SCHEMA"}
        check(x["schema"]==SCHEMA&&x["kind"]=="LOCAL_RECORDING_PROPOSAL"&&x["revision"]==1L&&listOf("canonicalWriteAllowed","applied","dotInvoked").all{x[it]==false}){"LOCAL_RECORDING_RESULT_SCHEMA"}
        val unsigned=x.filterKeys{it!="resultDigest"};val digest=sha("ieum-local-recording-producer-v1\n"+StrictJson.stringify(unsigned))
        check(x["resultDigest"]==digest){"LOCAL_RECORDING_RESULT_DIGEST"}
        val frozen=JSONObject(handoff).getString("frozenRequestJson");val r=objectValue(StrictJson.parse(frozen.toByteArray()))
        check(x["requestSha"]==sha(frozen)&&same(x["binding"],r.filterKeys{it in binding})&&same(r,StrictJson.parse(request.toString().toByteArray()))){"LOCAL_RECORDING_REQUEST_BINDING"}
        val businessSha=DriveRelay.sha256(StrictJson.bytes(StrictJson.parse(business.toString().toByteArray())))
        check(x["businessContextSha"]==businessSha){"LOCAL_RECORDING_BUSINESS_BINDING"}
        val stt=objectValue(x["stt"]);check(stt.keys==setOf("provider","runtimeSha","modelSha","text","textSha","segments")){"LOCAL_RECORDING_STT_SCHEMA"}
        val text=stt["text"] as String;check(text.isNotBlank()&&text.length<=131072&&stt["textSha"]==sha(text)){"LOCAL_RECORDING_TRANSCRIPT_HASH"}
        val a=objectValue(x["analysis"]);check(a.keys==setOf("policy","summary","cumulativeSummary","tasks")&&a["policy"]==POLICY){"LOCAL_RECORDING_ANALYSIS_SCHEMA"}
        val execution=objectValue(x["execution"]);check(execution.keys==setOf("kind","rawOutputSha","exitCode")){"LOCAL_RECORDING_EXECUTION_SCHEMA"}
        val observed=packet.observation;val raw=observed.rawOutput();check(raw.size in 1..262144){"LOCAL_RECORDING_RAW_OUTPUT_SIZE"}
        check(observed.audioSha==r["audioSha"]&&observed.requestSha==x["requestSha"]&&observed.businessSha==businessSha&&observed.exitCode==0&&execution["exitCode"]==0L&&execution["rawOutputSha"]==DriveRelay.sha256(raw)) {"LOCAL_RECORDING_OBSERVATION_BINDING"}
        check(stt["runtimeSha"]==observed.runtimeSha&&stt["modelSha"]==observed.modelSha){"LOCAL_RECORDING_RUNTIME_BINDING"}
        val current=observed.kind in setOf(LocalExecutionKind.CURRENT_LOCAL_PROCESS,LocalExecutionKind.TRUSTED_LOCAL_REPLAY)
        check(execution["kind"]==(if(current)"CURRENT_LOCAL_PROCESS" else observed.kind.name)){"LOCAL_RECORDING_EXECUTION_KIND"}
        if(current)check(stt["provider"]=="WHISPER_CPP_LOCAL"&&observed.runtimeSha==trust.runtimeSha&&observed.modelSha==trust.modelSha){"LOCAL_RECORDING_UNTRUSTED_RUNTIME"}
        val rawSegments=JSONObject(String(raw,Charsets.UTF_8)).getJSONArray("transcription")
        val segments=stt["segments"] as List<*>;check(segments.isNotEmpty()&&segments.size<=256&&rawSegments.length()==segments.size){"LOCAL_RECORDING_SEGMENTS"}
        var end=0L;val joined=StringBuilder()
        segments.forEachIndexed { i,value->val s=objectValue(value);val actual=rawSegments.getJSONObject(i)
            check(s.keys==setOf("startMs","endMs","text")&&s["startMs"] is Long&&s["endMs"] is Long&&(s["startMs"] as Long)>=end&&(s["endMs"] as Long)>(s["startMs"] as Long)&&s["text"]==actual.getString("text")&&s["startMs"]==actual.getJSONObject("offsets").getLong("from")&&s["endMs"]==actual.getJSONObject("offsets").getLong("to")){"LOCAL_RECORDING_SEGMENT_BINDING"}
            end=s["endMs"] as Long;joined.append(s["text"] as String)
        };check(joined.toString()==text){"LOCAL_RECORDING_RAW_TRANSCRIPT_CHANGED"}
        val analysis=AutomationAnalysis(1,digest,String(bytes,Charsets.UTF_8),text,a["summary"] as String,a["cumulativeSummary"] as String,JSONArray(StrictJson.stringify(a["tasks"])),frozen)
        for(value in a["tasks"] as List<*>)check(objectValue(value).keys==setOf("taskId","title","assignee","due","status","quote","start","end")){"LOCAL_RECORDING_TASK_SCHEMA"}
        val semantic=text.contains(analysis.summary)&&analysis.cumulativeSummary==(business.getString("proposedSummary").let{if(it.isEmpty())"" else it+"\n"})+analysis.summary&&
            (0 until analysis.tasks.length()).all { i->val t=analysis.tasks.getJSONObject(i);t.isNull("taskId")&&t.getString("title")==t.getString("quote")&&t.getString("assignee")=="확인 필요"&&t.isNull("due")&&t.getString("status")=="needs_confirmation" }
        val context=JSONObject(r["contextJson"] as String)
        val privacy=context.optBoolean("synthetic")&&context.optBoolean("syntheticCreatorConfirmed")&&r["audioSha"] in trust.audioSha256
        check(trust.current()) {"LOCAL_RECORDING_AUTHORITY_NOT_CURRENT"}
        val evidence=sha("ieum-local-recording-observation-v1\n"+digest+"\n"+observed.runtimeSha+"\n"+observed.modelSha+"\n"+DriveRelay.sha256(raw)+"\n"+businessSha)
        return AutomationObservation.Result(analysis,AutomationAssurance("LOCAL_STT_EXTRACTIVE_PUBLIC_SYNTHETIC_V1",true,current,semantic,privacy,current&&semantic&&privacy,evidence))
    }
}
