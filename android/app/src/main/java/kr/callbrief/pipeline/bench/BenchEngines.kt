package kr.callbrief.pipeline.bench

import android.content.Context
import android.os.Build
import kr.callbrief.AndroidWhisperEngine
import kr.callbrief.SttDecode
import kr.callbrief.pipeline.CallTranscript
import kr.callbrief.pipeline.CallTranscriptionRunner
import kr.callbrief.pipeline.PipelineAudioMath
import kr.callbrief.pipeline.PipelineModelManager
import kr.callbrief.pipeline.WhisperCallTranscriber
import org.json.JSONObject
import java.io.File

/** r18-bench: one candidate engine for the length of one call. Segment times are relative to the piece. */
internal interface BenchEngine:AutoCloseable {
    /** One 16 kHz mono piece of the decoder (the same pieces the app's own run gets). */
    fun transcribe(pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment>
    /** Ends a piece under way at once (another job needs the phone). */
    fun cancel() {}
}

/** (가) and (나): the app's pinned whisper.cpp runtime (v1.7.6) with [model] — the app's q5_0 for (가), the bench's q8_0 for (나).
 * Same decoding as the app (greedy, no prompt, Korean). The runtime is shared with the app's run, so the bench cancels as
 * soon as that run wants the phone ([BenchWork]'s watcher). */
internal class WhisperBenchEngine(context:Context,private val model:File,private val expectedBytes:Long,threads:Int,stopped:()->Boolean):BenchEngine {
    private val engine=AndroidWhisperEngine(context)
    private val session=engine.openVerifiedModel(model,{ model.isFile && model.length()==expectedBytes },threads,stopped)
    override fun transcribe(pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> {
        val raw=session.transcribe(pcm,"ko",CallTranscriptionRunner.NATIVE_DEADLINE_SECONDS,"",SttDecode.DEFAULT,stopped)
        return WhisperCallTranscriber.segmentsOf(AndroidWhisperEngine.decodeOutput(raw))
    }
    override fun cancel()=engine.cancel()
    override fun close()=session.close()
}

/** (다): SenseVoice-Small int8 through sherpa-onnx 2.0.1 (official AAR's arm64 libraries, pinned in assets/local-stt/sherpa-pin.json),
 * language ko, inverse text normalization on (numbers as digits), each piece cut by Silero VAD into speech parts (at most 20 s)
 * that are decoded one by one. The native libraries are checked and loaded only here, i.e. only while the bench runs (C). */
internal class SenseVoiceBenchEngine(context:Context,model:File,tokens:File,vadModel:File,threads:Int):BenchEngine {
    private val recognizer:com.k2fsa.sherpa.onnx.OfflineRecognizer
    private val vad:com.k2fsa.sherpa.onnx.Vad
    init {
        SherpaRuntime.load(context)
        val cfg=com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            featConfig=com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate=PipelineAudioMath.TARGET_RATE,featureDim=80),
            modelConfig=com.k2fsa.sherpa.onnx.OfflineModelConfig(
                senseVoice=com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(model=model.canonicalPath,language="ko",useInverseTextNormalization=true),
                tokens=tokens.canonicalPath,numThreads=threads.coerceIn(1,8),debug=false,provider="cpu"))
        recognizer=com.k2fsa.sherpa.onnx.OfflineRecognizer(null,cfg)
        vad=com.k2fsa.sherpa.onnx.Vad(null,com.k2fsa.sherpa.onnx.VadModelConfig(
            sileroVadModelConfig=com.k2fsa.sherpa.onnx.SileroVadModelConfig(model=vadModel.canonicalPath,threshold=0.5f,minSilenceDuration=0.5f,
                minSpeechDuration=0.25f,windowSize=VAD_WINDOW,maxSpeechDuration=20f),
            sampleRate=PipelineAudioMath.TARGET_RATE,numThreads=1,provider="cpu",debug=false))
    }
    override fun transcribe(pcm:FloatArray,stopped:()->Boolean):List<CallTranscript.Segment> {
        vad.reset()
        val parts=ArrayList<Pair<Long,FloatArray>>()
        fun drain() { while(!vad.empty()) { val s=vad.front();parts+=s.start to s.samples;vad.pop() } }
        var i=0
        while(i<pcm.size) { val end=minOf(pcm.size,i+VAD_WINDOW);vad.acceptWaveform(pcm.copyOfRange(i,end));drain();i=end }
        vad.flush();drain()
        val out=ArrayList<CallTranscript.Segment>()
        for((start,samples) in parts) {
            check(!stopped()) { "BENCH_STOPPED" }
            val stream=recognizer.createStream()
            try {
                stream.acceptWaveform(samples,PipelineAudioMath.TARGET_RATE)
                recognizer.decode(stream)
                val text=recognizer.getResult(stream).text.trim()
                val startMs=start*1000/PipelineAudioMath.TARGET_RATE;val endMs=startMs+samples.size*1000L/PipelineAudioMath.TARGET_RATE
                if(text.isNotEmpty()) out+=CallTranscript.Segment(startMs,endMs,text)
            } finally { stream.release() }
        }
        return out
    }
    override fun close() { runCatching { vad.release() };runCatching { recognizer.release() } }
    companion object { const val VAD_WINDOW=512 }
}

/** The sherpa-onnx native libraries of this APK (arm64-v8a only), checked against the pins the build wrote and loaded once. */
internal object SherpaRuntime {
    const val PIN="local-stt/sherpa-pin.json"
    /** Load order: the runtime first, then the JNI library that needs it. */
    val LIBRARIES=listOf("libonnxruntime.so","libsherpa-onnx-jni.so")
    @Volatile private var loaded=false
    fun available(context:Context):Boolean = runCatching { pins(context)!=null }.getOrDefault(false)
    /** {file: {sha256, bytes}} for this device's ABI, or null (no pin file: a build without the libraries, or another ABI). */
    fun pins(context:Context):JSONObject? = runCatching {
        val pin=JSONObject(context.assets.open(PIN).bufferedReader().use { it.readText() })
        val native=pin.getJSONObject("native")
        Build.SUPPORTED_ABIS.firstOrNull { native.has(it) }?.let { native.getJSONObject(it) }
    }.getOrNull()
    fun load(context:Context)=synchronized(this) {
        if(loaded) return
        val pins=pins(context) ?: error("BENCH_C_NO_RUNTIME")
        val dir=File(context.applicationInfo.nativeLibraryDir)
        for(name in LIBRARIES) {
            val f=File(dir,name);val p=pins.optJSONObject(name) ?: error("BENCH_C_NO_RUNTIME")
            check(f.isFile && f.length()==p.optLong("bytes") && PipelineModelManager.sha256(f)==p.optString("sha256")) { "BENCH_C_RUNTIME_PIN" }
        }
        for(name in LIBRARIES) System.load(File(dir,name).canonicalPath)
        loaded=true
    }
}
