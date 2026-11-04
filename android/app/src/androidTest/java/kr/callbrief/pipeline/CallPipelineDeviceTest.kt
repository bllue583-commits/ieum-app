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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/** Real-device check of the lean pipeline with public audio only:
 * model download -> Korean Whisper speed -> transcript stored -> screens render (screenshots).
 * Results go to logcat (tag IEUM_RESULT) and to <external files>/device-test/.
 *
 * Instrumentation argument `model`: `large` (default, the app's pinned model; Firebase real devices) or `small`
 * (the quick emulator run: a pinned 60 MB multilingual model that only shows Korean text comes out; the speed test is skipped).
 * Either model goes through the app's own [PipelineModelManager] size + SHA-256 check. The CI runner may write its cached
 * copy in place as the finished partial download (tools/run_device_test.sh, run-as); the same check runs on it. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CallPipelineDeviceTest {
    /** The emulator runs the large model about 60x slower than a phone; one-piece device tests need the old 30-minute limit. */
    private val TEST_DEADLINE=1800

    private val args get()=InstrumentationRegistry.getArguments()
    private val large get()=args.getString("model") != "small"
    private val spec get()=if(large) PipelineModelManager.Spec.DEFAULT else SMALL_MODEL
    private fun model()=PipelineModelManager(storage,spec=spec)

    /** Quick run: the app schedules its own (large) model download when it finds none; stop it so 574 MB does not
     * download in the background while the screens are captured. */
    @After fun stopLargeDownloadInQuickRun() {
        if(!large)runCatching{androidx.work.WorkManager.getInstance(context).cancelUniqueWork(PipelineModelDownloadWork.UNIQUE_NAME)}
    }

    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val storage get()=CallPipelineStartup.storage(context)
    private val out get()=File(context.getExternalFilesDir(null),"device-test").apply{mkdirs()}
    private fun report(name:String,x:JSONObject){Log.i("IEUM_RESULT","$name ${x}");File(out,"$name.json").writeText(x.toString(2))}

    /** A short recording's pieces all at once (through the same streaming decoder the app uses). */
    private fun decodeAll(bytes:ByteArray,mime:String):PipelineDecodedAudio {
        val chunks=ArrayList<Pair<Long,FloatArray>>()
        val info=PipelineAudioDecoder().decode(PipelineBytesInput(bytes),mime){_,offset,pcm->chunks+=offset to pcm}
        return PipelineDecodedAudio(info.durationMs,chunks,info.vadApplied)
    }

    private fun publicKorean():FloatArray=PipelineAudioMath.decodeWav(context.assets.open("local-stt/public-ko.wav").use{it.readBytes()}).chunks.flatMap{it.second.asList()}.toFloatArray()

    @Test fun t1_modelDownloadsAndVerifies() {
        val model=model()
        val started=System.currentTimeMillis()
        val seeded=seedFromRunnerCache(model)
        fun download():PipelineModelManager.Result {
            var result=PipelineModelManager.Result.RETRY
            repeat(6){ i -> if(result==PipelineModelManager.Result.RETRY){ if(i>0)Thread.sleep(15_000L*i);result=model.download() } }
            return result
        }
        var result=download()
        // A bad cached copy fails the hash check and is deleted; fetch it from the network then.
        if(seeded=="runner copy"&&result==PipelineModelManager.Result.FAILED)result=download()
        // Why a download did not finish (HTTP code or network error), for the report.
        val probe=if(result==PipelineModelManager.Result.READY) "" else runCatching {
            okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(spec.url).head().build()).execute().use { "HTTP ${it.code}" }
        }.getOrElse { it.javaClass.simpleName+": "+it.message }
        report("t1_model",JSONObject().put("model",spec.name).put("cache",seeded).put("result",result.name).put("probe",probe).put("seconds",(System.currentTimeMillis()-started)/1000).put("state",model.state())
            .put("abi",android.os.Build.SUPPORTED_ABIS.first()).put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT))
        assertEquals(PipelineModelManager.Result.READY,result)
        assertNotNull(model.verifiedModelFile())
    }

    /** Speed on speech only (6 threads, then 8), then on a call-like recording with 4 s pauses between sentences,
     * which the silence skipping should shorten. */
    @Test fun t2_koreanSpeedWithLargeModel() {
        if(!large)report("t2_speed",JSONObject().put("skipped","model=small (quick run)"))
        assumeTrue("speed is measured with the large model only",large)
        val model=model();val file=checkNotNull(model.verifiedModelFile())
        val repeat=args.getString("repeat")?.toIntOrNull() ?: 3
        val one=publicKorean();val pcm=FloatArray(one.size*repeat){one[it%one.size]}
        val audioMs=pcm.size*1000L/16000
        val result=JSONObject().put("audioSeconds",audioMs/1000.0).put("cores",Runtime.getRuntime().availableProcessors()).put("device",android.os.Build.MODEL)
        var text=""
        val variants=(args.getString("threads") ?: "6,8").split(',').mapNotNull{it.trim().toIntOrNull()}
        for(threads in variants) {
            val started=System.currentTimeMillis()
            val segments=WhisperCallTranscriber(AndroidWhisperEngine(storage),model,threads,deadlineSeconds=TEST_DEADLINE).transcribe(file,pcm){false}
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
        val callText=trimmed.chunks.flatMap{(_,x)->WhisperCallTranscriber(AndroidWhisperEngine(storage),model,deadlineSeconds=TEST_DEADLINE).transcribe(file,x){false}}.joinToString(""){it.text}.trim()
        val elapsed=System.currentTimeMillis()-started
        result.put("callAudioSeconds",callMs/1000.0).put("callSpeechSeconds",trimmed.chunks.sumOf{it.second.size}/16000.0)
            .put("callSecondsPerAudioMinute",elapsed*60.0/callMs).put("text",text.take(200)).put("callText",callText.take(200))
        report("t2_speed",result)
        assertTrue("no Korean recognized: $text",text.any{it in '가'..'힣'})
        assertTrue("silence not skipped",trimmed.vadApplied);assertTrue(callText.contains("견적서"))
    }

    /** The transcription side hands a real transcript to the app's sink; without a key the call waits, plainly worded. */
    @Test fun t3_transcriptReachesTodayScreen() {
        val model=model();val file=checkNotNull(model.verifiedModelFile())
        val decoded=decodeAll(context.assets.open("local-stt/public-ko.wav").use{it.readBytes()},"audio/wav")
        val segments=decoded.chunks.flatMap{(offset,pcm)->WhisperCallTranscriber(AndroidWhisperEngine(storage),model,deadlineSeconds=TEST_DEADLINE).transcribe(file,pcm){false}
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
            listOf(ClaudeCallAnalysis.Task("create",null,"김민수 님께 견적서 보내기",CallUi.today().toString(),quote,"△△철강")),
            counterpart=ClaudeCallAnalysis.Counterpart("김민수","△△철강","과장",false),siteMemo="3층 슬래브 타설 준비 중, 철근 반입 금요일",
            companyMemo="철근 단가 재견적 진행 중",companyTrade="철근"))}
        val after=CallRecordStore(storage).use{it.record(stored.recordId)!!}
        report("t3_pipeline",JSONObject().put("state",after.state).put("counterpart",after.displayName).put("openTasks",CallRecordStore(storage).use{it.openItems().size}).put("transcript",text.take(200)))
        assertEquals(CallRecordStore.ANALYZED,after.state)
        assertTrue("task not created",CallRecordStore(storage).use{it.openItems()}.any{it.title.contains("견적서")})
    }

    /** Samsung recordings are m4a/AAC at 44.1 kHz: MediaCodec decode, resample, transcribe. */
    @Test fun t3b_m4aRecordingDecodesAndTranscribes() {
        val model=model();val file=checkNotNull(model.verifiedModelFile())
        val decoded=decodeAll(inst.context.assets.open("public-ko.m4a").use{it.readBytes()},"audio/mp4")
        val text=decoded.chunks.flatMap{(_,pcm)->WhisperCallTranscriber(AndroidWhisperEngine(storage),model,deadlineSeconds=TEST_DEADLINE).transcribe(file,pcm){false}}.joinToString(""){it.text}.trim()
        report("t3b_m4a",JSONObject().put("durationMs",decoded.durationMs).put("chunks",decoded.chunks.size).put("text",text.take(200)))
        assertTrue(decoded.durationMs in 6000..10000);assertTrue("no Korean: $text",text.any{it in '가'..'힣'})
    }

    @Test fun t4_screensRenderAndCapture() {
        val shots=JSONObject()
        // One Activity (design G1): each picture opens MainActivity on its screen, the way a notification does.
        fun shot(name:String,screen:Screen,ready:(MainActivity)->Boolean) {
            ActivityScenario.launch<MainActivity>(MainActivity.intent(context,screen)).use { s ->
                var ok=false
                repeat(300){ if(!ok){ s.onActivity{a->ok=ready(a)};if(!ok)Thread.sleep(100) } }
                inst.waitForIdleSync();Thread.sleep(2000);inst.waitForIdleSync()
                // takeScreenshot() now and then returns null on a busy emulator; the render check below is what counts.
                var bmp:Bitmap?=null
                repeat(3){ if(bmp==null){ bmp=inst.uiAutomation.takeScreenshot();if(bmp==null)Thread.sleep(1000) } }
                bmp?.let { b -> File(out,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)} }
                shots.put(name,ok);assertTrue("$name did not render",ok)
            }
        }
        fun has(a:Activity,tag:String)=a.window.decorView.findViewWithTag<View>(tag)!=null
        fun text(a:Activity,value:String):Boolean{
            fun walk(v:View):Boolean=(v is android.widget.TextView&&v.text.toString().contains(value))||(v is android.view.ViewGroup&&(0 until v.childCount).any{walk(v.getChildAt(it))})
            return walk(a.window.decorView)
        }
        shot("1_home",Screen.HOME){has(it,"home_calls_button")&&has(it,"home_today")}
        shot("2_today",Screen.CALLS){text(it,"김민수")}
        shot("3_tasks",Screen.TASKS){text(it,"견적서 보내기")}
        shot("4_settings",Screen.SETTINGS){text(it,"정리 동의")}
        shot("5_companies",Screen.COMPANIES){text(it,"△△철강")}
        // Company detail with its people and the [이름 고치기] button.
        val companyTag="company_"+CallRecordStore(storage).use{it.companies().first().id}
        var opened=false // one tap: every tap restarts the (slow on emulators) detail load
        shot("6_company_detail",Screen.COMPANIES){ a ->
            if(!opened) a.window.decorView.findViewWithTag<View>(companyTag)?.let { it.performClick();opened=true }
            text(a,"사람 (")&&text(a,"이름 고치기") }
        report("t4_screens",shots)
    }

    /** The morning report is posted as a notification that lists the to-dos (no tap needed to read them). */
    @Test fun t5_morningReportNotification() {
        val message=CallRecordStore(storage).use{CallDigestText.morning(it,CallUi.today())}
        CallDigestNotifier.post(context,CallDigestSchedule.MORNING,message)
        Thread.sleep(1000)
        val posted=context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull{it.tag=="ieum.calls.${CallDigestSchedule.MORNING}"}
        // Whole lines in one wrapped text (round 2: an inbox style cut each line with "…").
        val lines=posted?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()?.lines().orEmpty()
        report("t5_notification",JSONObject().put("posted",posted!=null).put("title",message.title).put("lines",org.json.JSONArray(lines)))
        assertNotNull("morning notification not shown",posted);assertTrue(lines.any{it.contains("견적서")})
    }

    // ---- Golden set (v24-2, design F5; decides #4 silence floor and #5 prompt in v2.5-4) ----

    /** Three synthetic Korean site calls (offline TTS, made by tools/audio/make_golden.py; fictional companies and people) are
     * transcribed the way the app does it (decode with speech-only cutting, one model session per call, chunks, TranscriptClean).
     * The text of every call and variant goes to logcat (IEUM_RESULT golden_text) and device-test/golden_runs.json;
     * tools/audio/golden_score.py on the runner turns that into CER, entity accuracy and the comparison table (one scorer, host-tested).
     *
     * Argument `golden`: `compare` (default: every [GoldenSwitches] variant this build has), `base` (today's code only), `off`.
     * Variants that do not run still get a row (why) so the comparison table always lists all three.
     * The large model on the emulator is far too slow for three one-minute calls; it runs only with `goldenLarge=true` (real devices). */
    @Test fun tg_goldenSet() {
        val mode=args.getString("golden") ?: "compare"
        if(mode=="off"){report("golden",JSONObject().put("skipped","golden=off"));return}
        if(large&&args.getString("goldenLarge")!="true"){report("golden",JSONObject().put("skipped","large model on an emulator is too slow for the golden set (goldenLarge=true on a real device)"));return}
        val set=JSONObject(inst.context.assets.open("golden/golden.json").bufferedReader().use{it.readText()})
        val model=model();val file=checkNotNull(model.verifiedModelFile())
        val runs=JSONArray();val summary=JSONObject().put("model",spec.name).put("mode",mode)
        for(v in GoldenSwitches.VARIANTS) {
            val runIt=v.available&&(mode=="compare"||v.name=="base")
            val row=JSONObject().put("variant",v.name).put("floor",v.floor ?: JSONObject.NULL).put("prompt",v.prompt).put("available",runIt)
            if(!runIt) {
                row.put("why",if(v.available) "not run (golden=$mode)" else v.why);runs.put(row);Log.i("IEUM_RESULT","golden_variant $row");summary.put(v.name,row.getString("why"));continue
            }
            val calls=JSONArray();val started=System.currentTimeMillis()
            for(i in 0 until set.getJSONArray("calls").length()) {
                val call=set.getJSONArray("calls").getJSONObject(i)
                val t0=System.currentTimeMillis()
                Log.i("IEUM_RESULT","golden_start ${v.name} ${call.getString("id")}") // a hang shows where it stopped
                val decoded=GoldenSwitches.decode(inst.context.assets.open("golden/"+call.getString("file")).use{it.readBytes()},v.floor)
                val prompt=if(v.prompt) set.getString("prompt") else null
                val segments=GoldenSwitches.transcriber(storage,model,prompt,TEST_DEADLINE).open(file){false}.use { session ->
                    decoded.chunks.flatMap{(offset,pcm)->session.transcribe(pcm){false}.map{CallTranscript.Segment(it.startMs+offset,it.endMs+offset,it.text)}} }
                val cleaned=TranscriptClean.clean(segments)
                val x=JSONObject().put("variant",v.name).put("id",call.getString("id")).put("seconds",(System.currentTimeMillis()-t0)/1000.0)
                    .put("audioSeconds",decoded.durationMs/1000.0).put("speechSeconds",decoded.chunks.sumOf{it.second.size}/16000.0).put("cleaned",cleaned.removed)
                    .put("text",cleaned.segments.joinToString(""){it.text}.trim())
                Log.i("IEUM_RESULT","golden_text $x");calls.put(x)
            }
            row.put("seconds",(System.currentTimeMillis()-started)/1000).put("calls",calls);runs.put(row)
            Log.i("IEUM_RESULT","golden_variant ${JSONObject().put("variant",v.name).put("floor",v.floor ?: JSONObject.NULL).put("prompt",v.prompt).put("available",true).put("seconds",row.getLong("seconds"))}")
            summary.put(v.name,"${row.getLong("seconds")} s")
        }
        File(out,"golden_runs.json").writeText(JSONObject().put("model",spec.name).put("threads",(Runtime.getRuntime().availableProcessors()-1).coerceIn(1,6)).put("variants",runs).toString(2))
        report("golden",summary)
        // Scores are measurements, not pass/fail; only check that every call produced Korean text.
        for(i in 0 until runs.length()) runs.getJSONObject(i).optJSONArray("calls")?.let { c -> for(j in 0 until c.length())
            assertTrue("no Korean in ${c.getJSONObject(j).getString("id")}",c.getJSONObject(j).getString("text").any{it in '가'..'힣'}) }
    }

    /** The two switches the golden set compares belong to the v24-stt work (feature/r6-v24-stt): F3 silence floor as a
     * parameter of the speech-only cut, F4 initial prompt on the Whisper session. They are not in this code yet, so the
     * variants are listed but marked unavailable (no reflection). When that work is merged, set [FLOOR] / [PROMPT] to true
     * and fill in the two marked lines below; nothing else in the test changes. */
    private object GoldenSwitches {
        /** F3 (#4): true once the floor can be passed to the speech-only cut. */
        const val FLOOR=true
        /** F4 (#5): true once the Whisper session takes an initial prompt. */
        const val PROMPT=true
        class Variant(val name:String,val floor:Float?,val prompt:Boolean,val available:Boolean,val why:String)
        /** "base" first (today's defaults: floor 0.006 in PipelineAudioMath, no prompt), then one change at a time. */
        val VARIANTS=listOf(
            Variant("base",null,false,true,""),
            Variant("floor0025",0.0025f,false,FLOOR,"silence floor switch not in this build yet (v24-stt)"),
            Variant("prompt",null,true,PROMPT,"prompt switch not in this build yet (v24-stt)"))
        /** null = the app's own decode. With [FLOOR]: decode without the cut, then PipelineAudioMath.speechOnly(audio, floor) (fill in). */
        fun decode(bytes:ByteArray,floor:Float?):PipelineDecodedAudio {
            val chunks=ArrayList<Pair<Long,FloatArray>>()
            // The decoder lends its buffer for each piece, so keep a copy.
            val info=PipelineAudioDecoder(speechFloor=floor ?: PipelineAudioMath.SPEECH_FLOOR).decode(PipelineBytesInput(bytes),"audio/mp4"){_,offset,pcm->chunks+=offset to pcm.copyOf()}
            return PipelineDecodedAudio(info.durationMs,chunks,info.vadApplied)
        }
        /** null = no prompt. With [PROMPT]: pass [prompt] to the transcriber/session (fill in). */
        fun transcriber(context:android.content.Context,model:PipelineSttModel,prompt:String?,deadline:Int):CallTranscriber {
            return WhisperCallTranscriber(AndroidWhisperEngine(context),model,deadlineSeconds=deadline,prompt=prompt ?: "")
        }
    }

    /** F6: a 30-minute synthetic call (the public TTS sentence with pauses, encoded on the device as m4a/AAC like a Samsung
     * recording) goes through the app's own runner piece by piece, with one stop part way (the worker being stopped) and a
     * resume. The model is stood in for (the emulator runs Whisper large ~60x slower than a phone; memory is what is checked
     * here). Peak Java heap is logged for the new path and for the v2.3 way (whole file copied, every 16 kHz piece kept,
     * then the speech copied out); the new peak must be at most a quarter. No real recording is used. */
    @Test fun t6_longCallStreamsWithoutRunningOutOfMemory() {
        val minutes=args.getString("longCallMinutes")?.toIntOrNull() ?: 30
        val file=File(context.cacheDir,"device-test-long-call.m4a")
        val started=System.currentTimeMillis()
        val audioMs=synthCallM4a(file,minutes*60)
        val made=System.currentTimeMillis()-started
        val result=JSONObject().put("minutes",minutes).put("fileBytes",file.length()).put("encodeSeconds",made/1000)
            .put("heapLimitMb",Runtime.getRuntime().maxMemory()/1048576)
        try {
            assertTrue("synthetic file over the vault limit",file.length() in 1..25_000_000)
            // New path: the bytes held as the app holds the vault copy, the real runner, a stand-in model.
            val key=CallTranscriptionStore.Key("device-test-long-$started",1,"a".repeat(64))
            val transcribed=ArrayList<Int>();var stopAt=7
            val transcriber=CallTranscriber { _,pcm,_ -> transcribed+=pcm.size;HeapPeak.held();Thread.sleep(150);listOf(CallTranscript.Segment(0,pcm.size*1000L/16000,"조각")) }
            val fake=object:PipelineSttModel { override val modelSha256="a".repeat(64);override val runtimeSha256="b".repeat(64);override val modelName="stand-in";override fun verifiedModelFile()=file }
            var info:PipelineAudioInfo?=null;var segments=0;var stopped=false;var resumedPieces=0
            val newPeak=HeapPeak.measure {
                CallTranscriptionStore(storage).use { store ->
                    fun runner()=CallTranscriptionRunner(store,fake,PipelineAudioDecoder(),transcriber,read={error("unused")},skipReason={null},sink={null},stopped={transcribed.size>stopAt})
                    fun input()=file.inputStream().use{PipelineBytesInput.read(it,file.length())}
                    try { input().use{runner().transcribeRecording(key,it,"audio/mp4",file)} } catch(_:CallTranscriptionRunner.Stopped) { stopped=true }
                    val before=transcribed.size;stopAt=Int.MAX_VALUE
                    val (i,s)=input().use{runner().transcribeRecording(key,it,"audio/mp4",file)}
                    info=i;segments=s.size;resumedPieces=transcribed.size-before
                    store.clearParts(key)
                }
            }
            val pieces=checkNotNull(info).chunks
            result.put("pieces",pieces).put("segments",segments).put("vadApplied",info!!.vadApplied).put("durationMs",info!!.durationMs)
                .put("stoppedOnce",stopped).put("piecesAfterResume",resumedPieces).put("newPeakMb",newPeak/1048576.0).put("newSampledMb",HeapPeak.lastSampled/1048576.0)
            Log.i("IEUM_HEAP","long call new path peak ${newPeak/1048576} MB for $minutes min, $pieces pieces")
            // The v2.3 way, for comparison (it may run out of memory: that counts as the whole heap).
            var oldOom=false
            val oldPeak=try { HeapPeak.measure {
                val bytes=java.io.ByteArrayOutputStream().also{ out -> file.inputStream().use{it.copyTo(out)} }.toByteArray()
                val all=ArrayList<Pair<Long,FloatArray>>()
                PipelineAudioDecoder().decodeRaw(PipelineBytesInput(bytes),false){ms,x,from,to->all+=ms to x.copyOfRange(from,to)} // v2.3 kept every piece
                val kept=PipelineAudioMath.speechOnly(PipelineDecodedAudio(audioMs,all,false))
                result.put("oldPieces",kept.chunks.size);bytes.size+all.size+kept.chunks.size
            } } catch(e:OutOfMemoryError) { oldOom=true;System.gc();Runtime.getRuntime().maxMemory() }
            result.put("oldPeakMb",oldPeak/1048576.0).put("oldOutOfMemory",oldOom).put("ratio",newPeak.toDouble()/oldPeak)
            Log.i("IEUM_HEAP","long call old path peak ${oldPeak/1048576} MB${if(oldOom)" (out of memory)" else ""}")
            report("t6_long_call",result)
            assertTrue("stop part way did not happen",stopped);assertTrue(info!!.durationMs in audioMs-1000..audioMs+1000)
            assertEquals("every piece transcribed once, the stopped one again",pieces+1,transcribed.size)
            if(!oldOom)assertEquals(result.getInt("oldPieces"),pieces)
            assertTrue("new peak ${newPeak/1048576} MB is over a quarter of the old ${oldPeak/1048576} MB",newPeak*4<=oldPeak)
        } finally { file.delete() }
    }

    /** Peak live Java heap above the start while [block] runs. The number compared is taken where the app needs memory
     * most, while a piece is being transcribed: the stand-in transcriber calls [held], which collects garbage on the decoding
     * thread itself, so nothing is being allocated meanwhile and the number is exactly what the code holds (the vault copy,
     * the chunker's buffer, the piece). A sampler thread also collects every 100 ms and its peak is kept as [lastSampled]
     * for the log only: with the decoder allocating meanwhile it counts garbage made during each collection (98 MB
     * sampled on the emulator for 30 minutes). For a block that never calls [held] (the old way) the sampled peak is used. */
    private object HeapPeak {
        private fun used()=Runtime.getRuntime().let{it.totalMemory()-it.freeMemory()}
        private val heldPeak=java.util.concurrent.atomic.AtomicLong(-1)
        @Volatile var lastSampled=0L;private set
        fun held(){ if(heldPeak.get()<0)return;System.gc();System.runFinalization();System.gc();heldPeak.accumulateAndGet(used(),::maxOf) }
        fun measure(block:()->Any?):Long {
            repeat(3){System.gc();System.runFinalization()};Thread.sleep(200)
            val base=used();val peak=java.util.concurrent.atomic.AtomicLong(base);val running=java.util.concurrent.atomic.AtomicBoolean(true)
            heldPeak.set(0)
            val sampler=Thread{ while(running.get()){ System.gc();peak.accumulateAndGet(used(),::maxOf);Thread.sleep(100) } }.apply{isDaemon=true;start()}
            try { block() } finally { running.set(false);sampler.join() }
            System.gc();lastSampled=maxOf(0L,maxOf(peak.get(),used())-base)
            val h=heldPeak.getAndSet(-1)
            return if(h>0) maxOf(0L,h-base) else lastSampled
        }
    }

    /** [seconds] of synthetic call audio as m4a/AAC 44.1 kHz mono 64 kbit/s (MediaCodec + MediaMuxer, moov at the end like a
     * recorder): the public TTS sentence, quieter or louder each time, then 4 s of faint noise. Returns the length in ms. */
    private fun synthCallM4a(file:File,seconds:Int,rate:Int=44100):Long {
        // One sentence + pause cycle, resampled to [rate] once (the per-sample loop was too slow on the emulator).
        val one=publicKorean();val cycle16=one.size+16000*4
        fun at16(i:Int):Float=if(i<one.size) one[i] else (((i.toLong()*7919)%200)-100)/100000f
        val cycle=(cycle16.toLong()*rate/16000).toInt();val speech=(one.size.toLong()*rate/16000).toInt()
        val base=FloatArray(cycle){ k -> val p=k*16000.0/rate;val i0=p.toInt();val a=at16(i0);a+(at16(minOf(i0+1,cycle16-1))-a)*(p-i0).toFloat() }
        val block=ShortArray(8192)
        val total=rate.toLong()*seconds
        val format=android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC,rate,1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE,64000);setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE,16384) }
        val codec=android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format,null,null,android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
        val muxer=android.media.MediaMuxer(file.path,android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track=-1;var written=0L;var inputDone=false;var outputDone=false
        val info=android.media.MediaCodec.BufferInfo()
        try {
            while(!outputDone){
                if(!inputDone){
                    val i=codec.dequeueInputBuffer(10_000)
                    if(i>=0){
                        val buf=codec.getInputBuffer(i)!!;buf.clear();buf.order(java.nio.ByteOrder.nativeOrder())
                        val frames=minOf(minOf(buf.remaining()/2,block.size).toLong(),total-written).toInt()
                        if(frames<=0){codec.queueInputBuffer(i,0,0,written*1_000_000/rate,android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else {
                            for(k in 0 until frames){ val t=written+k;val j=(t%cycle).toInt();val c=t/cycle
                                val v=if(j<speech) base[j]*(0.6f+0.04f*((c*7919)%10)) else base[j]
                                block[k]=(v.coerceIn(-1f,1f)*32767).toInt().toShort() }
                            buf.asShortBuffer().put(block,0,frames)
                            codec.queueInputBuffer(i,0,frames*2,written*1_000_000/rate,0);written+=frames
                        }
                    }
                }
                val o=codec.dequeueOutputBuffer(info,10_000)
                if(o==android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=muxer.addTrack(codec.outputFormat);muxer.start()}
                else if(o>=0){
                    val buf=codec.getOutputBuffer(o)!!
                    if(info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0)info.size=0
                    if(info.size>0&&track>=0){buf.position(info.offset);buf.limit(info.offset+info.size);muxer.writeSampleData(track,buf,info)}
                    codec.releaseOutputBuffer(o,false)
                    if(info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)outputDone=true
                }
            }
        } finally { runCatching{codec.stop()};codec.release();runCatching{muxer.stop()};muxer.release() }
        return total*1000/rate
    }

    /** For the report: where the model came from. A full-size partial file is the runner's cached copy;
     * [PipelineModelManager.download] checks its size and SHA-256 before publishing it, exactly as for a network download. */
    private fun seedFromRunnerCache(model:PipelineModelManager):String = when {
        model.verifiedModelFile()!=null -> "present"
        model.partFile.isFile&&model.partFile.length()==spec.bytes -> "runner copy"
        else -> "network"
    }

    private companion object {
        /** Quick-run model: whisper.cpp multilingual base, q5_1. Pinned from huggingface.co/ggerganov/whisper.cpp
         * (file size and LFS SHA-256), the same source as the app's own model. Tests only; the app never uses it. */
        val SMALL_MODEL=PipelineModelManager.Spec("ggml-base-q5_1",59707625L,
            "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin")
    }
}
