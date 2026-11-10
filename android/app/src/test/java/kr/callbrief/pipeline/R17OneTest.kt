package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

/** v3.7 r17-1 (DESIGN_V7 3.1): T2 yesterday and today first, T3 the rest of v3.6.1 (call-log length, the morning rule under
 * Samsung's protection, samples per thread count, yieldMs and decodeAudioMs), T5 the plugged-in run in the foreground.
 * Generated recordings and calls only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class R17OneTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val minute=60_000L;private val hour=60*minute;private val day=24*hour
    private fun kst(d:Int,h:Int,m:Int=0)=LocalDate.of(2026,11,d).atTime(h,m).atZone(TranscriptionPower.KST).toInstant().toEpochMilli()
    /** 2026-11-10 23:00 KST: the next report is 11/11's, so "yesterday and today" starts at 11/10 00:00. */
    private val night=kst(10,23)
    private var clock=night

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        CallTranscriptionStore(context){ clock }.close()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER);null } }
    }
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun snap(pct:Int=60,plugged:String="ac",status:String="charging")=BatterySnapshot(pct,plugged,status,false,0,0.4f,300,false)

    // ---- T2: the order, pure ----
    private fun row(at:Long,entered:Long=at,parts:Boolean=false,marked:Boolean=false,updated:Long=entered)=QueueOrder.Row(at,entered,updated,parts,marked)

    @Test fun theReportDayTurnsAtFive() {
        assertEquals(LocalDate.of(2026,11,10),QueueOrder.reportDay(kst(10,4,59)))
        assertEquals(LocalDate.of(2026,11,11),QueueOrder.reportDay(kst(10,5,0)))
        assertEquals(kst(9,0),QueueOrder.fromMs(kst(10,4,59)))   // before the 05:00 report: yesterday (11/9) and today
        assertEquals(kst(10,0),QueueOrder.fromMs(kst(10,5,0)))   // after it: today, for tomorrow's report
        // A call at 11/9 23:00 is "yesterday" at 04:59 and an older one at 05:00.
        val late=row(kst(9,23),entered=kst(9,23));val older=row(kst(8,10),entered=kst(8,10))
        assertSame(late,QueueOrder.sort(listOf(older,late),kst(10,4,59)).first())
        assertEquals("older than yesterday, under a day in the queue: the rest",3,QueueOrder.rank(late,kst(10,5,0),null).second)
    }
    @Test fun yesterdaysCallsGoBeforeThirtyOldOnes() {
        // 30 recordings of the last week still waiting (all over a day in the queue), and 10 calls of today.
        val old=(1..30).map { row(night-2*day-it*hour,entered=night-2*day-it*hour+minute) }
        val today=(1..10).map { row(kst(10,9)+it*30*minute) }
        val order=QueueOrder.sort(old+today,night)
        assertEquals("today's ten first, newest first",today.sortedByDescending { it.atMs },order.take(10))
        assertEquals("then the long waits, longest first",old.sortedBy { it.enteredMs },order.drop(10))
    }
    @Test fun aStoppedOldRecordingWithoutPiecesDoesNotTakeTheNight() {
        val stoppedOld=row(night-3*day,entered=night-3*day,marked=true)            // Yield left STOPPED, nothing saved
        val begunOld=row(night-3*day+hour,entered=night-3*day+hour,parts=true)     // an old one with a piece: its age too
        val begunToday=row(kst(10,14),entered=kst(10,14),parts=true)
        val freshToday=row(kst(10,20))
        val order=QueueOrder.sort(listOf(stoppedOld,begunOld,freshToday,begunToday),night)
        assertEquals(listOf(begunToday,freshToday,stoppedOld,begunOld),order)
        // Under a day in the queue and older than yesterday: the rest, newest first, after the long waits.
        val recentOld=row(night-2*day,entered=night-2*hour)
        assertEquals(listOf(freshToday,stoppedOld,recentOld),QueueOrder.sort(listOf(recentOld,stoppedOld,freshToday),night))
    }
    @Test fun firstPassOldRecordingsStillComeLast() {
        val cut=night-5*day
        val first=row(night-20*day,entered=night-hour);val firstBegun=row(night-10*day,entered=night-2*hour,marked=true)
        val waited=row(night-3*day,entered=night-3*day)
        val order=QueueOrder.sort(listOf(first,firstBegun,waited),night,oldBefore=cut)
        assertEquals("q_old first, and under way before the oldest call among them",listOf(waited,firstBegun,first),order)
    }

    // ---- T2 in the store ----
    private val rate=16000
    private var seq=0
    private val wavs=HashMap<String,ByteArray>()
    private fun wav(seconds:Double):ByteArray {
        val n=(rate*seconds).toInt();val out=ByteArray(44+n*2)
        val h=ByteBuffer.wrap(out,0,44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray());h.putInt(36+n*2);h.put("WAVE".toByteArray());h.put("fmt ".toByteArray());h.putInt(16)
        h.putShort(1);h.putShort(1);h.putInt(rate);h.putInt(rate*2);h.putShort(2);h.putShort(16);h.put("data".toByteArray());h.putInt(n*2)
        for(t in 0 until n){ val v=(sin(2*PI*330*t/rate)*6000).toInt();out[44+2*t]=(v and 0xff).toByte();out[45+2*t]=((v shr 8) and 0xff).toByte() }
        return out
    }
    private fun rec(id:String,startedMs:Long,bytes:Long=1000,seconds:Double=0.0) {
        if(seconds>0) wavs[id]=wav(seconds)
        val sha="%064x".format(++seq);val cipher="%064x".format(1000+seq)
        port().use { p -> p.transaction {
            val o=GeneralLocalStore.OWNER
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,modified_at_ms,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision,original_started_at_ms) VALUES(?,?,'g1',?,?,'audio/wav',?,?,?,?,1,1,1,?)",
                id,o,"doc_$id","$id.wav",bytes,startedMs,startedMs,startedMs,startedMs)
            p.execute("INSERT INTO content_blobs VALUES(?,?)",sha,bytes)
            p.execute("INSERT INTO source_versions VALUES(?,1,?,?,?,?)",id,sha,bytes,startedMs,"obs_$id")
            p.execute("INSERT INTO local_files VALUES(?,'content://generated/tree',?,?,'sig',?,?)",id,"doc_$id","content://generated/document/$id",sha,null)
            p.execute("INSERT INTO local_visibility(recording_id) VALUES(?)",id)
            p.execute("INSERT INTO vault2_refs VALUES(?,1,?,?,?,?,?,?)",id,o,sha,bytes,GeneralLocalStore.ALIAS,cipher,bytes+28);null } }
    }
    private fun key(s:CallTranscriptionStore,id:String)=s.candidates().single { it.key.recordingId==id }.key

    @Test fun theStoreTakesYesterdayFirstAndAStoppedOldOneByItsAge() {
        rec("old1",night-4*day);rec("old2",night-3*day);rec("stopped",night-5*day);rec("today1",kst(10,10));rec("today2",kst(10,15))
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            // Each entered the queue a minute after its call.
            for(c in s.candidates()) { clock=c.startedAtMs!!+minute;s.offer(c,true,"") }
            clock=night
            // The stopped one was pushed back by a Yield: STOPPED, no piece. Before v3.7 it went first of all.
            val st=key(s,"stopped");assertTrue(s.claim(st));s.finish(st,"QUEUED","STOPPED",refundAttempt=true)
            fun next()=s.nextQueued()!!.candidate.key.also { s.claim(it);s.finish(it,"DONE","") }.recordingId
            assertEquals(listOf("today2","today1","stopped","old1","old2"),List(5) { next() })
        }
    }
    @Test fun aSavedPieceOfTodayGoesFirst() {
        rec("a",kst(10,9));rec("b",kst(10,20))
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            s.offerAll(s.candidates(),{null})
            s.savePart(key(s,"a"),0,listOf(CallTranscript.Segment(0,1000,"말")))
            assertEquals("a",s.nextQueued()!!.candidate.key.recordingId)
        }
    }

    // ---- T3: the battery queue's length from the call log ----
    @Test fun aWaitingRecordingsLengthComesFromTheCallLog() {
        val afternoon=kst(10,14);clock=afternoon
        port().use { p -> p.execute("CREATE TABLE IF NOT EXISTS call_log(log_id INTEGER PRIMARY KEY,number_key TEXT,name TEXT,start_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,type INTEGER NOT NULL)") }
        // Sizes say 10 minutes each (1 byte per ms), the log says 90 s for one (entry 30 s before the file's time), 6 min for the other.
        rec("short",afternoon-hour,bytes=600_000);rec("long",afternoon-30*minute,bytes=600_000);rec("nolog",afternoon-10*minute,bytes=60_000)
        port().use { p -> p.transaction {
            p.execute("INSERT INTO call_log VALUES(1,'k1',NULL,?,90000,1)",afternoon-hour-30_000)
            p.execute("INSERT INTO call_log VALUES(2,'k2',NULL,?,360000,2)",afternoon-30*minute+20_000)
            p.execute("INSERT INTO call_log VALUES(3,'k3',NULL,?,60000,3)",afternoon-10*minute) // missed: not a length
            null } }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            s.offerAll(s.candidates(),{null})
            val w=CallTranscriptionStore.BatteryWindow(PowerPolicy.dayStartMs(afternoon),1.0)
            // nolog: 60 s by its size; short: 90 s by the log (its size alone said 10 minutes).
            assertEquals("nolog",s.nextQueued(null,w)!!.candidate.key.recordingId)
            val k=key(s,"nolog");s.claim(k);s.finish(k,"DONE","")
            assertEquals("short",s.nextQueued(null,w)!!.candidate.key.recordingId)
            val k2=key(s,"short");s.claim(k2);s.finish(k2,"DONE","")
            assertNull("long: 6 minutes by the log",s.nextQueued(null,w))
            assertEquals(1,s.powerWaiting(w,false).first)
        }
    }

    // ---- T3: the morning rule under Samsung's protection ----
    @Test fun theMorningRestSkipsAProtectedBattery() {
        val six=kst(10,6)
        assertEquals("morning",(PowerPolicy.decide(snap(pct=85),PowerBudget(),six) as PowerPolicy.Decision.Wait).reason)
        assertTrue(PowerPolicy.decide(snap(pct=85,status="notCharging"),PowerBudget(),six) is PowerPolicy.Decision.Run)
        assertTrue(PowerPolicy.decide(snap(pct=85,status="full"),PowerBudget(),six) is PowerPolicy.Decision.Run)
    }

    // ---- T3: speed samples per thread count ----
    @Test fun samplesStayPerThreadCount() {
        var o=PowerStats.notePiece(null,240_000,120_000,6,cold=true,now=night)
        o=PowerStats.notePiece(o,250_000,120_000,6,cold=false,now=night+5*minute)
        // By day on battery: 4 threads, slower.
        o=PowerStats.notePiece(o,400_000,120_000,4,cold=true,now=night+day)
        val c=o.getJSONObject("chunk")
        assertEquals(1,c.getJSONArray("rates").length());assertEquals(4,c.getInt("threads"))
        assertEquals(2,c.getJSONObject("byThreads").getJSONObject("6").getJSONArray("rates").length())
        // Back on the charger at 6: the night's samples are still there.
        o=PowerStats.notePiece(o,245_000,120_000,6,cold=true,now=night+day+5*hour)
        val c6=o.getJSONObject("chunk")
        assertEquals(3,c6.getJSONArray("rates").length());assertEquals(2,c6.getJSONArray("coldRates").length())
        assertEquals(245_000.0/120_000,PowerStats.rate(o,6)!!,0.01)
        assertEquals(400_000.0/120_000,PowerStats.rate(o,4)!!,0.01)
        assertEquals("unknown count: the last one's",PowerStats.rate(o)!!,PowerStats.rate(o,6)!!,0.0)
        assertEquals("no samples for 2: the last count's",PowerStats.rate(o,6)!!,PowerStats.rate(o,2)!!,0.0)
        // A v3.6 ledger: its top-level lists move under the thread count they were made on.
        val v36=JSONObject().put("chunk",JSONObject().put("threads",6).put("rates",org.json.JSONArray().put(2.0).put(2.1)).put("coldRates",org.json.JSONArray().put(2.0)))
        val up=PowerStats.notePiece(v36,500_000,120_000,4,cold=false,now=night)
        assertEquals(2,up.getJSONObject("chunk").getJSONObject("byThreads").getJSONObject("6").getJSONArray("rates").length())
    }

    // ---- T3: yieldMs and decodeAudioMs come out of the runner ----
    private val model=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="m";override fun verifiedModelFile()=File("m") }
    private fun runner(s:CallTranscriptionStore,t:CallTranscriber,events:CallTranscriptionRunner.Events,keepGoing:()->Boolean={true},
                       stopped:()->Boolean={false},yieldStop:()->Boolean={false},timeLimited:()->Boolean={false})=
        CallTranscriptionRunner(s,model,PipelineAudioDecoder(),t,read={ c -> PipelineBytesInput(wavs.getValue(c.key.recordingId).copyOf()) },
            skipReason={null},sink={ object:CallTranscriptSink { override fun onTranscript(transcript:CallTranscript) {} } },now={clock},
            stopped=stopped,keepGoing=keepGoing,events=events,yieldStop=yieldStop,timeLimited=timeLimited)
    @Test fun aYieldCountsTheTimeLeftAndDecodingIsTimedApartFromThePieces() {
        rec("long",kst(10,20),seconds=300.0)
        var yieldMs=-1L;var decode=0L;var calls=0
        val events=object:CallTranscriptionRunner.Events {
            override fun yieldLeft(ms:Long) { yieldMs=ms }
            override fun decodeAudio(ms:Long) { decode+=ms;calls++ }
        }
        // Each piece takes 3 minutes of work; the second would not fit in the 9 minutes (W1).
        val t=CallTranscriber { _,pcm,_ -> clock+=3*minute;listOf(CallTranscript.Segment(0,pcm.size*1000L/rate,"말")) }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val pass=runner(s,t,events,keepGoing={ false }).run()
            assertEquals("YIELD",pass.state)
            assertEquals(TranscriptionPower.SELF_END_MS-3*minute,yieldMs)
            assertEquals(1,calls);assertEquals("the clock moved only in the pieces",0L,decode)
        }
    }

    // ---- T5: a foreground stop is a yield, not a stall, and spends no attempt ----
    @Test fun aForegroundTimeoutOrRefusalIsAYield() {
        rec("call",kst(10,20),seconds=300.0)
        var stop=false;var yields=0
        val events=object:CallTranscriptionRunner.Events { override fun yielded() { yields++ } }
        val t=CallTranscriber { _,pcm,_ -> clock+=2*minute;stop=true;throw IllegalStateException("ANDROID_STT_STOPPED") }
        CallTranscriptionStore(context,{ clock },{}).use { s ->
            val k=key(s.also { it.offerAll(it.candidates(),{null}) },"call")
            val pass=runner(s,t,events,stopped={ stop },yieldStop={ stop },timeLimited={ true }).run()
            assertEquals("YIELD",pass.state);assertEquals(1,yields)
            assertEquals(0,s.attempts(k));assertEquals(0,s.totalStalls(k))
            assertTrue(s.rows().single().let { it.second=="QUEUED" && it.third.first=="STOPPED" })
            // The same stop without the foreground reason: a stall is counted (the first recording of a run, no piece done).
            stop=false
            runner(s,t,events,stopped={ stop },timeLimited={ false }).run()
            assertEquals(1,s.totalStalls(k))
        }
        assertTrue(TranscriptionForeground.yieldStop(androidx.work.WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT,false))
        assertTrue(TranscriptionForeground.yieldStop(androidx.work.WorkInfo.STOP_REASON_USER,true))
        assertFalse(TranscriptionForeground.yieldStop(androidx.work.WorkInfo.STOP_REASON_USER,false))
        assertFalse(TranscriptionForeground.yieldStop(androidx.work.WorkInfo.STOP_REASON_TIMEOUT,true))
    }

    // ---- T5: when to ask, the 5-hour day, the ledger and the heartbeat ----
    @Test fun evenNightsOnlyAndFiveHoursADay() {
        assertTrue(ForegroundPolicy.evenDay(kst(10,1)));assertFalse(ForegroundPolicy.evenDay(kst(11,1)))
        assertFalse("23:59 KST of 11/11 is odd",ForegroundPolicy.evenDay(kst(11,23,59)))
        assertTrue(ForegroundPolicy.onToday(null,kst(10,2)));assertTrue(ForegroundPolicy.onToday("auto",kst(10,2)))
        assertFalse(ForegroundPolicy.onToday("auto",kst(11,2)));assertTrue(ForegroundPolicy.onToday("on",kst(11,2)))
        assertFalse(ForegroundPolicy.onToday("off",kst(10,2)));assertEquals("auto",ForegroundPolicy.setting("x"))
        val p=PowerPolicy.Mode.PLUGGED
        assertTrue(ForegroundPolicy.wanted("auto",p,kst(10,2),0))
        assertFalse("never on battery",ForegroundPolicy.wanted("on",PowerPolicy.Mode.BATTERY,kst(10,2),0))
        assertFalse(ForegroundPolicy.wanted("auto",p,kst(10,2),ForegroundPolicy.DAY_LIMIT_MS))
        assertFalse(ForegroundPolicy.wanted("auto",p,kst(10,2),0,refusedToday=ForegroundPolicy.REFUSALS_PER_DAY))
        val t=kst(10,2)
        var o=PowerStats.noteFg(null,t,"run",4*hour)
        assertTrue(ForegroundPolicy.wanted("auto",p,t,PowerStats.fgUsedMs(o,t)))
        o=PowerStats.noteFg(o,t+4*hour,"run",hour)
        assertEquals(5*hour,PowerStats.fgUsedMs(o,t+5*hour))
        assertFalse("5 hours used",ForegroundPolicy.wanted("auto",p,t+5*hour,PowerStats.fgUsedMs(o,t+5*hour)))
        // A time-limit stop: no more requests today, whatever was counted; the next even day starts again.
        var x=PowerStats.noteFg(null,t,"timeout",30*minute)
        assertEquals(ForegroundPolicy.DAY_LIMIT_MS,PowerStats.fgUsedMs(x,t))
        assertEquals(0L,PowerStats.fgUsedMs(x,t+2*day))
        x=PowerStats.noteFg(x,t,"refused")
        assertEquals(1,PowerStats.fgRefusedToday(x,t))
        // Heartbeat: fg{runs,min,refused,timeout} and the day's piece median per way.
        var l=PowerStats.noteFg(PowerStats.noteFg(null,t,"run",2*hour),t,"refused")
        l=PowerStats.notePiece(l,200_000,120_000,6,cold=true,now=t,foreground=true)
        l=PowerStats.notePiece(l,220_000,120_000,6,cold=false,now=t+5*minute,foreground=true)
        l=PowerStats.notePiece(l,300_000,120_000,6,cold=false,now=t+10*minute,foreground=false)
        l=PowerStats.notePiece(l,500_000,120_000,4,cold=false,now=t+11*minute) // battery: in neither
        l=PowerStats.noteFgSetting(l,"auto",true)
        val hb=PowerText.heartbeat(l,false,t+hour)
        val today=hb.getJSONObject("today")
        val fg=today.getJSONObject("fg")
        assertEquals(1,fg.getInt("runs"));assertEquals(120,fg.getInt("min"));assertEquals(1,fg.getInt("refused"));assertEquals(0,fg.getInt("timeout"))
        val med=today.getJSONObject("pieceMedianMs")
        assertEquals(210_000L,med.getLong("fg"));assertEquals(300_000L,med.getLong("bg"));assertEquals(2,med.getInt("fgN"));assertEquals(1,med.getInt("bgN"))
        assertEquals("auto",hb.getString("fgSetting"));assertTrue(hb.getBoolean("fgOn"))
        // Nothing yet: zeros and nulls, the block always there.
        val empty=PowerText.heartbeat(null,false,t).getJSONObject("today")
        assertEquals(0,empty.getJSONObject("fg").getInt("runs"));assertTrue(empty.getJSONObject("pieceMedianMs").isNull("fg"))
    }
    @Test fun theForegroundNoticeIsQuietAndOneLine() {
        val info=TranscriptionForeground.info(context)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,info.foregroundServiceType)
        assertEquals("충전하는 동안 통화를 글자로 바꾸고 있어요",info.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertTrue(info.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE!=0)
        val ch=context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(TranscriptionForeground.CHANNEL)
        assertEquals(android.app.NotificationManager.IMPORTANCE_MIN,ch.importance);assertNull(ch.sound)
    }
    @Test fun theManifestDeclaresTheForegroundTypes() {
        val info=context.packageManager.getPackageInfo(context.packageName,android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(info.requestedPermissions!!.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING"))
        val xml=File("src/main/AndroidManifest.xml").readText()
        val service=Regex("""<service android:name="androidx\.work\.impl\.foreground\.SystemForegroundService"[^>]*>""").find(xml)!!.value
        assertTrue(service.contains("""android:foregroundServiceType="dataSync|mediaProcessing""""));assertTrue(service.contains("""tools:node="merge""""))
    }
}
