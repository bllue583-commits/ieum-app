package kr.callbrief.pipeline

import android.content.Context
import android.os.Build
import kr.callbrief.SecureSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Model states stored under [PipelineSettingsKeys.MODEL_STATE]. */
internal object PipelineModelState {
    const val NOT_DOWNLOADED="NOT_DOWNLOADED"
    const val DOWNLOADING="DOWNLOADING"
    const val READY="READY"
    const val FAILED="FAILED"
    const val UNSUPPORTED="UNSUPPORTED"
}

/** Minimal HTTP seam so tests never touch the network. */
internal interface PipelineModelHttp {
    class Response(val code:Int,val contentRange:String?,val contentLength:Long,val body:InputStream):AutoCloseable {
        override fun close(){runCatching{body.close()}}
    }
    /** GET [url], following redirects; [rangeStart] > 0 sends `Range: bytes=<rangeStart>-`. */
    fun get(url:String,rangeStart:Long):Response
}

internal class PipelineModelOkHttp(private val client:OkHttpClient=OkHttpClient.Builder()
    .followRedirects(true).followSslRedirects(true)
    .connectTimeout(30,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).build()):PipelineModelHttp {
    override fun get(url:String,rangeStart:Long):PipelineModelHttp.Response {
        val request=Request.Builder().url(url).apply{if(rangeStart>0)header("Range","bytes=$rangeStart-")}
            .header("Accept-Encoding","identity").build()
        val response=client.newCall(request).execute()
        val body=response.body
        if(body==null){response.close();error("PIPELINE_MODEL_HTTP_EMPTY")}
        return PipelineModelHttp.Response(response.code,response.header("Content-Range"),body.contentLength(),body.byteStream())
    }
}

/** Pinned public whisper.cpp model, downloaded once to no-backup storage and verified by size and SHA-256.
 * The runtime hash is the APK's pinned native library for this ABI. No audio or account data is involved. */
internal class PipelineModelManager(
    private val context:Context,
    private val settings:SecureSettings=SecureSettings(context),
    private val http:PipelineModelHttp=PipelineModelOkHttp(),
    private val spec:Spec=Spec.DEFAULT,
    private val runtimePin:()->String?={readRuntimeSha(context)},
):PipelineSttModel {
    class Spec(val name:String,val bytes:Long,val sha256:String,val url:String) {
        val fileName get()="$name.bin"
        companion object {
            val DEFAULT=Spec("ggml-large-v3-turbo-q5_0",574041195L,
                "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin")
        }
    }

    override val modelSha256:String get()=spec.sha256
    override val modelName:String get()=spec.name
    override val runtimeSha256:String get()=runtimePin() ?: ""

    /** Device-level (not per account): one copy of the 574 MB model for the whole app. */
    val directory:File get()=File(kr.callbrief.AutomationConnectionBindings.base(context).noBackupFilesDir,"pipeline-stt")
    val modelFile:File get()=File(directory,spec.fileName)
    val partFile:File get()=File(directory,spec.fileName+".part")
    private val markerFile:File get()=File(directory,spec.fileName+".verified")

    fun state():String=runCatching{settings.snapshot().optString(PipelineSettingsKeys.MODEL_STATE,"")}.getOrDefault("")
        .ifBlank{PipelineModelState.NOT_DOWNLOADED}
    private fun setState(value:String)=settings.update{it.put(PipelineSettingsKeys.MODEL_STATE,value)}

    override fun verifiedModelFile():File? {
        synchronized(LOCK) {
            val file=modelFile
            if(!file.isFile||file.length()!=spec.bytes)return null
            if(markerMatches(file))return file
            // Marker missing or stale (file replaced or touched): re-verify once and re-mark.
            if(sha256(file)!=spec.sha256)return null
            writeMarker(file);return file
        }
    }

    private fun markerValue(file:File)="${spec.sha256}:${file.length()}:${file.lastModified()}"
    private fun markerMatches(file:File)=markerFile.isFile&&runCatching{markerFile.readText()}.getOrNull()==markerValue(file)
    private fun writeMarker(file:File){val tmp=File(directory,markerFile.name+".tmp");tmp.writeText(markerValue(file));check(tmp.renameTo(markerFile)){"PIPELINE_MODEL_MARKER"}}

    /** The worker gave up on transient failures; the partial file is kept so a later schedule resumes. */
    fun markFailed()=synchronized(LOCK){if(verifiedModelFile()==null)setState(PipelineModelState.FAILED)}

    /** Result of one [download] attempt. RETRY = transient (network); the worker retries with backoff. */
    enum class Result{READY,RETRY,FAILED,UNSUPPORTED}

    /** Download or resume, verify, and atomically publish. Safe to call repeatedly. */
    fun download(stopped:()->Boolean={false}):Result { synchronized(LOCK) {
        if(runtimePin()==null){setState(PipelineModelState.UNSUPPORTED);return Result.UNSUPPORTED}
        if(verifiedModelFile()!=null){setState(PipelineModelState.READY);return Result.READY}
        directory.mkdirs()
        if(modelFile.exists()){modelFile.delete();markerFile.delete()}
        setState(PipelineModelState.DOWNLOADING)
        try {
            var have=if(partFile.isFile)partFile.length() else 0L
            if(have>spec.bytes){partFile.delete();have=0L}
            if(have<spec.bytes){
                http.get(spec.url,have).use{r->
                    val append=when {
                        have>0&&r.code==206 -> {
                            val start=r.contentRange?.let{Regex("""bytes\s+(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull()}
                            if(start!=have){partFile.delete();return Result.RETRY};true
                        }
                        r.code==200 -> false
                        r.code==416&&have>0 -> {partFile.delete();return Result.RETRY}
                        r.code in 500..599||r.code==408||r.code==429 -> return Result.RETRY
                        else -> error("PIPELINE_MODEL_HTTP_${r.code}")
                    }
                    RandomAccessFile(partFile,"rw").use{out->
                        if(!append){out.setLength(0);have=0L}
                        out.seek(have)
                        val buffer=ByteArray(64*1024)
                        while(true){
                            if(stopped())return Result.RETRY
                            val n=r.body.read(buffer);if(n<0)break
                            check(have+n<=spec.bytes){"PIPELINE_MODEL_SIZE"}
                            out.write(buffer,0,n);have+=n
                        }
                        out.fd.sync()
                    }
                }
            }
            if(partFile.length()<spec.bytes)return Result.RETRY
            if(partFile.length()!=spec.bytes||sha256(partFile)!=spec.sha256){
                partFile.delete();setState(PipelineModelState.FAILED);return Result.FAILED
            }
            check(partFile.renameTo(modelFile)){"PIPELINE_MODEL_RENAME"}
            writeMarker(modelFile)
            setState(PipelineModelState.READY);return Result.READY
        } catch(e:java.io.IOException){
            return Result.RETRY
        } catch(e:IllegalStateException){
            partFile.delete();setState(PipelineModelState.FAILED);return Result.FAILED
        }
    } }

    companion object {
        private val LOCK=Any()
        fun sha256(file:File):String=file.inputStream().use{input->
            val d=MessageDigest.getInstance("SHA-256");val b=ByteArray(1 shl 16)
            while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}
            d.digest().joinToString(""){"%02x".format(it)}
        }
        /** Pinned native runtime SHA-256 for this device's ABI (assets/local-stt/runtime-pin.json), or null. */
        fun readRuntimeSha(context:Context):String? = runCatching {
            val pin=JSONObject(context.assets.open("local-stt/runtime-pin.json").bufferedReader().use{it.readText()})
            val native=pin.getJSONObject("native")
            Build.SUPPORTED_ABIS.firstOrNull{native.has(it)}?.let{native.getJSONObject(it).getString("sha256")}
        }.getOrNull()

    }
}
