package kr.callbrief.pipeline

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.AutomaticRecordingDiscovery
import kr.callbrief.MainActivity
import kr.callbrief.SecureSettings

/** App home: what happened today at a glance, the first-run checklist until setup is done,
 * and three big doors (오늘의 통화 / 할 일 / 설정). Older features sit behind "기타 기능". */
class HomeActivity:ComponentActivity() {
    internal data class Snapshot(val folder:Boolean,val key:Boolean,val consent:Boolean,val model:String,
                                 val calls:Int,val working:Int,val newTasks:Int,val due:Int,val overdue:Int,val lastAnalyzedMs:Long)
    private lateinit var body:LinearLayout

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        body=CallUi.page(this)
        CallPipelineStartup.init(this)
    }
    override fun onResume() { super.onResume();load() }

    private fun load() {
        lifecycleScope.launch {
            val snap=withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            if(isFinishing||isDestroyed)return@launch
            render(snap)
        }
    }

    private fun read():Snapshot {
        val storage=CallPipelineStartup.storage(this)
        val s=SecureSettings(storage).snapshot()
        val folder=s.optString("treeUri").isNotBlank()
        if(folder) {
            runCatching { AutomaticRecordingDiscovery.ensure(storage) }
            runCatching { CallTranscriptionWork.schedule(storage) }
        }
        val today=CallUi.today()
        val (calls,due,newTasks)=CallRecordStore(storage).use { Triple(it.day(today),it.dueItems(today),it.newTaskCount(today)) }
        return Snapshot(folder,s.optString(PipelineSettingsKeys.CLAUDE_API_KEY).isNotBlank(),s.optBoolean(PipelineSettingsKeys.CONSENT_GRANTED,false),
            s.optString(PipelineSettingsKeys.MODEL_STATE),calls.size,calls.count{it.state!=CallRecordStore.ANALYZED},newTasks,
            due.size,due.count{CallUi.overdue(it.due)},s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0))
    }

    private fun open(target:Class<*>,setup:Intent.()->Unit={}) = startActivity(Intent(this,target).apply(setup))

    internal fun render(s:Snapshot?) {
        body.removeAllViews()
        body.addView(CallUi.title(this,"이음").apply { tag="home_title" })
        body.addView(CallUi.text(this,CallUi.date(CallUi.today())+" · 오늘의 통화를 대신 기억해요",CallUi.SMALL,color=CallUi.SUBTLE))

        if(s==null) { body.addView(CallUi.text(this,"정보를 읽지 못했어요. 잠시 후 다시 열어주세요."));doors();return }

        val steps=listOf(s.folder,s.key,s.consent,s.model=="READY")
        if(!steps.all{it}) {
            val c=CallUi.card(this,warn=true).apply { tag="home_setup" }
            c.addView(CallUi.text(this,"처음 설정 ${steps.count{it}}/4",CallUi.HEADING,true))
            fun step(n:Int,done:Boolean,label:String,detail:String,action:()->Unit) {
                c.addView(CallUi.text(this,(if(done) "✓ " else "$n. ")+label,CallUi.BODY,true,if(done) CallUi.OK else CallUi.INK))
                if(!done) { c.addView(CallUi.text(this,detail,CallUi.SMALL,color=CallUi.SUBTLE));c.addView(CallUi.button(this,"$label 하기",action)) }
            }
            step(1,s.folder,"녹음 폴더 연결","휴대폰의 통화 녹음 폴더를 한 번 골라주세요.") { open(MainActivity::class.java){ putExtra("open_settings",true) } }
            step(2,s.key,"Claude 키 넣기","통화를 요약하는 데 쓰는 키예요. 설정 화면에 붙여 넣으면 돼요.") { open(CallSettingsActivity::class.java) }
            step(3,s.consent,"정리 동의","녹음은 휴대폰에 그대로 두고, 글자로 바꾼 내용만 보내 정리해요.") { open(CallSettingsActivity::class.java) }
            step(4,s.model=="READY","음성 인식 모델 받기",
                if(s.model=="DOWNLOADING") "받는 중이에요. Wi-Fi에 연결해 두세요. (약 570MB)" else "Wi-Fi에서 한 번만 받아요. (약 570MB)") {
                PipelineModelDownloadWork.enqueue(this);android.widget.Toast.makeText(this,"Wi-Fi에 연결되면 받기 시작해요.",android.widget.Toast.LENGTH_LONG).show();load()
            }
            body.addView(c)
        }

        val today=CallUi.card(this).apply { tag="home_today" }
        today.addView(CallUi.text(this,"오늘",CallUi.HEADING,true))
        today.addView(CallUi.text(this,"통화 ${s.calls}건"+(if(s.working>0) " (정리 중 ${s.working}건)" else ""),CallUi.SUMMARY,true).apply { tag="home_calls" })
        today.addView(CallUi.text(this,"새로 생긴 할 일 ${s.newTasks}개",CallUi.SUMMARY))
        today.addView(CallUi.text(this,"오늘까지 할 일 ${s.due}개"+(if(s.overdue>0) " · 기한 지난 일 ${s.overdue}개" else ""),CallUi.SUMMARY,color=if(s.overdue>0) CallUi.WARN else CallUi.INK))
        today.addView(CallUi.text(this,"마지막 정리: "+CallUi.dateTime(s.lastAnalyzedMs),CallUi.SMALL,color=CallUi.SUBTLE))
        body.addView(today)
        doors()
    }

    private fun doors() {
        body.addView(CallUi.bigButton(this,"📞","오늘의 통화") { open(TodayCallsActivity::class.java) }.apply { tag="home_calls_button" })
        body.addView(CallUi.bigButton(this,"✅","할 일") { open(CallTasksActivity::class.java) }.apply { tag="home_tasks_button" })
        body.addView(CallUi.bigButton(this,"⚙️","설정") { open(CallSettingsActivity::class.java) }.apply { tag="home_settings_button" })
        body.addView(CallUi.gap(this,16))
        body.addView(CallUi.button(this,"기타 기능 (예전 화면)") { open(MainActivity::class.java) }.apply { tag="home_more_button" })
    }
}
