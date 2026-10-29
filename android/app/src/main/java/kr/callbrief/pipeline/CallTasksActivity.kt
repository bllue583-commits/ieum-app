package kr.callbrief.pipeline

import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.callbrief.RecordingWorkflowStore

/** "할 일": every open to-do, the urgent ones first, each with one big "완료" button.
 * Completion is always the user's explicit action (the AI only ever marks "확인 필요"). */
class CallTasksActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var items:List<OpenItem> =emptyList()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        body=CallUi.page(this)
    }
    override fun onResume() { super.onResume();load() }

    private fun load() {
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallTasksActivity)).use { it.openItems() } } }
            if(isFinishing||isDestroyed)return@launch
            items=result.getOrDefault(emptyList());render(result.isSuccess)
        }
    }

    internal fun groups(all:List<OpenItem>):List<Pair<String,List<OpenItem>>> {
        val today=CallUi.today().toString()
        fun iso(i:OpenItem)=Regex("\\d{4}-\\d{2}-\\d{2}").matches(i.due)
        val overdue=all.filter{iso(it)&&it.due<today}.sortedBy{it.due}
        val dueToday=all.filter{iso(it)&&it.due==today}
        val check=all.filter{it.status=="needs_confirmation"&&it !in overdue&&it !in dueToday}
        val later=all.filter{iso(it)&&it.due>today&&it !in check}.sortedBy{it.due}
        val none=all.filter{it !in overdue&&it !in dueToday&&it !in check&&it !in later}
        return listOf("기한 지난 일" to overdue,"오늘까지" to dueToday,"끝났는지 확인해 주세요" to check,"다가오는 일" to later,"기한 없는 일" to none).filter{it.second.isNotEmpty()}
    }

    private fun render(ok:Boolean) {
        body.removeAllViews()
        body.addView(CallUi.title(this,"할 일"))
        if(!ok) { body.addView(CallUi.text(this,"할 일을 읽지 못했어요. 잠시 후 다시 열어주세요."));return }
        if(items.isEmpty()) { body.addView(CallUi.text(this,"남은 할 일이 없어요.",CallUi.SUMMARY));return }
        body.addView(CallUi.text(this,"남은 할 일 ${items.size}개",CallUi.SUMMARY,color=CallUi.SUBTLE))
        for((name,list) in groups(items)) {
            body.addView(CallUi.heading(this,"$name (${list.size})"))
            list.forEach { item ->
                val late=CallUi.overdue(item.due)
                val c=CallUi.card(this,warn=late).apply { tag="task_${item.id}" }
                c.addView(CallUi.text(this,item.title,CallUi.SUMMARY,true))
                val meta=listOfNotNull(CallUi.dueWords(item.due),CallUi.taskStatus(item.status).ifEmpty{null},item.counterpart?.let{"$it 통화"}).joinToString(" · ")
                c.addView(CallUi.text(this,meta,CallUi.BODY,color=if(late) CallUi.WARN else CallUi.SUBTLE))
                c.addView(CallUi.button(this,"완료했어요") { complete(item) }.apply { tag="done_${item.id}" })
                body.addView(c)
            }
        }
    }

    private fun complete(item:OpenItem) {
        lifecycleScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching {
                RecordingWorkflowStore(CallPipelineStartup.storage(this@CallTasksActivity)).use { it.editItem(item.id,item.revision,item.title,"done",item.due,item.edition) }
            }.isSuccess }
            Toast.makeText(this@CallTasksActivity,if(ok) "완료로 바꿨어요." else "바꾸지 못했어요. 다시 시도해 주세요.",Toast.LENGTH_SHORT).show();load()
        }
    }
}
