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
    /** [prompt]: Whisper's initial prompt (F4), "" for none. [options]: [SttDecode.native] (v3.3). */
    external fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int,prompt:String,options:FloatArray):ByteArray
    /** v3.3: tokens [text] makes with the loaded model's tokenizer; -1 when unknown. */
    external fun countTokens(handle:Long,text:String):Int
}
/** Test seam for the JNI calls (the real ones need the .so). */
internal interface WhisperNativeApi {
    fun open(model:String,threads:Int):Long
    fun cancel(handle:Long)
    fun close(handle:Long)
    fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int,prompt:String):ByteArray
    /** v3.3: with decoding options ([SttDecode.native]); a fake without options answers as with the defaults. */
    fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int,prompt:String,options:FloatArray):ByteArray =
        transcribeChunk(handle,pcm,language,seconds,prompt)
    /** v3.3: token count of [text], -1 when unknown (fakes). */
    fun countTokens(handle:Long,text:String):Int = -1
}
internal object RealWhisperNative:WhisperNativeApi {
    override fun open(model:String,threads:Int)=AndroidWhisperNative.open(model,threads)
    override fun cancel(handle:Long)=AndroidWhisperNative.cancel(handle)
    override fun close(handle:Long)=AndroidWhisperNative.close(handle)
    override fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int,prompt:String)=
        AndroidWhisperNative.transcribeChunk(handle,pcm,language,seconds,prompt,SttDecode.DEFAULT.native())
    override fun transcribeChunk(handle:Long,pcm:FloatArray,language:String,seconds:Int,prompt:String,options:FloatArray)=
        AndroidWhisperNative.transcribeChunk(handle,pcm,language,seconds,prompt,options)
    override fun countTokens(handle:Long,text:String)=AndroidWhisperNative.countTokens(handle,text)
}

/** v3.3 (DESIGN_V4 3.2-5, 8): the decoding switches the golden variants try. The default is today's decoding (greedy,
 * library fallback 0.2 per step, best_of 5, non-speech tokens not suppressed); only a measured variant changes it. */
internal data class SttDecode(
    /** Temperature step of the fallback; below 0 = the library's 0.2 (B1 tries 0.4, or 0 for no fallback). */
    val temperatureInc:Float=-1f,
    /** Candidates at a fallback temperature; below 1 = the library's 5 (B1 tries 2). */
    val bestOf:Int=0,
    /** Beam search width; below 2 = greedy, as now (B4 tries 2 or 3). */
    val beamSize:Int=0,
    /** B5: suppress non-speech tokens. */
    val suppressNonSpeech:Boolean=false,
) {
    fun native()=floatArrayOf(temperatureInc,bestOf.toFloat(),beamSize.toFloat(),if(suppressNonSpeech) 1f else 0f)
    companion object { val DEFAULT=SttDecode() }
}

/** [dropped] counts segments that were left out because they were malformed or blank (never their text). [repaired]
 * (v3.3, A8) counts segments whose times went backwards or had no length and were set right (start=last, end=start+1).
 * [timings]: the piece's encode/decode ms and fallback counts, when the runtime reported them (numbers only). */
internal data class AndroidWhisperOutput(val raw:ByteArray,val text:String,val segments:JSONArray,val dropped:Int=0,val repaired:Int=0,
    val timings:JSONObject?=null)

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
        fun transcribe(pcm:FloatArray,language:String,timeoutSeconds:Int,prompt:String=defaultPrompt(),stopped:()->Boolean):ByteArray=
            transcribe(pcm,language,timeoutSeconds,prompt,SttDecode.DEFAULT,stopped)
        /** v3.3: with decoding switches (golden variants); [SttDecode.DEFAULT] is the call above. */
        fun transcribe(pcm:FloatArray,language:String,timeoutSeconds:Int,prompt:String,decode:SttDecode,stopped:()->Boolean):ByteArray=synchronized(EXECUTION_LOCK) {
            check(language in setOf("ko","en","auto")){"ANDROID_STT_LANGUAGE"};check(prompt.length<=MAX_PROMPT_CHARS){"ANDROID_STT_PROMPT"}
            check(!closed){"ANDROID_STT_STOPPED"};check(!stopped()){"ANDROID_STT_STOPPED"};check(stillVerified()&&model.isFile){"PIPELINE_STT_MODEL_UNVERIFIED"}
            val seconds=timeoutSeconds.coerceIn(30,3600)
            val raw=if(decode==SttDecode.DEFAULT) api.transcribeChunk(handle,pcm,language,seconds,prompt) else api.transcribeChunk(handle,pcm,language,seconds,prompt,decode.native())
            check(!stopped()){"ANDROID_STT_STOPPED"};check(stillVerified()){"PIPELINE_STT_MODEL_UNVERIFIED"};raw
        }
        /** v3.3 (3.2-7): tokens [text] makes for this model; an estimate when the runtime cannot say ([estimateTokens]). */
        fun countTokens(text:String):Int = if(text.isEmpty()) 0 else (if(closed) -1 else runCatching { api.countTokens(handle,text) }.getOrDefault(-1)).takeIf { it>=0 } ?: estimateTokens(text)
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
        openVerifiedModel(model,stillVerified,threads,stopped).use { it.transcribe(pcm,language,timeoutSeconds,stopped=stopped) }
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
        /** F4 initial prompt (site vocabulary). Off: a prompt can make Whisper repeat itself or write the listed words where
         * they were not said. v3.3 (DESIGN_V4 3.2-7, B3): switched on only if the real-recording golden measurement shows
         * company and title hits going up while CER gets no more than 2 points worse ([GoldenScore]); the prompt is then
         * the sentence-style one of [SttPrompt.sentence], limited in tokens ([SttPrompt.MAX_TOKENS]), not in letters. */
        const val PROMPT_ENABLED=false
        const val SITE_PROMPT="건설 현장 통화입니다. 견적서, 철근, 레미콘, 타설, 거푸집, 슬래브, 단가, 납품, 발주, 현장 소장."
        /** Same limit as the native side (1024 UTF-8 bytes; Korean is 3 bytes a character). */
        const val MAX_PROMPT_CHARS=300
        fun defaultPrompt()=if(PROMPT_ENABLED) SITE_PROMPT else ""
        /** v3.3 (3.2-1): a number whose lowest token probability is under this is "uncertain" (r13-1's number guard). */
        const val UNCERTAIN_P=0.5
        private val NUMBER=Regex("\\d+(?:[.,]\\d+)*")
        /** A rough token count when the runtime cannot tell: two UTF-8 bytes a token (Korean syllables are 3 bytes and
         * take one or two tokens), so the estimate errs on the long side and a prompt stays under its limit. */
        fun estimateTokens(text:String):Int = (text.toByteArray(Charsets.UTF_8).size+1)/2
        /** Numbers in one segment's tokens ([[text,p,t0,t1],...]) whose lowest token probability is under [threshold],
         * as written; a number split over several tokens takes the lowest of them. Unknown probabilities count as sure. */
        fun uncertainNumbers(tokens:JSONArray,threshold:Double=UNCERTAIN_P):List<String> {
            val text=StringBuilder();val owner=ArrayList<Int>();val p=DoubleArray(tokens.length()) { 1.0 }
            for(j in 0 until tokens.length()) {
                val t=tokens.optJSONArray(j) ?: continue
                p[j]=t.optDouble(1,1.0).takeIf { it.isFinite() } ?: 1.0
                for(c in t.optString(0)) { text.append(c);owner+=j }
            }
            return NUMBER.findAll(text).filter { m -> m.range.minOf { p[owner[it]] }<threshold }.map { it.value }.distinct().toList()
        }
        /** A malformed or blank segment is dropped on its own; one whose times run backwards or have no length is set
         * right (A8: start=last end, end=start+1) and counted. The rest of the piece is kept. An empty result is silence.
         * Reads the v3.3 output (tokens, timings) and the older one without them. */
        fun decodeOutput(raw:ByteArray):AndroidWhisperOutput {
            check(raw.size in 1..MAX_OUTPUT_BYTES){"ANDROID_STT_OUTPUT_LIMIT"};val o=JSONObject(String(raw,Charsets.UTF_8));val a=o.getJSONArray("transcription");check(a.length()<=MAX_SEGMENTS){"ANDROID_STT_SEGMENTS"}
            val segments=JSONArray();val text=StringBuilder();var last=0L;var dropped=0;var repaired=0
            for(i in 0 until a.length()){
                val ok=runCatching {
                    val s=a.getJSONObject(i);val offset=s.getJSONObject("offsets");var start=offset.getLong("from");var end=offset.getLong("to");val value=s.getString("text")
                    if(value.isBlank()||text.length+value.length>131072) return@runCatching false
                    var fixed=false
                    if(start<last){start=last;fixed=true}
                    if(end<=start){end=start+1;fixed=true}
                    val unsure=s.optJSONArray("tokens")?.let { runCatching { uncertainNumbers(it) }.getOrDefault(emptyList()) } ?: emptyList()
                    last=end;text.append(value);if(fixed)repaired++
                    segments.put(JSONObject().put("startMs",start).put("endMs",end).put("text",value).apply { if(unsure.isNotEmpty()) put("uncertainNumbers",JSONArray(unsure)) });true
                }.getOrDefault(false)
                if(!ok)dropped++
            }
            val timings=o.optJSONObject("timings")?.let { t -> JSONObject().apply { for(k in TIMING_KEYS) t.optDouble(k).takeIf { it.isFinite() && it>=0 }?.let { put(k,it) } } }?.takeIf { it.length()>0 }
            return AndroidWhisperOutput(raw.clone(),text.toString(),segments,dropped,repaired,timings)
        }
        /** The numbers of the native "timings" block that are kept (ms and fallback counts). */
        val TIMING_KEYS=listOf("encodeMs","decodeMs","batchdMs","promptMs","sampleMs","fallbackP","fallbackH")
    }
}

/** v3.3 (DESIGN_V4 3.2-6, 7): the initial prompt, limited in tokens (whisper_tokenize), not in letters. Off in the app
 * ([AndroidWhisperEngine.PROMPT_ENABLED]); the golden variants use it. */
internal object SttPrompt {
    /** B3: the whole initial prompt. */
    const val MAX_TOKENS=120
    /** B2: what the previous piece said, at most this much, in front of the prompt. */
    const val CONTEXT_TOKENS=40
    const val LEAD="건설 현장 소장과 협력업체의 업무 통화."
    /** Sentence-style prompt (B3): "[lead] 탄탄이엔씨 김호중 과장, 대한철강 박민수 소장." Names in the given order (recent
     * counterparts first, the caller orders them) as long as the whole stays within [maxTokens] tokens by [count]. */
    fun sentence(names:List<String>,maxTokens:Int=MAX_TOKENS,count:(String)->Int,lead:String=LEAD):String {
        var out=if(count(lead)<=maxTokens) lead else return ""
        val taken=ArrayList<String>()
        for(n in names.map { it.replace(Regex("\\s+")," ").trim() }.filter { it.isNotEmpty() }.distinct()) {
            val next="$lead "+(taken+n).joinToString(", ")+"."
            if(count(next)>maxTokens) break
            taken+=n;out=next
        }
        return out
    }
    /** B2: the last one or two sentences of [previous] within [maxTokens]; a single longer sentence keeps its end. */
    fun context(previous:String,maxTokens:Int=CONTEXT_TOKENS,count:(String)->Int):String {
        val sentences=previous.split(Regex("(?<=[.?!。])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        if(sentences.isEmpty()) return ""
        var out=""
        for(s in sentences.takeLast(2).reversed()) { val next=if(out.isEmpty()) s else "$s $out";if(count(next)<=maxTokens) out=next else break }
        if(out.isNotEmpty()) return out
        var tail=sentences.last()
        while(tail.isNotEmpty() && count(tail)>maxTokens) tail=tail.drop(maxOf(1,tail.length/8))
        return tail.trim()
    }
    /** Context first, then the prompt, the whole within [maxTokens]: the prompt is dropped before the context is. */
    fun join(context:String,prompt:String,maxTokens:Int=MAX_TOKENS,count:(String)->Int):String {
        val both=listOf(context,prompt).filter { it.isNotBlank() }.joinToString(" ")
        return if(count(both)<=maxTokens) both else context
    }
}
