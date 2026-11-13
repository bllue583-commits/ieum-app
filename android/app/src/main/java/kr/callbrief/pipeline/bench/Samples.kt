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
import kr.callbrief.pipeline.EmulatorInbox
import kr.callbrief.pipeline.NumberGuard
import kr.callbrief.pipeline.PipelineAudioDecoder
import kr.callbrief.pipeline.PipelineAudioMath
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit

/** r18-bench item 9 (user's decision 11/13): recording samples for the workspace's reference transcripts. The one place where
 * a recording leaves the phone, and only while the mailbox's `samples.on` is true: work calls only (the bench's rules), sent
 * one at a time on the charger and on Wi-Fi as assets of the private mailbox's release [TAG] (a release asset, not a commit:
 * a commit would stay in the history for good). The audio is decoded and written again as 16 kHz mono 16-bit WAV (the whole
 * length, silence kept), so no tag of the original file (which may carry the other side's name or number) goes along. Each
 * goes under a random name `s_<8>.wav` with a `s_<8>.json` beside it (length, the phone's transcript with numbers masked as
 * the mailbox requests have them, piece and line times, engine). No name, number, file name or path.
 *
 * Deleting goes by what the **server** lists, not by the phone's own record (review 11/13, high 1): at every heartbeat the
 * release is looked up by its tag and every asset older than 7 days, every asset the phone has no record of (an orphan of a
 * lost answer, a crash, a failed half upload or lost settings) and every asset of a call that is no longer a work call
 * (high 2: found personal later, excluded, deleted) goes. With `samples.on` false the release is looked up by its tag and
 * emptied, deleted with its tag, and only after the server is seen empty does the phone stop asking (`cleared`). */
internal object Samples {
    const val KEY="samplesState"
    const val TAG="bench-samples"
    const val KEEP_MS=7*86_400_000L
    /** An asset without a record on the phone is left this long (an upload under way has not been recorded yet). */
    const val ORPHAN_GRACE_MS=30*60_000L
    const val MAX_TRIES=3
    /** Local keys of recordings already sent once (never sent again), kept up to this many. */
    const val SENT_KEEP=300
    const val FORMAT="wav"

    data class Asset(val id:Long,val name:String,val createdMs:Long)
    data class Release(val id:Long)
    /** The mailbox's releases (a test fakes it). */
    interface Releases {
        fun find(tag:String):Release?
        fun assets(releaseId:Long):List<Asset>
        fun create(tag:String):Release
        fun upload(releaseId:Long,name:String,contentType:String,file:File):Long
        fun deleteAsset(id:Long)
        fun deleteRelease(id:Long)
        fun deleteTag(tag:String)
    }

    fun parse(text:String?):JSONObject = runCatching { JSONObject(text ?: "") }.getOrNull() ?: JSONObject()
    fun live(s:JSONObject):List<JSONObject> = s.optJSONArray("live")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
    fun sent(s:JSONObject):Set<String> = s.optJSONArray("sent")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
    /** The local key as kept in [KEY]: a hash, so even the sealed settings do not hold the recording id twice. */
    fun hash(key:String)=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun add(s:JSONObject,k:String,n:Long=1) { if(n!=0L) s.put(k,s.optLong(k,0)+n) }
    private val CODE=Regex("[A-Za-z0-9_]{1,60}")
    fun code(e:Throwable)=e.message?.takeIf { CODE.matches(it) } ?: e.javaClass.simpleName.take(60)

    /** A random asset id of 8 letters and digits (no part of the recording, the call or the time in it). */
    fun randomId(random:java.util.Random=SecureRandom()):String { val a="abcdefghijklmnopqrstuvwxyz0123456789";return "s_"+(1..8).map { a[random.nextInt(a.length)] }.joinToString("") }

    /** The sidecar `s_<id>.json`: what the workspace needs to compare, nothing that names anyone. */
    fun sidecar(id:String,audioName:String,durationMs:Long,transcript:String,segmentsJson:String,pieces:List<Pair<Long,Long>>,vad:Boolean):JSONObject {
        val lines=transcript.split("\n")
        val times=NumberGuard.Times.parse(segmentsJson)?.takeIf { it.size==lines.size }
        return JSONObject().put("schema","ieum.bench-sample.v2").put("id",id).put("audio",audioName)
            .put("format",FORMAT).put("sampleRate",PipelineAudioMath.TARGET_RATE).put("channels",1).put("bits",16)
            .put("durationS",Math.round(durationMs/100.0)/10.0)
            .put("engine","whisper.cpp-v1.7.6-android-cpu").put("model","ggml-large-v3-turbo-q5_0")
            .put("pieces",JSONArray().apply { pieces.forEach { (at,ms) -> put(JSONObject().put("startMs",at).put("ms",ms)) } }).put("vad",vad)
            .put("lines",JSONArray().apply { lines.forEachIndexed { i,l -> put(JSONObject().put("text",ClaudeCallAnalyzer.maskNumbers(l))
                .put("startS",times?.let { it.startsMs[i]/1000 } ?: JSONObject.NULL).put("endS",times?.let { it.endsMs[i]/1000 } ?: JSONObject.NULL)) } })
    }

    // ---- deleting, by the server's list ----------------------------------------------------------------------------------
    /** What one sweep did, applied to the stored state by [applySweep] (read again just before the write). */
    data class Sweep(val removedIds:Set<String> =emptySet(),val deleted:Int=0,val orphans:Int=0,val revoked:Int=0,val overdue:Int=0,
                     val deleteCode:String?=null,val cleared:Boolean=false,val ok:Boolean=true)

    /** One look at the release under [TAG]. [off]: everything goes, then the release and its tag. Otherwise assets older than
     * [KEEP_MS] (by the server's created_at, or the record's own time), assets with no record (after [ORPHAN_GRACE_MS]) and
     * assets of calls not in [eligible] (null: not checked) go. Records whose assets are no longer on the server are dropped. */
    fun sweep(live:List<JSONObject>,r:Releases,now:Long,off:Boolean,eligible:Set<String>?):Sweep {
        val rel=try { r.find(TAG) } catch(e:Exception) { return Sweep(deleteCode=code(e),ok=false,overdue=live.count { now-it.optLong("atMs",now)>=KEEP_MS }) }
        if(rel==null) {
            // Nothing up (or the release was deleted by hand): every record is void. Off: a tag left without a release goes too.
            val tag=if(off) runCatching { r.deleteTag(TAG) } else Result.success(Unit)
            return Sweep(removedIds=live.map { it.optString("id") }.toSet(),cleared=off && tag.isSuccess,deleteCode=tag.exceptionOrNull()?.let(::code),ok=tag.isSuccess)
        }
        val assets=try { r.assets(rel.id) } catch(e:Exception) { return Sweep(deleteCode=code(e),ok=false) }
        val byName=HashMap<String,JSONObject>()
        for(e in live) e.optJSONArray("names")?.let { a -> for(i in 0 until a.length()) byName[a.optString(i)]=e }
        var deleted=0;var orphans=0;var overdue=0;var failCode:String?=null
        val gone=HashSet<String>();val revokedIds=HashSet<String>()
        for(a in assets) {
            val e=byName[a.name]
            val expired=now-a.createdMs>=KEEP_MS || (e!=null && now-e.optLong("atMs",now)>=KEEP_MS)
            val orphan=e==null && now-a.createdMs>=ORPHAN_GRACE_MS
            val revoke=e!=null && eligible!=null && e.optString("key") !in eligible
            if(!(off || expired || orphan || revoke)) continue
            try { r.deleteAsset(a.id);deleted++;gone+=a.name;if(orphan && !off && !expired) orphans++;if(revoke && e!=null) revokedIds+=e.optString("id") }
            catch(x:Exception) { failCode=code(x);if(off || expired) overdue++ }
        }
        val onServer=assets.map { it.name }.toSet()-gone
        val removed=live.filter { e -> val n=e.optJSONArray("names");n==null || (0 until n.length()).none { n.optString(it) in onServer } }.map { it.optString("id") }.toSet()
        var cleared=false
        if(off && failCode==null) {
            try { r.deleteRelease(rel.id);r.deleteTag(TAG);cleared=r.find(TAG)==null } catch(x:Exception) { failCode=code(x) }
        }
        return Sweep(removed,deleted,orphans,revokedIds.count { it in removed },overdue,failCode,cleared,failCode==null)
    }
    fun applySweep(s:JSONObject,w:Sweep,now:Long):JSONObject {
        s.put("live",JSONArray(live(s).filter { it.optString("id") !in w.removedIds }))
        add(s,"deleted",w.deleted.toLong());add(s,"orphans",w.orphans.toLong());add(s,"revoked",w.revoked.toLong())
        s.put("overdue",w.overdue).put("sweptMs",now)
        // A failed delete stays visible until a sweep goes through without one (an upload does not clear it).
        if(w.deleteCode!=null) s.put("deleteCode",w.deleteCode) else if(w.ok) s.remove("deleteCode")
        if(w.cleared) { s.put("cleared",true);s.remove("live");s.put("countOn",0).put("minutesOn",0).put("longOn",0) }
        return s
    }

    /** Heartbeat time. Off: nothing at all unless something may be up (a record, or the config naming `samples` while the
     * phone has not yet seen the server empty), then the release is emptied by its tag. On: the server-list sweep, then the
     * upload is queued. A failed request leaves the rest for the next heartbeat. Never throws. */
    fun onHeartbeat(app:Context,cfg:SamplesConfig,now:Long,releases:()->Releases? ={ GitHubReleases.make() },
                    eligible:()->Set<String>? ={ eligibleKeys(app,now) },queue:(Context)->Unit={ SampleWork.enqueue(it) },
                    cancel:(Context)->Unit={ SampleWork.cancel(it) }) { runCatching {
        val settings=SecureSettings(app)
        val s=parse(settings.snapshot().optString(KEY))
        if(!cfg.on) {
            if(s.optBoolean("cleared",false)) return@runCatching
            if(s.length()==0 && !cfg.present) return@runCatching
            cancel(app)
            val r=releases() ?: return@runCatching
            val w=sweep(live(s),r,now,off=true,eligible=null)
            settings.update { it.put(KEY,applySweep(parse(it.optString(KEY)),w,now).toString()) }
            return@runCatching
        }
        if(s.optBoolean("cleared",false)) settings.update { val c=parse(it.optString(KEY));c.remove("cleared");it.put(KEY,c.toString()) }
        releases()?.let { r ->
            val elig=if(live(s).isNotEmpty()) runCatching { eligible() }.getOrNull() else null
            val w=sweep(live(s),r,now,off=false,eligible=elig)
            settings.update { it.put(KEY,applySweep(parse(it.optString(KEY)),w,now).toString()) }
        }
        queue(app)
    } }
    /** Keys (hashed) of the recordings that are work calls now, by the same rules as the upload (the text need not be kept). */
    fun eligibleKeys(app:Context,now:Long):Set<String> = BenchSource(app).calls(now-KEEP_MS-86_400_000L,needText=false).map { hash(it.key) }.toSet()

    // ---- uploading -----------------------------------------------------------------------------------------------------
    /** The next recording to send, or null (none left, or the count or minutes are used up). */
    fun pickNext(s:JSONObject,cfg:SamplesConfig,calls:List<BenchCall>,now:Long):BenchCall? {
        val countLeft=cfg.calls-s.optInt("countOn",0);val minutesLeft=cfg.maxMinutes-Math.ceil(s.optDouble("minutesOn",0.0)).toInt()
        if(countLeft<=0 || minutesLeft<=0) return null
        val sentHashes=sent(s)
        val longLeft=(2-s.optInt("longOn",0)).coerceAtLeast(0)
        return BenchPick.samples(calls.filter { hash(it.key) !in sentHashes },now-KEEP_MS,emptySet(),1,minutesLeft,longLeft).firstOrNull()
    }
    /** Uploads the WAV [audio] and its sidecar; returns the record to keep. A sidecar that fails takes its audio back down
     * (and if that fails too, the next sweep finds the audio as an orphan). */
    fun upload(pick:BenchCall,rec:kr.callbrief.pipeline.CallRecordStore.BenchRow,audio:File,durationMs:Long,pieces:List<Pair<Long,Long>>,vad:Boolean,
               r:Releases,now:Long,tmp:File,random:java.util.Random=SecureRandom()):JSONObject {
        val id=randomId(random);val audioName="$id.$FORMAT";val jsonName="$id.json"
        val release=r.find(TAG) ?: r.create(TAG)
        val a1=r.upload(release.id,audioName,"audio/wav",audio)
        val side=File(tmp,"$id.json")
        try {
            side.writeText(sidecar(id,audioName,durationMs,rec.transcript,rec.segmentsJson,pieces,vad).toString())
            val a2=try { r.upload(release.id,jsonName,"application/json",side) } catch(e:Exception) { runCatching { r.deleteAsset(a1) };throw e }
            return JSONObject().put("id",id).put("names",JSONArray().put(audioName).put(jsonName)).put("assets",JSONArray().put(a1).put(a2))
                .put("atMs",now).put("min",Math.round(pick.durationMs/6000.0)/10.0).put("key",hash(pick.key)).put("long",pick.durationMs>BenchPick.LONG_MS)
        } finally { side.delete() }
    }
    fun applyUpload(s:JSONObject,entry:JSONObject,minutes:Double):JSONObject {
        val live=s.optJSONArray("live") ?: JSONArray().also { s.put("live",it) }
        live.put(entry);markSent(s,entry.optString("key"));add(s,"uploaded");add(s,"countOn");if(entry.optBoolean("long")) add(s,"longOn")
        s.put("minutes",Math.round((s.optDouble("minutes",0.0)+minutes)*10)/10.0).put("minutesOn",Math.round((s.optDouble("minutesOn",0.0)+minutes)*10)/10.0)
        s.remove("lastCode");s.optJSONObject("tries")?.remove(entry.optString("key"))
        return s
    }
    /** A failed try for the recording [h]; at [MAX_TRIES] it is not tried again (counted as sent without anything up). */
    fun applyFailure(s:JSONObject,h:String,code:String):JSONObject {
        val tries=(s.optJSONObject("tries") ?: JSONObject().also { s.put("tries",it) })
        val n=tries.optInt(h,0)+1;tries.put(h,n)
        add(s,"failed");s.put("lastCode",code)
        if(n>=MAX_TRIES) { markSent(s,h);tries.remove(h) }
        return s
    }
    private fun markSent(s:JSONObject,h:String) {
        val a=s.optJSONArray("sent") ?: JSONArray()
        val list=(0 until a.length()).map { a.optString(it) }.filter { it!=h }+h
        s.put("sent",JSONArray(list.takeLast(SENT_KEEP)))
    }

    /** 16 kHz mono 16-bit WAV of the whole recording ([PipelineAudioDecoder.decodeRaw]: silence kept, no source tags). */
    fun writeWav(input:kr.callbrief.pipeline.PipelineAudioInput,out:File):Long {
        var samples=0L
        RandomAccessFile(out,"rw").use { f ->
            f.setLength(0);f.write(ByteArray(44))
            val buf=java.io.ByteArrayOutputStream()
            PipelineAudioDecoder().decodeRaw(input) { _,x,from,to ->
                buf.reset()
                for(i in from until to) { val v=(x[i].coerceIn(-1f,1f)*32767f).toInt();buf.write(v and 0xff);buf.write((v shr 8) and 0xff) }
                f.write(buf.toByteArray());samples+=to-from
            }
            f.seek(0);f.write(wavHeader(samples))
        }
        return samples*1000/PipelineAudioMath.TARGET_RATE
    }
    fun wavHeader(samples:Long,rate:Int=PipelineAudioMath.TARGET_RATE):ByteArray {
        val data=samples*2
        val b=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36+data).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate*2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.toInt())
        return b.array()
    }

    /** The heartbeat's `samples{on,uploaded,minutes,deleted,failed,live,lastCode,overdue,orphans,revoked,deleteCode}`; null while never on. */
    fun heartbeat(snapshot:JSONObject):JSONObject? = runCatching {
        val s=parse(snapshot.optString(KEY));val cfg=Bench.config(snapshot).second
        if(!cfg.on && s.length()==0) return@runCatching null
        fun c(k:String)=s.optString(k).takeIf { s.has(k) && CODE.matches(it) } ?: JSONObject.NULL
        JSONObject().put("on",cfg.on).put("uploaded",s.optLong("uploaded",0)).put("minutes",s.optDouble("minutes",0.0))
            .put("deleted",s.optLong("deleted",0)).put("failed",s.optLong("failed",0)).put("live",live(s).size)
            .put("overdue",s.optLong("overdue",0)).put("orphans",s.optLong("orphans",0)).put("revoked",s.optLong("revoked",0))
            .put("cleared",s.optBoolean("cleared",false)).put("lastCode",c("lastCode")).put("deleteCode",c("deleteCode"))
    }.getOrNull()
}

/** The mailbox's releases through the GitHub API, with the token the app already uses for the mailbox and its updates. */
internal class GitHubReleases(private val repo:String,private val token:String,private val client:OkHttpClient=CLIENT):Samples.Releases {
    private fun req(url:String)=Request.Builder().url(url).header("Authorization","Bearer $token").header("X-GitHub-Api-Version","2022-11-28").header("Accept","application/vnd.github+json")
    private fun call(r:Request):Pair<Int,String> = client.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }
    private fun ok(code:Int) { if(code !in 200..299) throw if(code>=500 || code==429) IOException("SAMPLE_HTTP_$code") else IllegalStateException("SAMPLE_HTTP_$code") }
    private val json="application/json".toMediaType()
    override fun find(tag:String):Samples.Release? { val (c,t)=call(req("$API/repos/$repo/releases/tags/$tag").get().build());if(c==404) return null;ok(c);return Samples.Release(JSONObject(t).getLong("id")) }
    override fun assets(releaseId:Long):List<Samples.Asset> {
        val out=ArrayList<Samples.Asset>()
        for(page in 1..20) {
            val (c,t)=call(req("$API/repos/$repo/releases/$releaseId/assets?per_page=100&page=$page").get().build());ok(c)
            val a=JSONArray(t)
            for(i in 0 until a.length()) a.getJSONObject(i).let { x -> out+=Samples.Asset(x.getLong("id"),x.optString("name"),
                runCatching { Instant.parse(x.optString("created_at")).toEpochMilli() }.getOrDefault(0L)) }
            if(a.length()<100) break
        }
        return out
    }
    override fun create(tag:String):Samples.Release {
        val body=JSONObject().put("tag_name",tag).put("name","측정 표본 (7일 뒤 지워짐)").put("body","이음 엔진 측정용 녹음 표본. 폰이 7일 뒤 지운다.").put("prerelease",true).put("draft",false)
        val (c,t)=call(req("$API/repos/$repo/releases").post(body.toString().toRequestBody(json)).build());ok(c);return Samples.Release(JSONObject(t).getLong("id"))
    }
    override fun upload(releaseId:Long,name:String,contentType:String,file:File):Long {
        val (c,t)=call(req("$UPLOADS/repos/$repo/releases/$releaseId/assets?name=$name").post(file.asRequestBody(contentType.toMediaType())).build());ok(c)
        return JSONObject(t).getLong("id")
    }
    override fun deleteAsset(id:Long) { val (c,_)=call(req("$API/repos/$repo/releases/assets/$id").delete().build());if(c!=404) ok(c) }
    override fun deleteRelease(id:Long) { val (c,_)=call(req("$API/repos/$repo/releases/$id").delete().build());if(c!=404) ok(c) }
    override fun deleteTag(tag:String) { val (c,_)=call(req("$API/repos/$repo/git/refs/tags/$tag").delete().build());if(c!=404 && c!=422) ok(c) }
    companion object {
        const val API="https://api.github.com";const val UPLOADS="https://uploads.github.com"
        /** One client for the process, made only when a request is really sent. */
        private val CLIENT by lazy { OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).writeTimeout(300,TimeUnit.SECONDS).build() }
        /** Null without a token, and on an emulator (CI's runs neither upload nor delete samples, [EmulatorInbox]). */
        fun make(emulator:Boolean=EmulatorInbox.here):GitHubReleases? = if(emulator) null else
            kr.callbrief.BuildConfig.IEUM_INBOX_TOKEN.takeIf { it.isNotBlank() }?.let { GitHubReleases(kr.callbrief.BuildConfig.IEUM_INBOX_REPO,it) }
    }
}

/** Uploads samples one at a time, on the charger and Wi-Fi only, never during a call or a transcription run. */
class SampleWork(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {
        val app=applicationContext
        val settings=SecureSettings(app)
        val cfg=Bench.config(settings.snapshot()).second
        val tmp=File(app.noBackupFilesDir,TMP).also { d -> d.listFiles()?.forEach { it.delete() };d.mkdirs() }
        try {
            if(!cfg.on) return Result.success()
            val r=GitHubReleases.make() ?: return Result.success()
            val source=BenchSource(app)
            val calls=runCatching { source.calls(System.currentTimeMillis()-Samples.KEEP_MS) }.getOrElse { return Result.retry() }
            while(!isStopped) {
                val b=BatterySnapshot.read(app)
                if(BenchGate.stop(b.isPlugged,b.inCall,0,Bench.transcriptionBusy(app),null)!=null || !BenchWork.unmetered(app)) break
                if(!Bench.config(settings.snapshot()).second.on) break
                val now=System.currentTimeMillis()
                val pick=Samples.pickNext(Samples.parse(settings.snapshot().optString(Samples.KEY)),cfg,calls,now) ?: break
                val h=Samples.hash(pick.key)
                val wav=File(tmp,"a.wav")
                try {
                    val rec=source.transcript(pick.recordingId) ?: error("SAMPLE_NO_TRANSCRIPT")
                    val ms=BenchAudio.read(app,pick).use { Samples.writeWav(it,wav) }
                    val bounds=ArrayList<Pair<Long,Long>>()
                    val info=BenchAudio.read(app,pick).use { input -> PipelineAudioDecoder().decode(input,pick.mime) { _,offset,pcm -> bounds+=offset to pcm.size*1000L/PipelineAudioMath.TARGET_RATE } }
                    val entry=Samples.upload(pick,rec,wav,ms,bounds,info.vadApplied,r,now,tmp)
                    // Read again just before the write: the heartbeat may have swept in between.
                    settings.update { it.put(Samples.KEY,Samples.applyUpload(Samples.parse(it.optString(Samples.KEY)),entry,pick.durationMs/60000.0).toString()) }
                } catch(e:Exception) {
                    settings.update { it.put(Samples.KEY,Samples.applyFailure(Samples.parse(it.optString(Samples.KEY)),h,Samples.code(e)).toString()) }
                    return Result.success()
                } finally { wav.delete() }
            }
            return Result.success()
        } finally { tmp.listFiles()?.forEach { it.delete() } }
    }
    companion object {
        const val UNIQUE="ieum.bench.samples"
        const val TMP="bench-samples-tmp"
        fun enqueue(app:Context)=runCatching { WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE,ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SampleWork>().setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED).build()).build()) }
        fun cancel(app:Context)=runCatching { WorkManager.getInstance(app).cancelUniqueWork(UNIQUE) }
    }
}
