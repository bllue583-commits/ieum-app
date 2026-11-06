package kr.callbrief.pipeline.report

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kr.callbrief.SecureSettings
import kr.callbrief.pipeline.CallPipelineStartup
import kr.callbrief.pipeline.CallRecordStore
import kr.callbrief.pipeline.OpenItem
import kr.callbrief.pipeline.PipelineSettingsKeys
import kr.callbrief.pipeline.TaskActions
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * v3.5 (DESIGN_V5 2.1-2): the morning report's way in. The unique work [NAME] runs at 05:30 KST, reads the inbox's
 * `report/<today>.json` (an ETag read: unchanged costs no download) and hands it to [KakaoReport.send]. When it is not there
 * yet (404) it tries again at 05:35, 05:45 and 06:00; at 06:00 the phone sends its own short report (the open to-dos, the
 * home rule) with `report.source="phone"`. Waits for a network (WorkManager), and after 09:00 sends nothing. Each day's
 * chain ends by queueing the next day's 05:30.
 */
object ReportFetch {
    const val NAME="ieum.report.morning"
    /** DESIGN_V5 2.1-2: its own time, apart from the app notice's MORNING_HOUR. */
    const val REPORT_SEND_HOUR=5
    const val REPORT_SEND_MINUTE=30
    /** The tries after 05:30, in minutes: 05:30, 05:35, 05:45, 06:00. */
    val TRIES=listOf(0L,5L,15L,30L)
    /** No KakaoTalk message after this (the report would come in the middle of the morning). */
    val CUTOFF:LocalTime=LocalTime.of(9,0)
    /** Settings: the last report read (JSON text, in the encrypted settings) and its ETag. */
    const val REPORT_BODY="reportBody"
    const val REPORT_ETAG="reportETag"
    private const val TAG="ieum.report"

    /** What one read of the inbox gave. */
    sealed class Fetch {
        data class Found(val report:JSONObject):Fetch()
        object Missing:Fetch()
        data class Failed(val code:String):Fetch()
    }
    /** The inbox reader (GitHub contents API in the app, a fake in tests). [etag]: the one kept from the last 200. */
    interface Source { fun get(path:String,etag:String?):Response }
    /** [code] 200 with [body] and [etag], 304 (unchanged), 404 (not there) or anything else. */
    data class Response(val code:Int,val body:String?,val etag:String?)

    /** Tests replace these. */
    @Volatile internal var sourceFactory:(()->Source?)?=null
    @Volatile internal var clock:()->Long={ System.currentTimeMillis() }
    /** Queues a try ([Plan]); tests record it instead. */
    @Volatile internal var scheduler:((Context,Plan)->Unit)?=null

    // ---- times ----
    private val KST=CallRecordStore.KST
    /** 05:30 KST of [day]. */
    internal fun target(day:LocalDate):Instant=day.atTime(REPORT_SEND_HOUR,REPORT_SEND_MINUTE).atZone(KST).toInstant()
    /** The next 05:30 KST at or after [now] (today's when it has not come yet, else tomorrow's). */
    internal fun nextTarget(now:Instant):Instant {
        val today=now.atZone(KST).toLocalDate()
        return target(today).takeIf { !it.isBefore(now) } ?: target(today.plusDays(1))
    }
    /** One queued try: the day, which try (index in [TRIES]) and when. */
    data class Plan(val day:LocalDate,val attempt:Int,val atMs:Long)
    internal fun plan(day:LocalDate,attempt:Int)=Plan(day,attempt,target(day).plusSeconds(TRIES[attempt]*60).toEpochMilli())
    internal fun tooLate(day:LocalDate,nowMs:Long)=!Instant.ofEpochMilli(nowMs).isBefore(ZonedDateTime.of(day,CUTOFF,KST).toInstant())

    // ---- scheduling ----
    /** App start, boot and the hourly tick (CallPipelineStartup.ensureWork): today's or tomorrow's 05:30 stays queued.
     * KEEP, so a chain already waiting (a retry at 05:45) is left alone. Nothing when the build has no Kakao key. */
    fun ensure(app:Context) {
        if(!KakaoReport.available()) return
        val now=Instant.ofEpochMilli(clock())
        val day=nextTarget(now).atZone(KST).toLocalDate()
        queue(app,plan(day,0),ExistingWorkPolicy.KEEP)
    }
    private fun queue(app:Context,p:Plan,policy:ExistingWorkPolicy) {
        scheduler?.let { it(app,p);return }
        WorkManager.getInstance(app.applicationContext).enqueueUniqueWork(NAME,policy,request(p,clock()))
    }
    internal fun request(p:Plan,nowMs:Long):OneTimeWorkRequest = OneTimeWorkRequestBuilder<ReportMorningWork>()
        .setInitialDelay((p.atMs-nowMs).coerceAtLeast(0),TimeUnit.MILLISECONDS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInputData(workDataOf("day" to p.day.toString(),"attempt" to p.attempt)).build()

    // ---- one try ----
    /** What a try did, for the work's log and the tests. */
    enum class Outcome { SENT, FAILED, RETRY_LATER, PHONE_SENT, PHONE_FAILED, TOO_LATE, OFF }
    /** One try of [day]'s chain; queues the next try or tomorrow's first. Never throws. */
    suspend fun run(app:Context,day:LocalDate,attempt:Int):Outcome {
        val outcome=try { tryOnce(app,day,attempt) } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { Log.w(TAG,"try failed: ${e.javaClass.simpleName}");Outcome.FAILED }
        val next=if(outcome==Outcome.RETRY_LATER) plan(day,attempt+1) else plan(day.plusDays(1),0)
        // From inside the running work: appended, so the next one waits until this one has ended.
        runCatching { queue(app,next,ExistingWorkPolicy.APPEND_OR_REPLACE) }.onFailure { Log.w(TAG,"next not queued: ${it.javaClass.simpleName}") }
        return outcome
    }
    private suspend fun tryOnce(app:Context,day:LocalDate,attempt:Int):Outcome {
        val now=clock()
        if(!KakaoReport.available()) return Outcome.OFF
        val s=settings(app)
        if(s?.optBoolean(PipelineSettingsKeys.MORNING_KAKAO,true)==false || s?.optBoolean(PipelineSettingsKeys.KAKAO_LINKED,false)!=true) return Outcome.OFF
        if(s.optString(PipelineSettingsKeys.REPORT_DAY)==day.toString()) return Outcome.SENT
        if(tooLate(day,now)) return Outcome.TOO_LATE
        val last=attempt>=TRIES.lastIndex
        when(val f=fetch(app,day)) {
            is Fetch.Found -> {
                val e=KakaoReport.send(app,f.report,KakaoReport.SOURCE_ROUTINE)
                return when {
                    e==null || e==KakaoReport.ALREADY_SENT -> Outcome.SENT
                    // Nothing went (a network fault): the next try sends it again; a token error waits for a new link.
                    !last && !e.startsWith(KakaoReport.TOKEN_PREFIX) && settings(app)?.optString(PipelineSettingsKeys.REPORT_DAY)!=day.toString() -> Outcome.RETRY_LATER
                    else -> Outcome.FAILED
                }
            }
            else -> {
                if(!last) return Outcome.RETRY_LATER
                // 06:00 and still no report from the night's tidy: the phone's own short report (DESIGN_V5 1절).
                if(f is Fetch.Failed) Log.w(TAG,"report not read (${f.code}): phone report")
                val e=KakaoReport.send(app,phoneReport(app,day),KakaoReport.SOURCE_PHONE)
                return if(e==null || e==KakaoReport.ALREADY_SENT) Outcome.PHONE_SENT else Outcome.PHONE_FAILED
            }
        }
    }

    // ---- reading report/<day>.json ----
    internal fun path(day:LocalDate)="report/$day.json"
    /** Reads [day]'s report with the kept ETag; a 304 gives the kept copy. A report of another day is [Fetch.Missing]. */
    internal fun fetch(app:Context,day:LocalDate):Fetch {
        val source=(sourceFactory ?: ::githubSource)() ?: return Fetch.Failed("NO_INBOX")
        val s=settings(app)
        val kept=s?.optString(REPORT_BODY)?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("day")==day.toString() }
        val etag=s?.optString(REPORT_ETAG)?.takeIf { it.isNotBlank() && kept!=null }
        val r=try { source.get(path(day),etag) } catch(e:IOException) { return Fetch.Failed("IO_"+e.javaClass.simpleName) }
        return when(r.code) {
            304 -> kept?.let { Fetch.Found(it) } ?: Fetch.Failed("HTTP_304")
            404 -> Fetch.Missing
            200 -> {
                val o=runCatching { JSONObject(r.body ?: "") }.getOrNull() ?: return Fetch.Failed("BAD_JSON")
                if(o.optString("day")!=day.toString()) return Fetch.Missing
                save(app) { it.put(REPORT_BODY,o.toString()).put(REPORT_ETAG,r.etag ?: "") }
                Fetch.Found(o)
            }
            else -> Fetch.Failed("HTTP_${r.code}")
        }
    }
    /** The report read today, if any (settings [지금 보내 보기]). */
    fun today(app:Context,day:LocalDate=CallRecordStore.kstDay(clock())):JSONObject? =
        settings(app)?.optString(REPORT_BODY)?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.takeIf { it.optString("day")==day.toString() }

    /** The inbox's contents API, as the results pass reads it (same repository, token and headers). */
    private fun githubSource():Source? {
        val token=kr.callbrief.BuildConfig.IEUM_INBOX_TOKEN.takeIf { it.isNotBlank() } ?: return null
        val repo=kr.callbrief.BuildConfig.IEUM_INBOX_REPO
        return object:Source {
            override fun get(path:String,etag:String?):Response {
                val b=Request.Builder().url("https://api.github.com/repos/$repo/contents/$path")
                    .header("Authorization","Bearer $token").header("X-GitHub-Api-Version","2022-11-28")
                    .header("Accept","application/vnd.github.raw+json")
                etag?.let { b.header("If-None-Match",it) }
                return client.newCall(b.get().build()).execute().use { Response(it.code,if(it.code==200) it.body?.string() else null,it.header("ETag")) }
            }
        }
    }
    private val client by lazy { OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(40,TimeUnit.SECONDS).build() }

    // ---- the phone's own report (06:00, DESIGN_V5 1절) ----
    /** The open to-dos as the home screen ranks them ("먼저 볼 체크할 일" first: asked today or to check, then the newest). */
    fun phoneReport(app:Context,day:LocalDate):JSONObject {
        val open=runCatching { kr.callbrief.IeumApplication.store(app).openItems() }.getOrDefault(emptyList())
        return phoneReport(day,open)
    }
    internal fun phoneReport(day:LocalDate,open:List<OpenItem>):JSONObject {
        val first=kr.callbrief.pipeline.HomeScreen.firstLook(open,day)
        val ranked=(first+TaskActions.ordered(open).filter { it !in first }).take(8)
        val today=JSONArray()
        ranked.forEach { i -> today.put(JSONObject().put("id",i.id).put("what",i.title).put("who",i.company ?: i.counterpart ?: "")
            .put("since",since(i.createdMs,day)).put("status",if(i.status=="needs_confirmation" || i.tentative) "확인 필요" else "").put("check",false)) }
        return JSONObject().put("day",day.toString()).put("generatedMs",clock())
            .put("head",JSONObject().put("calls",0).put("newChecks",0).put("todayChecks",open.size).put("issues",0))
            .put("yesterday",JSONArray()).put("today",today).put("inProgress",JSONArray()).put("check",JSONArray())
    }
    /** "오늘 요청", "어제 요청", "3일 전 요청" (the report's own words for when it was asked, 1절). */
    internal fun since(createdMs:Long,day:LocalDate):String {
        if(createdMs<=0) return ""
        val n=Duration.between(CallRecordStore.kstDay(createdMs).atStartOfDay(),day.atStartOfDay()).toDays()
        return when { n<=0 -> "오늘 요청";n==1L -> "어제 요청";else -> "${n}일 전 요청" }
    }

    private fun settings(app:Context):JSONObject? = runCatching { SecureSettings(CallPipelineStartup.storage(app)).snapshot() }.getOrNull()
    private fun save(app:Context,block:(JSONObject)->Unit) { runCatching { SecureSettings(CallPipelineStartup.storage(app)).update(block) } }
}

/** One try of the morning report chain ([ReportFetch.run]). */
internal class ReportMorningWork(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val day=runCatching { LocalDate.parse(inputData.getString("day")) }.getOrNull() ?: CallRecordStore.kstDay(System.currentTimeMillis())
        ReportFetch.run(applicationContext,day,inputData.getInt("attempt",0).coerceIn(0,ReportFetch.TRIES.lastIndex))
        return Result.success()
    }
}
