package kr.callbrief

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** JNI entry points (libieum_whisper.so), internal, with no intent/JSON mechanism for asserting execution. */
internal object AndroidWhisperNative {
    external fun begin():Long
    external fun cancel(id:Long)
    external fun release(id:Long)
    external fun transcribe(id:Long,model:String,pcm:FloatArray,language:String):ByteArray
    external fun beginTimed(seconds:Int):Long
    external fun transcribeModel(id:Long,model:String,pcm:FloatArray,language:String,threads:Int):ByteArray
}
internal data class AndroidWhisperOutput(val raw:ByteArray,val text:String,val segments:JSONArray)

/** Pinned APK native runtime (assets/local-stt/runtime-pin.json). The model file is owned and verified by the caller.
 * No microphone, network or account access. */
internal class AndroidWhisperEngine(private val context:Context) {
    private val pin=JSONObject(context.assets.open("local-stt/runtime-pin.json").bufferedReader().use{it.readText()})
    private val abi=Build.SUPPORTED_ABIS.firstOrNull{pin.getJSONObject("native").has(it)} ?: error("ANDROID_STT_ABI_UNSUPPORTED")
    private val runtimeSha=pin.getJSONObject("native").getJSONObject(abi).getString("sha256")
    private val library=File(context.applicationInfo.nativeLibraryDir,"libieum_whisper.so")
    private val active=AtomicLong(0)
    private fun hash(file:File)=file.inputStream().use{input->val d=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)};d.digest().joinToString(""){"%02x".format(it)}}
    fun cancel(){active.get().takeIf{it!=0L}?.let{AndroidWhisperNative.cancel(it)}}
    /** Checks and loads the pinned native runtime once per process. */
    private fun loadRuntime()=synchronized(LOAD_LOCK) {
        check(library.isFile&&library.length()==pin.getJSONObject("native").getJSONObject(abi).getLong("bytes")&&hash(library)==runtimeSha){"ANDROID_STT_RUNTIME_PIN"}
        check(loadedSha==null||loadedSha==runtimeSha){"ANDROID_STT_RUNTIME_CHANGED"}
        if(loadedSha==null){System.load(library.canonicalPath);loadedSha=runtimeSha}
    }
    /** A caller-verified model file (size+SHA-256 checked by its owner) and an explicit language.
     * The model must still be verified before and after native work; raw native JSON is returned. */
    fun transcribeVerifiedModel(model:File,stillVerified:()->Boolean,pcm:FloatArray,language:String,threads:Int,timeoutSeconds:Int,stopped:()->Boolean):ByteArray=synchronized(EXECUTION_LOCK) {
        check(language in setOf("ko","en","auto")){"ANDROID_STT_LANGUAGE"}
        check(!stopped()){"ANDROID_STT_STOPPED"};check(stillVerified()&&model.isFile){"PIPELINE_STT_MODEL_UNVERIFIED"};loadRuntime()
        val id=AndroidWhisperNative.beginTimed(timeoutSeconds.coerceIn(30,3600));active.set(id)
        try {
            check(!stopped()){"ANDROID_STT_STOPPED"}
            val raw=AndroidWhisperNative.transcribeModel(id,model.canonicalPath,pcm,language,threads.coerceIn(1,8))
            check(!stopped()){"ANDROID_STT_STOPPED"};check(stillVerified()){"PIPELINE_STT_MODEL_UNVERIFIED"};raw
        }finally{active.set(0);AndroidWhisperNative.release(id)}
    }
    companion object {
        private val LOAD_LOCK=Any();private val EXECUTION_LOCK=Any();private var loadedSha:String?=null
        fun decodeOutput(raw:ByteArray):AndroidWhisperOutput {
            check(raw.size in 1..262144){"ANDROID_STT_OUTPUT_LIMIT"};val a=JSONObject(String(raw,Charsets.UTF_8)).getJSONArray("transcription");check(a.length() in 1..256){"ANDROID_STT_SEGMENTS"}
            val segments=JSONArray();val text=StringBuilder();var last=0L
            for(i in 0 until a.length()){val s=a.getJSONObject(i);val offset=s.getJSONObject("offsets");val start=offset.getLong("from");val end=offset.getLong("to");val value=s.getString("text");check(start>=last&&end>start&&value.isNotBlank()){"ANDROID_STT_SEGMENT_INVALID"};last=end;text.append(value);segments.put(JSONObject().put("startMs",start).put("endMs",end).put("text",value))}
            check(text.isNotBlank()&&text.length<=131072){"ANDROID_STT_TEXT_SIZE"};return AndroidWhisperOutput(raw.clone(),text.toString(),segments)
        }
    }
}
