package kr.callbrief.pipeline

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.callbrief.AndroidWhisperEngine
import kr.callbrief.SecureSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Real-device check of the lean pipeline with public audio only:
 * model download -> Korean Whisper speed -> transcript stored -> screens render (screenshots).
 * Results go to logcat (tag IEUM_RESULT) and to <external files>/device-test/. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CallPipelineDeviceTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val storage get()=CallPipelineStartup.storage(context)
    private val out get()=File(context.getExternalFilesDir(null),"device-test").apply{mkdirs()}
    private fun report(name:String,x:JSONObject){Log.i("IEUM_RESULT","$name ${x}");File(out,"$name.json").writeText(x.toString(2))}

    private fun publicKorean():FloatArray=PipelineAudioMath.decodeWav(context.assets.open("local-stt/public-ko.wav").use{it.readBytes()}).chunks.flatMap{it.second.asList()}.toFloatArray()

    @Test fun t1_modelDownloadsAndVerifies() {
        val model=PipelineModelManager(storage)
        val started=System.currentTimeMillis()
        var result=PipelineModelManager.Result.RETRY
        repeat(6){ i -> if(result==PipelineModelManager.Result.RETRY){ if(i>0)Thread.sleep(15_000L*i);result=model.download() } }
        // Why a download did not finish (HTTP code or network error), for the report.
        val probe=if(result==PipelineModelManager.Result.READY) "" else runCatching {
            okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(PipelineModelManager.Spec.DEFAULT.url).head().build()).execute().use { "HTTP ${it.code}" }
        }.getOrElse { it.javaClass.simpleName+": "+it.message }
        report("t1_model",JSONObject().put("result",result.name).put("probe",probe).put("seconds",(System.currentTimeMillis()-started)/1000).put("state",model.state())
            .put("abi",android.os.Build.SUPPORTED_ABIS.first()).put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT))
        assertEquals(PipelineModelManager.Result.READY,result)
        assertNotNull(model.verifiedModelFile())
    }

    /** Speed on speech only (6 threads, then 8), then on a call-like recording with 4 s pauses between sentences,
     * which the silence skipping should shorten. */
    @Test fun t2_koreanSpeedWithLargeModel() {
        val model=PipelineModelManager(storage);val file=checkNotNull(model.verifiedModelFile())
        val repeat=InstrumentationRegistry.getArguments().getString("repeat")?.toIntOrNull() ?: 3
        val one=publicKorean();val pcm=FloatArray(one.size*repeat){one[it%one.size]}
        val audioMs=pcm.size*1000L/16000
        val result=JSONObject().put("audioSeconds",audioMs/1000.0).put("cores",Runtime.getRuntime().availableProcessors()).put("device",android.os.Build.MODEL)
        var text=""
        val args=InstrumentationRegistry.getArguments()
        val variants=(args.getString("threads") ?: "6,8").split(',').mapNotNull{it.trim().toIntOrNull()}
        for(threads in variants) {
            val started=System.currentTimeMillis()
            val segments=WhisperCallTranscriber(AndroidWhisperEngine(storage),model,threads).transcribe(file,pcm){false}
            val elapsed=System.currentTimeMillis()-started
            text=segments.joinToString(""){it.text}.trim()
            result.put("secondsPerAudioMinute_threads$threads",elapsed*60.0/audioMs)
        }
        if(args.getString("callLike")=="false") { report("t2_speed",result.put("text",text.take(200)));assertTrue(text.any{it in '가'..'힣'});return }
        // Call-like: each sentence followed by 4 s of near silence.
        val pause=FloatArray(16000*4){ ((it*7919)%200-100)/100000f }
        val call=(0 until repeat).flatMap{one.asList()+pause.asList()}.toFloatArray()
        val callMs=call.size*1000L/16000
        val trimmed=PipelineAudioMath.speechOnly(PipelineDecodedAudio(callMs,PipelineAudioMath.split(call),false))
        val started=System.currentTimeMillis()
        val callText=trimmed.chunks.flatMap{(_,x)->WhisperCallTranscriber(AndroidWhisperEngine(storage),model).transcribe(file,x){false}}.joinToString(""){it.text}.trim()
        val elapsed=System.currentTimeMillis()-started
        result.put("callAudioSeconds",callMs/1000.0).put("callSpeechSeconds",trimmed.chunks.sumOf{it.second.size}/16000.0)
            .put("callSecondsPerAudioMinute",elapsed*60.0/callMs).put("text",text.take(200)).put("callText",callText.take(200))
        report("t2_speed",result)
        assertTrue("no Korean recognized: $text",text.any{it in '가'..'힣'})
        assertTrue("silence not skipped",trimmed.vadApplied);assertTrue(callText.contains("견적서"))
    }

    /** The transcription side hands a real transcript to the app's sink; without a key the call waits, plainly worded. */
    @Test fun t3_transcriptReachesTodayScreen() {
        val model=PipelineModelManager(storage);val file=checkNotNull(model.verifiedModelFile())
        val decoded=PipelineAudioDecoder().decode(context.assets.open("local-stt/public-ko.wav").use{it.readBytes()},"audio/wav")
        val segments=decoded.chunks.flatMap{(offset,pcm)->WhisperCallTranscriber(AndroidWhisperEngine(storage),model).transcribe(file,pcm){false}
            .map{CallTranscript.Segment(it.startMs+offset,it.endMs+offset,it.text)}}
        val text=segments.joinToString(""){it.text}.trim();assertTrue(text.isNotEmpty())
        val now=System.currentTimeMillis();val recording="device-test-$now"
        // A real call always has its imported recording row; seed a synthetic one (no audio file behind it).
        kr.co.callsummary.core.android.AndroidSqlPort(storage,kr.callbrief.GeneralLocalStore.DATABASE,storage.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",kr.callbrief.GeneralLocalStore.OWNER)
            p.execute("INSERT OR IGNORE INTO folder_grants VALUES('device-test',?,'content://device-test/tree',1,1,1)",kr.callbrief.GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'device-test',?,?,'audio/mp4',10,0,0,1,1,1)",
                recording,kr.callbrief.GeneralLocalStore.OWNER,recording,"통화 녹음 김민수_251029_101500.m4a");null
        } }
        val t=CallTranscript(recording,1,"sha-$now",now-60_000,decoded.durationMs,
            CallTranscriptionRunner.counterpart("통화 녹음 김민수_251029_101500.m4a"),text,segments,model.modelName,model.modelSha256,decoded.vadApplied)
        CallSinkImpl(context){}.onTranscript(t)
        val stored=CallRecordStore(storage).use{it.record(CallRecordStore.recordId(t))}
        assertNotNull(stored);assertEquals("김민수",stored!!.displayName)
        // Stand in for Claude (no key in tests): apply a fixed analysis so the to-do screens have content.
        val quote=text.take(8)
        CallRecordStore(storage).use{it.applyAnalysis(stored.recordId,ClaudeCallAnalysis("김민수 님과 견적서 일정을 이야기했어요. 금요일까지 견적서를 보내기로 했어요.",
            listOf("견적서는 금요일까지"),"김민수",null,listOf(ClaudeCallAnalysis.Promise("견적서 보내기",CallUi.today().plusDays(1).toString(),"me",quote)),
            listOf(ClaudeCallAnalysis.Task("create",null,"김민수 님께 견적서 보내기",CallUi.today().toString(),quote))))}
        val after=CallRecordStore(storage).use{it.record(stored.recordId)!!}
        report("t3_pipeline",JSONObject().put("state",after.state).put("counterpart",after.displayName).put("openTasks",CallRecordStore(storage).use{it.openItems().size}).put("transcript",text.take(200)))
        assertEquals(CallRecordStore.ANALYZED,after.state)
        assertTrue("task not created",CallRecordStore(storage).use{it.openItems()}.any{it.title.contains("견적서")})
    }

    /** Samsung recordings are m4a/AAC at 44.1 kHz: MediaCodec decode, resample, transcribe. */
    @Test fun t3b_m4aRecordingDecodesAndTranscribes() {
        val model=PipelineModelManager(storage);val file=checkNotNull(model.verifiedModelFile())
        val decoded=PipelineAudioDecoder().decode(inst.context.assets.open("public-ko.m4a").use{it.readBytes()},"audio/mp4")
        val text=decoded.chunks.flatMap{(_,pcm)->WhisperCallTranscriber(AndroidWhisperEngine(storage),model).transcribe(file,pcm){false}}.joinToString(""){it.text}.trim()
        report("t3b_m4a",JSONObject().put("durationMs",decoded.durationMs).put("chunks",decoded.chunks.size).put("text",text.take(200)))
        assertTrue(decoded.durationMs in 6000..10000);assertTrue("no Korean: $text",text.any{it in '가'..'힣'})
    }

    @Test fun t4_screensRenderAndCapture() {
        CallUi.allowCaptureForTests=true
        val shots=JSONObject()
        fun <A:Activity> shot(name:String,cls:Class<A>,ready:(A)->Boolean) {
            ActivityScenario.launch(cls).use { s ->
                var ok=false
                repeat(100){ if(!ok){ s.onActivity{a->ok=ready(a)};if(!ok)Thread.sleep(100) } }
                inst.waitForIdleSync();Thread.sleep(2000);inst.waitForIdleSync()
                val bmp=inst.uiAutomation.takeScreenshot()
                File(out,"$name.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
                shots.put(name,ok);assertTrue("$name did not render",ok)
            }
        }
        fun has(a:Activity,tag:String)=a.window.decorView.findViewWithTag<View>(tag)!=null
        fun text(a:Activity,value:String):Boolean{
            fun walk(v:View):Boolean=(v is android.widget.TextView&&v.text.toString().contains(value))||(v is android.view.ViewGroup&&(0 until v.childCount).any{walk(v.getChildAt(it))})
            return walk(a.window.decorView)
        }
        shot("1_home",HomeActivity::class.java){has(it,"home_calls_button")&&has(it,"home_today")}
        shot("2_today",TodayCallsActivity::class.java){text(it,"김민수")}
        shot("3_tasks",CallTasksActivity::class.java){text(it,"견적서 보내기")}
        shot("4_settings",CallSettingsActivity::class.java){text(it,"통화 정리 방식")}
        report("t4_screens",shots)
    }

    /** The morning report is posted as a notification that lists the to-dos (no tap needed to read them). */
    @Test fun t5_morningReportNotification() {
        val message=CallRecordStore(storage).use{CallDigestText.morning(it,CallUi.today())}
        CallDigestNotifier.post(context,CallDigestSchedule.MORNING,message)
        Thread.sleep(1000)
        val posted=context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull{it.tag=="ieum.calls.${CallDigestSchedule.MORNING}"}
        val lines=posted?.notification?.extras?.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.map{it.toString()}.orEmpty()
        report("t5_notification",JSONObject().put("posted",posted!=null).put("title",message.title).put("lines",org.json.JSONArray(lines)))
        assertNotNull("morning notification not shown",posted);assertTrue(lines.any{it.contains("견적서")})
    }
}
