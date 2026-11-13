package kr.callbrief.pipeline.bench

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kr.callbrief.SecureSettings
import kr.callbrief.pipeline.BatterySnapshot
import kr.callbrief.pipeline.ClaudeCallAnalyzer
import kr.callbrief.pipeline.NumberGuard
import kr.callbrief.pipeline.PipelineAudioDecoder
import kr.callbrief.pipeline.PipelineAudioMath
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** r18-bench item 9 (user's decision 11/13): recording samples for the workspace's reference transcripts. The one place where
 * a recording leaves the phone, and only while the mailbox's `samples.on` is true: work calls only (the bench's rules), sent
 * one at a time on the charger and on Wi-Fi as assets of the private mailbox's release [TAG] (a release asset, not a commit:
 * a commit would stay in the history for good), each under a random name `s_<8>.<ext>` with a `s_<8>.json` beside it
 * (length, the phone's transcript with numbers masked as the mailbox requests have them, piece and line times, engine).
 * No name, number, file name or path. Deleted 7 days after upload, and all at once (with the release and its tag) when
 * `samples.on` goes false. */
internal object Samples {
    const val KEY="samplesState"
    const val TAG="bench-samples"
    const val KEEP_MS=7*86_400_000L
    const val MAX_TRIES=3
    /** Local keys of recordings already sent once (never sent again), kept up to this many. */
    const val SENT_KEEP=300

    data class Asset(val id:Long,val name:String)
    data class Release(val id:Long,val assets:List<Asset>)
    /** The mailbox's releases (a test fakes it). */
    interface Releases {
        fun find(tag:String):Release?
        fun create(tag:String):Release
        fun upload(releaseId:Long,name:String,contentType:String,bytes:ByteArray):Long
        fun deleteAsset(id:Long)
        fun deleteRelease(id:Long)
        fun deleteTag(tag:String)
    }

    fun parse(text:String?):JSONObject = runCatching { JSONObject(text ?: "") }.getOrNull() ?: JSONObject()
    fun live(s:JSONObject):List<JSONObject> = s.optJSONArray("live")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
    fun sent(s:JSONObject):Set<String> = s.optJSONArray("sent")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
    /** The local key as kept in [KEY]: a hash, so even the sealed settings do not hold the recording id twice. */
    fun hash(key:String)=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun add(s:JSONObject,k:String,n:Long=1) { s.put(k,s.optLong(k,0)+n) }
    private val CODE=Regex("[A-Za-z0-9_]{1,60}")
    fun code(e:Throwable)=e.message?.takeIf { CODE.matches(it) } ?: e.javaClass.simpleName.take(60)

    /** A random asset id of 8 letters and digits (no part of the recording, the call or the time in it). */
    fun randomId(random:java.util.Random=SecureRandom()):String { val a="abcdefghijklmnopqrstuvwxyz0123456789";return "s_"+(1..8).map { a[random.nextInt(a.length)] }.joinToString("") }
    /** The original container's extension from its type (never from the file name, which holds the other side's name). */
    fun extension(mime:String)=when(mime.lowercase().substringBefore(';').trim()) {
        "audio/mp4","audio/m4a","audio/x-m4a","audio/aac-adts" -> "m4a";"audio/aac" -> "aac";"audio/amr","audio/3gpp" -> if(mime.contains("amr")) "amr" else "3gp"
        "audio/wav","audio/x-wav","audio/wave" -> "wav";"audio/mpeg" -> "mp3";"audio/ogg" -> "ogg";"audio/opus" -> "opus";else -> "bin" }

    /** The sidecar `s_<id>.json`: what the workspace needs to compare, nothing that names anyone. */
    fun sidecar(id:String,audioName:String,durationMs:Long,transcript:String,segmentsJson:String,pieces:List<Pair<Long,Long>>,vad:Boolean):JSONObject {
        val lines=transcript.split("\n")
        val times=NumberGuard.Times.parse(segmentsJson)?.takeIf { it.size==lines.size }
        return JSONObject().put("schema","ieum.bench-sample.v1").put("id",id).put("audio",audioName)
            .put("durationS",Math.round(durationMs/100.0)/10.0)
            .put("engine","whisper.cpp-v1.7.6-android-cpu").put("model","ggml-large-v3-turbo-q5_0")
            .put("pieces",JSONArray().apply { pieces.forEach { (at,ms) -> put(JSONObject().put("startMs",at).put("ms",ms)) } }).put("vad",vad)
            .put("lines",JSONArray().apply { lines.forEachIndexed { i,l -> put(JSONObject().put("text",ClaudeCallAnalyzer.maskNumbers(l))
                .put("startS",times?.let { it.startsMs[i]/1000 } ?: JSONObject.NULL).put("endS",times?.let { it.endsMs[i]/1000 } ?: JSONObject.NULL)) } })
    }

    /** Heartbeat time: off -> everything goes (assets, release, tag); on -> assets past [KEEP_MS] go, and the upload is queued.
     * A failed request leaves the state as it is for the next heartbeat. Never throws. */
    fun onHeartbeat(app:Context,cfg:SamplesConfig,now:Long,releases:Releases?=GitHubReleases.make(),queue:(Context)->Unit={ SampleWork.enqueue(it) },
                    cancel:(Context)->Unit={ SampleWork.cancel(it) }) { runCatching {
        val settings=SecureSettings(app)
        val s=parse(settings.snapshot().optString(KEY))
        if(!cfg.on) {
            if(!s.has("live") && !s.has("releaseId")) { if(s.length()>0) cancel(app);return@runCatching }
            cancel(app)
            val r=releases ?: return@runCatching
            sweep(s,r,now,all=true)
            settings.update { it.put(KEY,s.toString()) }
            return@runCatching
        }
        if(releases!=null && live(s).any { now-it.optLong("atMs",now)>=KEEP_MS }) { sweep(s,releases,now,all=false);settings.update { it.put(KEY,s.toString()) } }
        queue(app)
    } }

    /** Deletes the live assets past [KEEP_MS], or with [all] every one of them and then the release and its tag. A failed
     * delete keeps its entry (tried again at the next heartbeat). */
    internal fun sweep(s:JSONObject,r:Releases,now:Long,all:Boolean) {
        val keep=JSONArray()
        for(e in live(s)) {
            if(!all && now-e.optLong("atMs",now)<KEEP_MS) { keep.put(e);continue }
            val ok=runCatching { e.optJSONArray("assets")?.let { a -> for(i in 0 until a.length()) r.deleteAsset(a.getLong(i)) } }
            if(ok.isSuccess) add(s,"deleted") else { keep.put(e);s.put("lastCode",code(ok.exceptionOrNull()!!)) }
        }
        s.put("live",keep).put("atMs",now)
        if(all && keep.length()==0) runCatching {
            (s.optLong("releaseId",0).takeIf { it>0 } ?: r.find(TAG)?.id)?.let { r.deleteRelease(it) }
            r.deleteTag(TAG)
            s.remove("releaseId");s.remove("live");s.put("countOn",0).put("minutesOn",0).put("longOn",0)
        }.onFailure { s.put("lastCode",code(it)) }
    }

    /** Upload the next sample, if any; false when nothing is left (or the plan is full). */
    internal fun uploadNext(s:JSONObject,cfg:SamplesConfig,calls:List<BenchCall>,now:Long,r:Releases,
                            row:(BenchCall)->kr.callbrief.pipeline.CallRecordStore.BenchRow?,audio:(BenchCall)->ByteArray,
                            pieces:(BenchCall,ByteArray)->Pair<List<Pair<Long,Long>>,Boolean>,random:java.util.Random=SecureRandom()):Boolean {
        val countLeft=cfg.calls-s.optInt("countOn",0);val minutesLeft=cfg.maxMinutes-Math.ceil(s.optDouble("minutesOn",0.0)).toInt()
        if(countLeft<=0 || minutesLeft<=0) return false
        val sentHashes=sent(s)
        val longLeft=(2-s.optInt("longOn",0)).coerceAtLeast(0)
        val pick=BenchPick.samples(calls.filter { hash(it.key) !in sentHashes },now-KEEP_MS,emptySet(),1,minutesLeft,longLeft).firstOrNull() ?: return false
        val h=hash(pick.key)
        try {
            val rec=row(pick) ?: error("SAMPLE_NO_TRANSCRIPT")
            val bytes=audio(pick)
            val (bounds,vad)=pieces(pick,bytes)
            val id=randomId(random);val audioName="$id.${extension(pick.mime)}"
            val release=s.optLong("releaseId",0).takeIf { it>0 }?.let { Release(it,emptyList()) } ?: (r.find(TAG) ?: r.create(TAG)).also { s.put("releaseId",it.id) }
            val a1=r.upload(release.id,audioName,pick.mime.ifBlank { "application/octet-stream" },bytes)
            val json=sidecar(id,audioName,pick.durationMs,rec.transcript,rec.segmentsJson,bounds,vad).toString().toByteArray()
            val a2=try { r.upload(release.id,"$id.json","application/json",json) } catch(e:Exception) { runCatching { r.deleteAsset(a1) };throw e }
            val live=s.optJSONArray("live") ?: JSONArray().also { s.put("live",it) }
            live.put(JSONObject().put("id",id).put("assets",JSONArray().put(a1).put(a2)).put("atMs",now).put("min",Math.round(pick.durationMs/6000.0)/10.0))
            markSent(s,h);add(s,"uploaded");add(s,"countOn");if(pick.durationMs>BenchPick.LONG_MS) add(s,"longOn")
            s.put("minutes",Math.round((s.optDouble("minutes",0.0)+pick.durationMs/60000.0)*10)/10.0)
            s.put("minutesOn",Math.round((s.optDouble("minutesOn",0.0)+pick.durationMs/60000.0)*10)/10.0)
            s.remove("lastCode");(s.optJSONObject("tries") ?: JSONObject()).remove(h)
            return true
        } catch(e:Exception) {
            val tries=(s.optJSONObject("tries") ?: JSONObject().also { s.put("tries",it) })
            val n=tries.optInt(h,0)+1;tries.put(h,n)
            add(s,"failed");s.put("lastCode",code(e))
            // At the limit the recording is not tried again (it counts as sent, without anything having gone up).
            if(n>=MAX_TRIES) { markSent(s,h);tries.remove(h) }
            throw e
        }
    }
    private fun markSent(s:JSONObject,h:String) {
        val a=s.optJSONArray("sent") ?: JSONArray()
        val list=(0 until a.length()).map { a.optString(it) }.filter { it!=h }+h
        s.put("sent",JSONArray(list.takeLast(SENT_KEEP)))
    }

    /** The heartbeat's `samples{on,uploaded,minutes,deleted,failed,live,lastCode}`; null while never on. */
    fun heartbeat(snapshot:JSONObject):JSONObject? = runCatching {
        val s=parse(snapshot.optString(KEY));val cfg=Bench.config(snapshot).second
        if(!cfg.on && s.length()==0) return@runCatching null
        JSONObject().put("on",cfg.on).put("uploaded",s.optLong("uploaded",0)).put("minutes",s.optDouble("minutes",0.0))
            .put("deleted",s.optLong("deleted",0)).put("failed",s.optLong("failed",0)).put("live",live(s).size)
            .put("lastCode",s.optString("lastCode").takeIf { s.has("lastCode") && CODE.matches(it) } ?: JSONObject.NULL)
    }.getOrNull()
}

/** The mailbox's releases through the GitHub API, with the token the app already uses for the mailbox and its updates. */
internal class GitHubReleases(private val repo:String,private val token:String,
    private val client:OkHttpClient=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).writeTimeout(120,TimeUnit.SECONDS).build()):Samples.Releases {
    private fun req(url:String)=Request.Builder().url(url).header("Authorization","Bearer $token").header("X-GitHub-Api-Version","2022-11-28").header("Accept","application/vnd.github+json")
    private fun call(r:Request):Pair<Int,String> = client.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }
    private fun ok(code:Int) { if(code !in 200..299) throw if(code>=500 || code==429) IOException("SAMPLE_HTTP_$code") else IllegalStateException("SAMPLE_HTTP_$code") }
    private fun release(o:JSONObject)=Samples.Release(o.getLong("id"),o.optJSONArray("assets")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> Samples.Asset(x.getLong("id"),x.optString("name")) } } } ?: emptyList())
    private val json="application/json".toMediaType()
    override fun find(tag:String):Samples.Release? { val (c,t)=call(req("$API/repos/$repo/releases/tags/$tag").get().build());if(c==404) return null;ok(c);return release(JSONObject(t)) }
    override fun create(tag:String):Samples.Release {
        val body=JSONObject().put("tag_name",tag).put("name","측정 표본 (7일 뒤 지워짐)").put("body","이음 엔진 측정용 녹음 표본. 폰이 7일 뒤 지운다.").put("prerelease",true).put("draft",false)
        val (c,t)=call(req("$API/repos/$repo/releases").post(body.toString().toRequestBody(json)).build());ok(c);return release(JSONObject(t))
    }
    override fun upload(releaseId:Long,name:String,contentType:String,bytes:ByteArray):Long {
        val (c,t)=call(req("$UPLOADS/repos/$repo/releases/$releaseId/assets?name=$name").post(bytes.toRequestBody(contentType.toMediaType())).build());ok(c)
        return JSONObject(t).getLong("id")
    }
    override fun deleteAsset(id:Long) { val (c,_)=call(req("$API/repos/$repo/releases/assets/$id").delete().build());if(c!=404) ok(c) }
    override fun deleteRelease(id:Long) { val (c,_)=call(req("$API/repos/$repo/releases/$id").delete().build());if(c!=404) ok(c) }
    override fun deleteTag(tag:String) { val (c,_)=call(req("$API/repos/$repo/git/refs/tags/$tag").delete().build());if(c!=404 && c!=422) ok(c) }
    companion object {
        const val API="https://api.github.com";const val UPLOADS="https://uploads.github.com"
        fun make():GitHubReleases? = kr.callbrief.BuildConfig.IEUM_INBOX_TOKEN.takeIf { it.isNotBlank() }?.let { GitHubReleases(kr.callbrief.BuildConfig.IEUM_INBOX_REPO,it) }
    }
}

/** Uploads samples one at a time, on the charger and Wi-Fi only, never during a call or a transcription run. */
class SampleWork(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {
        val app=applicationContext
        val settings=SecureSettings(app)
        val cfg=Bench.config(settings.snapshot()).second
        if(!cfg.on) return Result.success()
        val r=GitHubReleases.make() ?: return Result.success()
        val source=BenchSource(app)
        val calls=runCatching { source.calls(System.currentTimeMillis()-Samples.KEEP_MS) }.getOrElse { return Result.retry() }
        while(!isStopped) {
            val b=BatterySnapshot.read(app)
            if(BenchGate.stop(b.isPlugged,b.inCall,0,Bench.transcriptionBusy(app),null)!=null || !BenchWork.unmetered(app)) break
            val s=Samples.parse(settings.snapshot().optString(Samples.KEY))
            val more=try {
                Samples.uploadNext(s,cfg,calls,System.currentTimeMillis(),r,{ c -> source.transcript(c.recordingId) },
                    { c -> BenchAudio.read(app,c).use { input -> ByteArray(input.size.toInt()).also { buf -> var p=0;while(p<buf.size) { val n=input.readAt(p.toLong(),buf,p,buf.size-p);if(n<0) break;p+=n } } } },
                    { c,_ -> pieces(app,c) })
            } catch(e:Exception) { settings.update { it.put(Samples.KEY,s.toString()) };return Result.success() }
            settings.update { it.put(Samples.KEY,s.toString()) }
            if(!more) break
        }
        return Result.success()
    }
    /** The decoder's pieces of the recording (start, length in ms), the same as the app's run cuts. */
    private fun pieces(app:Context,c:BenchCall):Pair<List<Pair<Long,Long>>,Boolean> {
        val out=ArrayList<Pair<Long,Long>>()
        val info=BenchAudio.read(app,c).use { input -> PipelineAudioDecoder().decode(input,c.mime) { _,offset,pcm -> out+=offset to pcm.size*1000L/PipelineAudioMath.TARGET_RATE } }
        return out to info.vadApplied
    }
    companion object {
        const val UNIQUE="ieum.bench.samples"
        fun enqueue(app:Context)=runCatching { WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE,ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SampleWork>().setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED).build()).build()) }
        fun cancel(app:Context)=runCatching { WorkManager.getInstance(app).cancelUniqueWork(UNIQUE) }
    }
}
