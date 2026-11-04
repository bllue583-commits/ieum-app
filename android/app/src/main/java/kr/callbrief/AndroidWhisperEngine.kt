package kr.callbrief

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** JNI entry points (libieum_whisper.so), internal, with no intent/JSON mechanism for asserting execution. */
internal object AndroidWhisperNative {
    /** Loads the model once; the returned handle owns it, the cancel flag and the deadline of the running chunk. */
    external fun open(model:String,threads:Int):Long
    external fun cancel(handle:Long)
    external fun close(handle:Long)
    external fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int):ByteArray
}
/** Test seam for the five JNI calls (the real ones need the .so). */
internal interface WhisperNativeApi {
    fun open(model:String,threads:Int):Long
    fun cancel(handle:Long)
    fun close(handle:Long)
    fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int):ByteArray
}
internal object RealWhisperNative:WhisperNativeApi {
    override fun open(model:String,threads:Int)=AndroidWhisperNative.open(model,threads)
    override fun cancel(handle:Long)=AndroidWhisperNative.cancel(handle)
    override fun close(handle:Long)=AndroidWhisperNative.close(handle)
    override fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int)=AndroidWhisperNative.transcribeChunk(handle,pcm,language,seconds)
}
/** [dropped] counts segments that were left out because they were malformed (never their text). */
internal data class AndroidWhisperOutput(val raw:ByteArray,val text:String,val segments:JSONArray,val dropped:Int=0)

/** Pinned APK native runtime (assets/local-stt/runtime-pin.json). The model file is owned and verified by the caller.
 * No microphone, network or account access. */
internal class AndroidWhisperEngine(private val context:Context,private val api:WhisperNativeApi=RealWhisperNative,private val loadNative:Boolean=true) {
    private val pin by lazy { JSONObject(context.assets.open("local-stt/runtime-pin.json").bufferedReader().use{it.readText()}) }
    private val abi by lazy { Build.SUPPORTED_ABIS.firstOrNull{pin.getJSONObject("native").has(it)} ?: error("ANDROID_STT_ABI_UNSUPPORTED") }
    private val runtimeSha by lazy { pin.getJSONObject("native").getJSONObject(abi).getString("sha256") }
    private val library=File(context.applicationInfo.nativeLibraryDir,"libieum_whisper.so")
    private val active=AtomicLong(0)
    private fun hash(file:File)=file.inputStream().use{input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString(""){"%02x".format(it)}}
    /** Cancels the open model handle (the chunk running on it ends at once; later chunks refuse to start). */
    fun cancel(){active.get().takeIf{it!=0L}?.let{api.cancel(it)}}
    /** Checks and loads the pinned native runtime once per process. */
    private fun loadRuntime()=synchronized(LOAD_LOCK) {
        check(library.isFile&&library.length()==pin.getJSONObject("native").getJSONObject(abi).getLong("bytes")&&hash(library)==runtimeSha){"ANDROID_STT_RUNTIME_PIN"}
        check(loadedSha==null||loadedSha==runtimeSha){"ANDROID_STT_RUNTIME_CHANGED"}
        // The arm64 runtime is built for armv8.2-a+dotprod+fp16 (cpp/CMakeLists.txt): refuse it on a CPU without those, before it can die with SIGILL.
        if(loadedSha==null&&abi=="arm64-v8a")check(missingArmFeatures(runCatching{File("/proc/cpuinfo").readText()}.getOrDefault("")).isEmpty()){"ANDROID_STT_CPU_UNSUPPORTED"}
        if(loadedSha==null){System.load(library.canonicalPath);loadedSha=runtimeSha}
    }
    /** One loaded model for one recording. [transcribe] per piece, [close] always (normal end, error, stop). */
    inner class Session internal constructor(private val handle:Long,private val model:File,private val stillVerified:()->Boolean):AutoCloseable {
        @Volatile private var closed=false
        fun transcribe(pcm:FloatArray,language:String,timeoutSeconds:Int,stopped:()->Boolean):ByteArray=synchronized(EXECUTION_LOCK) {
            check(language in setOf("ko","en","auto")){"ANDROID_STT_LANGUAGE"}
            check(!closed){"ANDROID_STT_STOPPED"};check(!stopped()){"ANDROID_STT_STOPPED"};check(stillVerified()&&model.isFile){"PIPELINE_STT_MODEL_UNVERIFIED"}
            val raw=api.transcribeChunk(handle,pcm,language,timeoutSeconds.coerceIn(30,3600))
            check(!stopped()){"ANDROID_STT_STOPPED"};check(stillVerified()){"PIPELINE_STT_MODEL_UNVERIFIED"};raw
        }
        override fun close() { if(closed)return;closed=true;active.compareAndSet(handle,0);api.close(handle) }
    }
    /** A caller-verified model file (size+SHA-256 checked by its owner), read from disk once. The caller must close the session. */
    fun openVerifiedModel(model:File,stillVerified:()->Boolean,threads:Int,stopped:()->Boolean):Session {
        check(!stopped()){"ANDROID_STT_STOPPED"};check(stillVerified()&&model.isFile){"PIPELINE_STT_MODEL_UNVERIFIED"};if(loadNative)loadRuntime()
        val handle=api.open(model.canonicalPath,threads.coerceIn(1,8));active.set(handle)
        return Session(handle,model,stillVerified)
    }
    /** One piece with its own model load (settings screen, device tests). */
    fun transcribeVerifiedModel(model:File,stillVerified:()->Boolean,pcm:FloatArray,language:String,threads:Int,timeoutSeconds:Int,stopped:()->Boolean):ByteArray =
        openVerifiedModel(model,stillVerified,threads,stopped).use { it.transcribe(pcm,language,timeoutSeconds,stopped) }
    companion object {
        private val LOAD_LOCK=Any();private val EXECUTION_LOCK=Any();private var loadedSha:String?=null
        /** CPU features the arm64 runtime needs (dotprod = asimddp, fp16 vector = asimdhp), missing on any core of [cpuinfo].
         * No "Features" line (unreadable cpuinfo) counts as missing all of them. */
        val ARM_FEATURES=listOf("asimddp","asimdhp")
        fun missingArmFeatures(cpuinfo:String):List<String> {
            val cores=cpuinfo.lineSequence().filter{it.trimStart().startsWith("Features")}.map{it.substringAfter(':').trim().split(Regex("\\s+")).toSet()}.toList()
            return ARM_FEATURES.filter{f->cores.isEmpty()||cores.any{f !in it}}
        }
        /** Same segment limit and output size as the native side (ieum_whisper.cpp). */
        const val MAX_SEGMENTS=2048
        const val MAX_OUTPUT_BYTES=1048576
        /** A malformed segment is dropped on its own; the rest of the piece is kept. An empty result is silence. */
        fun decodeOutput(raw:ByteArray):AndroidWhisperOutput {
            check(raw.size in 1..MAX_OUTPUT_BYTES){"ANDROID_STT_OUTPUT_LIMIT"};val a=JSONObject(String(raw,Charsets.UTF_8)).getJSONArray("transcription");check(a.length()<=MAX_SEGMENTS){"ANDROID_STT_SEGMENTS"}
            val segments=JSONArray();val text=StringBuilder();var last=0L;var dropped=0
            for(i in 0 until a.length()){
                val ok=runCatching {
                    val s=a.getJSONObject(i);val offset=s.getJSONObject("offsets");val start=offset.getLong("from");val end=offset.getLong("to");val value=s.getString("text")
                    if(start>=last&&end>start&&value.isNotBlank()&&text.length+value.length<=131072){last=end;text.append(value);segments.put(JSONObject().put("startMs",start).put("endMs",end).put("text",value));true} else false
                }.getOrDefault(false)
                if(!ok)dropped++
            }
            return AndroidWhisperOutput(raw.clone(),text.toString(),segments,dropped)
        }
    }
}
