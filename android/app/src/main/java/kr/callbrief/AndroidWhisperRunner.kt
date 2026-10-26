package kr.callbrief

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import kr.callagent.drive.DriveRelay
import kr.callagent.drive.StrictJson
import kr.callagent.drive.WireV0Codec
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** JNI entry points are internal, with no intent/JSON mechanism for asserting execution. */
internal object AndroidWhisperNative {
    external fun begin():Long
    external fun cancel(id:Long)
    external fun release(id:Long)
    external fun transcribe(id:Long,model:String,pcm:FloatArray,language:String):ByteArray
}
internal data class AndroidWhisperOutput(val raw:ByteArray,val text:String,val segments:JSONArray)

/** Pinned APK native code + bundled public model. No microphone, network or account access. */
internal class AndroidWhisperEngine(private val context:Context) {
    val pin=JSONObject(context.assets.open("local-stt/runtime-pin.json").bufferedReader().use{it.readText()})
    val abi=Build.SUPPORTED_ABIS.firstOrNull{pin.getJSONObject("native").has(it)} ?: error("ANDROID_STT_ABI_UNSUPPORTED")
    val runtimeSha=pin.getJSONObject("native").getJSONObject(abi).getString("sha256")
    val modelSha=pin.getJSONObject("model").getString("sha256")
    private val library=File(context.applicationInfo.nativeLibraryDir,"libieum_whisper.so")
    private val model=File(context.noBackupFilesDir,"android-local-stt/ggml-tiny-q5_1.bin")
    private val active=java.util.concurrent.atomic.AtomicLong(0)
    var calls=0;private set
    private fun hash(file:File)=file.inputStream().use{input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString(""){"%02x".format(it)}}
    fun verify() {
        check(library.isFile&&library.length()==pin.getJSONObject("native").getJSONObject(abi).getLong("bytes")&&hash(library)==runtimeSha){"ANDROID_STT_RUNTIME_PIN"}
        check(model.isFile&&model.length()==pin.getJSONObject("model").getLong("bytes")&&hash(model)==modelSha){"ANDROID_STT_MODEL_PIN"}
    }
    fun prepare()=synchronized(LOAD_LOCK) {
        check(library.isFile&&hash(library)==runtimeSha){"ANDROID_STT_RUNTIME_PIN"}
        if(!model.exists()) {
            model.parentFile!!.mkdirs();val atomic=AtomicFile(model);val out=atomic.startWrite()
            try { context.assets.open("local-stt/ggml-tiny-q5_1.bin").use{it.copyTo(out)};atomic.finishWrite(out) }
            catch(e:Exception){atomic.failWrite(out);throw IllegalStateException("ANDROID_STT_MODEL_STORE")}
        }
        verify()
        check(loadedSha==null||loadedSha==runtimeSha){"ANDROID_STT_RUNTIME_CHANGED"}
        if(loadedSha==null){System.load(library.canonicalPath);loadedSha=runtimeSha}
    }
    fun cancel(){active.get().takeIf{it!=0L}?.let{AndroidWhisperNative.cancel(it)}}
    fun transcribe(audio:ByteArray,stopped:()->Boolean):AndroidWhisperOutput=synchronized(EXECUTION_LOCK) {
        check(!stopped()){"ANDROID_STT_STOPPED"};prepare();val pcm=decodePcm(audio);val id=AndroidWhisperNative.begin();active.set(id)
        try {
            check(!stopped()){"ANDROID_STT_STOPPED"};calls++
            val raw=AndroidWhisperNative.transcribe(id,model.canonicalPath,pcm,"auto")
            check(!stopped()){"ANDROID_STT_STOPPED"};verify();decodeOutput(raw)
        }finally{active.set(0);AndroidWhisperNative.release(id);pcm.fill(0f)}
    }
    companion object {
        private val LOAD_LOCK=Any();private val EXECUTION_LOCK=Any();private var loadedSha:String?=null
        fun decodePcm(bytes:ByteArray):FloatArray {
            check(bytes.size in 44..10485760){"ANDROID_STT_WAV_SIZE"}
            val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(String(bytes,0,4,Charsets.US_ASCII)=="RIFF"&&String(bytes,8,4,Charsets.US_ASCII)=="WAVE"&&b.getInt(4).toLong()+8==bytes.size.toLong()){"ANDROID_STT_WAV_REQUIRED"}
            var pos=12;var data=-1;var length=0;var format=false
            while(pos+8<=bytes.size){val n=b.getInt(pos+4);check(n>=0&&pos.toLong()+8+n<=bytes.size){"ANDROID_STT_WAV_CHUNK"};when(String(bytes,pos,4,Charsets.US_ASCII)){
                "fmt "->{check(!format&&n>=16&&b.getShort(pos+8).toInt()==1&&b.getShort(pos+10).toInt()==1&&b.getInt(pos+12)==16000&&b.getShort(pos+22).toInt()==16){"ANDROID_STT_PCM16_MONO_16K"};format=true}
                "data"->{check(data<0){"ANDROID_STT_WAV_DATA"};data=pos+8;length=n}
            };pos+=8+n+(n%2)}
            check(pos==bytes.size&&format&&data>=0&&length>=3200&&length%2==0){"ANDROID_STT_WAV_DATA"}
            return FloatArray(length/2){b.getShort(data+it*2)/32768f}
        }
        fun decodeOutput(raw:ByteArray):AndroidWhisperOutput {
            check(raw.size in 1..262144){"ANDROID_STT_OUTPUT_LIMIT"};val a=JSONObject(String(raw,Charsets.UTF_8)).getJSONArray("transcription");check(a.length() in 1..256){"ANDROID_STT_SEGMENTS"}
            val segments=JSONArray();val text=StringBuilder();var last=0L
            for(i in 0 until a.length()){val s=a.getJSONObject(i);val offset=s.getJSONObject("offsets");val start=offset.getLong("from");val end=offset.getLong("to");val value=s.getString("text");check(start>=last&&end>start&&value.isNotBlank()){"ANDROID_STT_SEGMENT_INVALID"};last=end;text.append(value);segments.put(JSONObject().put("startMs",start).put("endMs",end).put("text",value))}
            check(text.isNotBlank()&&text.length<=131072){"ANDROID_STT_TEXT_SIZE"};return AndroidWhisperOutput(raw.clone(),text.toString(),segments)
        }
    }
}

/** Own protected journal only. Workflow/history writes remain owned by the existing accept gate. */
internal class AndroidWhisperRunner(private val context:Context,private val engine:AndroidWhisperEngine,
    private val current:()->Boolean,private val stop:AtomicBoolean,private val now:()->Long=System::currentTimeMillis):LocalRecordingRunner,AutoCloseable {
    private val db=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private val lifetime=Any()
    private var closed=false
    init { db.requireProtectedPersistence();db.transaction{
        db.execute("CREATE TABLE IF NOT EXISTS android_local_stt_jobs(id TEXT PRIMARY KEY,ticket_sha TEXT NOT NULL,state TEXT NOT NULL,receipt TEXT NOT NULL,result TEXT,witness TEXT)")
        db.execute("CREATE TRIGGER IF NOT EXISTS android_stt_ticket_immutable BEFORE UPDATE OF ticket_sha,receipt ON android_local_stt_jobs BEGIN SELECT RAISE(ABORT,'ANDROID_STT_TICKET_IMMUTABLE');END")
        db.execute("CREATE TRIGGER IF NOT EXISTS android_stt_result_immutable BEFORE UPDATE OF result,witness ON android_local_stt_jobs WHEN OLD.result IS NOT NULL BEGIN SELECT RAISE(ABORT,'ANDROID_STT_RESULT_IMMUTABLE');END");null
    } }
    private fun sha(s:String)=DriveRelay.sha256(s.toByteArray())
    private fun canonical(x:JSONObject)=StrictJson.stringify(StrictJson.parse(x.toString().toByteArray()))
    private fun row(id:String)=db.query("SELECT * FROM android_local_stt_jobs WHERE id=?",id).singleOrNull()
    private fun permitted(){check(current()&&!stop.get()){"ANDROID_STT_SCOPE_OR_STOP"}}
    private fun ticket(h:String,b:JSONObject)=sha(h+"\n"+canonical(b))
    private fun request(handoff:String):JSONObject {permitted();val r=JSONObject(JSONObject(handoff).getString("frozenRequestJson"));WireV0Codec.validateRequest(JSONObject(handoff).getString("frozenRequestJson").toByteArray());val c=JSONObject(r.getString("contextJson"));check(c.optBoolean("synthetic")&&c.optBoolean("syntheticCreatorConfirmed")&&c.getLong("leaseExpiresMs")>now()){"ANDROID_STT_SYNTHETIC_LEASE"};return r}
    override fun submit(key:String,handoff:String,business:JSONObject):AutomationDispatch {
        val r=request(handoff);val id=sha(handoff);val digest=ticket(handoff,business);val receipt=sha("ieum-android-stt-receipt-v1\n"+digest)
        val claim=db.transaction {
            val old=row(id)
            if(old!=null){check(old["ticket_sha"]==digest&&old["receipt"]==receipt){"ANDROID_STT_TICKET_CHANGED"};false}
            else {db.execute("INSERT INTO android_local_stt_jobs VALUES(?,?,'RUNNING',?,NULL,NULL)",id,digest,receipt);true}
        }
        if(!claim)return reconcile(key,handoff,business)
        var audio:ByteArray?=null
        try {
            val approved=engine.pin.getJSONObject("publicAudio");val name=approved.keys().asSequence().singleOrNull{approved.getString(it)==r.getString("audioSha")} ?: error("ANDROID_STT_PUBLIC_AUDIO_REQUIRED")
            permitted();audio=context.assets.open("local-stt/"+name).use{it.readBytes()};check(audio.size.toLong()==r.getLong("audioBytes")&&DriveRelay.sha256(audio)==r.getString("audioSha")){"ANDROID_STT_AUDIO_BINDING"}
            val output=engine.transcribe(audio){stop.get()||!current()};permitted();request(handoff)
            val analysis=extract(output.text,business);val binding=JSONObject();listOf("jobId","ownerBinding","sourceId","sourceVersion","audioSha","baseRevision","userRevision","lockRevision","taskRevision","archiveRevision","leaseEpoch","resultFileId","requestDigest").forEach{binding.put(it,r.get(it))}
            val frozen=JSONObject(handoff).getString("frozenRequestJson");val businessSha=sha(canonical(business));val rawSha=DriveRelay.sha256(output.raw)
            val result=JSONObject().put("schema","ieum.local-recording-producer-result.v1").put("kind","LOCAL_RECORDING_PROPOSAL").put("requestSha",sha(frozen)).put("businessContextSha",businessSha).put("binding",binding).put("revision",1)
                .put("stt",JSONObject().put("provider","WHISPER_CPP_LOCAL").put("runtimeSha",engine.runtimeSha).put("modelSha",engine.modelSha).put("text",output.text).put("textSha",sha(output.text)).put("segments",output.segments))
                .put("analysis",analysis).put("execution",JSONObject().put("kind","CURRENT_LOCAL_PROCESS").put("rawOutputSha",rawSha).put("exitCode",0)).put("canonicalWriteAllowed",false).put("applied",false).put("dotInvoked",false)
            result.put("resultDigest",sha("ieum-local-recording-producer-v1\n"+canonical(result)))
            val witness=JSONObject().put("audioSha",r.getString("audioSha")).put("requestSha",sha(frozen)).put("businessSha",businessSha).put("runtimeSha",engine.runtimeSha).put("modelSha",engine.modelSha).put("rawOutput",java.util.Base64.getEncoder().encodeToString(output.raw)).put("exitCode",0)
            db.transaction{permitted();request(handoff);check(row(id)?.get("state")=="RUNNING"){"ANDROID_STT_NOT_CURRENT"};db.execute("UPDATE android_local_stt_jobs SET state='RESULT_READY',result=?,witness=? WHERE id=?",canonical(result),canonical(witness),id);null}
            return AutomationDispatch("ACCEPTED",receipt,"ANDROID_LOCAL_STT_DURABLE_RESULT")
        }catch(e:Exception){db.execute("UPDATE android_local_stt_jobs SET state=? WHERE id=? AND state='RUNNING'",if(stop.get())"CANCELLED" else "HELD",id);throw e}
        finally{audio?.fill(0)}
    }
    override fun reconcile(key:String,handoff:String,business:JSONObject):AutomationDispatch {
        request(handoff);val x=row(sha(handoff)) ?: return AutomationDispatch("UNKNOWN",reason="ANDROID_LOCAL_STT_NOT_ENROLLED")
        check(x["ticket_sha"]==ticket(handoff,business)){"ANDROID_STT_TICKET_CHANGED"}
        return if(x["state"]=="RESULT_READY")AutomationDispatch("ACCEPTED",x["receipt"] as String,"ANDROID_LOCAL_STT_EXISTING_RESULT") else AutomationDispatch("UNKNOWN",reason="ANDROID_LOCAL_STT_REVIEW_REQUIRED")
    }
    override fun receive(key:String,receipt:String,handoff:String,business:JSONObject):LocalRecordingRun? {
        request(handoff);engine.verify();val x=row(sha(handoff)) ?: return null
        check(x["ticket_sha"]==ticket(handoff,business)&&x["receipt"]==receipt){"ANDROID_STT_RECEIPT_CHANGED"};if(x["state"]!="RESULT_READY")return null
        val w=JSONObject(x["witness"] as String);permitted()
        return LocalRecordingRun((x["result"] as String).toByteArray(),LocalExecutionObservation(LocalExecutionKind.TRUSTED_LOCAL_REPLAY,w.getString("audioSha"),w.getString("requestSha"),w.getString("businessSha"),w.getString("runtimeSha"),w.getString("modelSha"),java.util.Base64.getDecoder().decode(w.getString("rawOutput")),0))
    }
    // A closed handle can still stop native work; it cannot acknowledge a new durable write.
    fun cancel(){stop.set(true);engine.cancel();synchronized(lifetime){if(!closed)db.execute("UPDATE android_local_stt_jobs SET state='CANCELLED' WHERE state='RUNNING'")}}
    override fun close()=synchronized(lifetime){if(!closed){closed=true;db.close()}}
    companion object {
        fun extract(text:String,business:JSONObject):JSONObject {
            val sentences=Regex("[^.!?。！？\\n]+[.!?。！？]?").findAll(text).map{it.value.trim()}.filter{it.isNotBlank()}.toList()
            fun limit(x:String,n:Int):String {val cp=x.codePoints().toArray();return String(cp,0,minOf(cp.size,n))}
            val summary=limit(sentences.firstOrNull()?:text.trim(),300);check(summary.isNotBlank()&&text.contains(summary)){"ANDROID_STT_SUMMARY_QUOTE"}
            val prior=business.getString("proposedSummary");val cumulative=(if(prior.isEmpty())"" else prior+"\n")+summary;check(cumulative.length<=5000){"ANDROID_STT_CUMULATIVE_LIMIT"}
            val tasks=JSONArray();sentences.firstOrNull{Regex("확인|검토|review|please",RegexOption.IGNORE_CASE).containsMatchIn(it)}?.let{sentence->val quote=limit(sentence,180);val start=text.codePointCount(0,text.indexOf(quote));tasks.put(JSONObject().put("taskId",JSONObject.NULL).put("title",quote).put("assignee","확인 필요").put("due",JSONObject.NULL).put("status","needs_confirmation").put("quote",quote).put("start",start).put("end",start+quote.codePointCount(0,quote.length)))}
            return JSONObject().put("policy","IEUM_EXTRACTIVE_SAFE_DERIVED_V1").put("summary",summary).put("cumulativeSummary",cumulative).put("tasks",tasks)
        }
    }
}
