package kr.callbrief.pipeline.bench

import kr.callbrief.pipeline.PipelineModelHttp
import kr.callbrief.pipeline.PipelineModelManager
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** r18-bench: the bench's own model files, pinned by size and SHA-256 from the official releases (docs/ASSETS.md), under
 * no-backup storage in their own folder. Fetched only while the bench is on, on the charger and on Wi-Fi, with at least
 * [MIN_FREE_BYTES] free; resumed from a part file; deleted when the bench ends or is switched off. The app's own model
 * (PipelineModelManager, turbo q5_0) is never touched here. */
internal class BenchModels(private val dir:File,private val http:PipelineModelHttp,private val freeBytes:()->Long) {
    data class Spec(val name:String,val bytes:Long,val sha256:String,val url:String)
    enum class Result { READY,RETRY,NO_SPACE,FAILED }

    fun file(s:Spec)=File(dir,s.name)
    private fun part(s:Spec)=File(dir,s.name+".part")
    private fun marker(s:Spec)=File(dir,s.name+".verified")
    private fun markerValue(f:File,s:Spec)="${s.sha256}:${f.length()}:${f.lastModified()}"

    /** The verified file, or null (a stale marker is checked again once). */
    fun ready(s:Spec):File? {
        val f=file(s)
        if(!f.isFile || f.length()!=s.bytes) return null
        val m=marker(s)
        if(m.isFile && runCatching { m.readText() }.getOrNull()==markerValue(f,s)) return f
        if(PipelineModelManager.sha256(f)!=s.sha256) return null
        m.writeText(markerValue(f,s));return f
    }
    fun allReady(specs:List<Spec>)=specs.all { ready(it)!=null }

    /** Download or resume [s]; [stopped] ends it between blocks (the part file is kept). */
    fun fetch(s:Spec,stopped:()->Boolean):Result {
        if(ready(s)!=null) return Result.READY
        dir.mkdirs()
        var have=part(s).takeIf { it.isFile }?.length() ?: 0L
        if(have>s.bytes) { part(s).delete();have=0 }
        if(freeBytes()-(s.bytes-have)<MIN_FREE_BYTES) return Result.NO_SPACE
        try {
            if(have<s.bytes) http.get(s.url,have).use { r ->
                val append=when {
                    have>0 && r.code==206 -> {
                        val start=r.contentRange?.let { Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                        if(start!=have) { part(s).delete();return Result.RETRY };true }
                    r.code==200 -> false
                    r.code==416 && have>0 -> { part(s).delete();return Result.RETRY }
                    r.code in 500..599 || r.code==408 || r.code==429 -> return Result.RETRY
                    else -> return Result.FAILED
                }
                RandomAccessFile(part(s),"rw").use { out ->
                    if(!append) { out.setLength(0);have=0 }
                    out.seek(have);val buf=ByteArray(64*1024);var blocks=0
                    while(true) {
                        if(stopped()) return Result.RETRY
                        // Free space looked at again on the way (something else may fill the phone meanwhile).
                        if(++blocks%SPACE_CHECK_BLOCKS==0 && freeBytes()-(s.bytes-have)<MIN_FREE_BYTES) return Result.NO_SPACE
                        val n=r.body.read(buf);if(n<0) break
                        if(have+n>s.bytes) { part(s).delete();return Result.FAILED }
                        out.write(buf,0,n);have+=n
                    }
                    out.fd.sync()
                }
            }
        } catch(e:IOException) { return Result.RETRY }
        if(part(s).length()<s.bytes) return Result.RETRY
        if(PipelineModelManager.sha256(part(s))!=s.sha256) { part(s).delete();return Result.FAILED }
        if(!part(s).renameTo(file(s))) return Result.FAILED
        marker(s).writeText(markerValue(file(s),s))
        return Result.READY
    }

    /** Everything the bench fetched (models, part files, markers). */
    fun deleteAll():Int { val n=dir.listFiles()?.count { it.delete() } ?: 0;dir.delete();return n }
    fun bytesOnDisk():Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        /** Below this much free space nothing is fetched (the brief's 2 GB, counted after the file would be written). */
        const val MIN_FREE_BYTES=2L*1024*1024*1024
        const val DIR="bench-models"
        /** 64 KiB blocks between two looks at the free space (16 MiB). */
        const val SPACE_CHECK_BLOCKS=256
        /** Hugging Face files by commit, not by branch: what the pins were checked against cannot move under them. */
        const val HF_WHISPER="https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"
        const val HF_SENSE_VOICE="https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
        /** (나): whisper.cpp large-v3-turbo q8_0, the same publisher and file family as the app's q5_0 (huggingface ggerganov/whisper.cpp). */
        val TURBO_Q8=Spec("ggml-large-v3-turbo-q8_0.bin",874188075L,"317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1",
            "$HF_WHISPER/ggml-large-v3-turbo-q8_0.bin")
        /** (다): SenseVoice-Small int8 as converted by the sherpa-onnx authors (2024-07-17, the FunAudioLLM original; the 2025-09-09
         * build is a Cantonese fine-tune), and its tokens. */
        val SENSE_VOICE=Spec("sense-voice-int8-2024-07-17.onnx",239233841L,"c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            "$HF_SENSE_VOICE/model.int8.onnx")
        val SENSE_VOICE_TOKENS=Spec("sense-voice-tokens-2024-07-17.txt",315894L,"f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            "$HF_SENSE_VOICE/tokens.txt")
        /** Silero VAD as sherpa-onnx publishes it (release asr-models). */
        val SILERO_VAD=Spec("silero_vad.onnx",643854L,"9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx")
        /** What each candidate needs fetched ((가) uses the app's own verified model). */
        fun specs(candidate:String):List<Spec> = when(candidate) { "b" -> listOf(TURBO_Q8);"c" -> listOf(SENSE_VOICE,SENSE_VOICE_TOKENS,SILERO_VAD);else -> emptyList() }
    }
}
