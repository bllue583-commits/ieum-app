package kr.callbrief.pipeline

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "할 일": every open to-do, grouped by the day it was asked for, each with one "완료" button.
 * Completion is always the user's explicit action (the AI only ever marks "확인 필요"). */
class CallTasksActivity:ComponentActivity() {
    private lateinit var body:LinearLayout
    private var items:List<OpenItem> =emptyList()

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        body=CallUi.page(this,CallUi.Tab.TASKS)
    }
    override fun onResume() { super.onResume();load() }

    private fun load() {
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { CallRecordStore(CallPipelineStartup.storage(this@CallTasksActivity)).use { it.openItems() } } }
            if(isFinishing||isDestroyed)return@launch
            items=result.getOrDefault(emptyList());render(result.isSuccess)
        }
    }

    /** Grouped by when they were asked for, newest first; ones the AI thinks are finished come first for a check. */
    internal fun groups(all:List<OpenItem>):List<Pair<String,List<OpenItem>>> {
        val today=CallUi.today()
        fun day(i:OpenItem)=CallRecordStore.kstDay(i.createdMs)
        val check=all.filter { it.status=="needs_confirmation" }
        val rest=(all-check.toSet()).sortedByDescending { it.createdMs }
        return listOf("끝났는지 확인해 주세요" to check,"오늘 요청한 일" to rest.filter { day(it)==today },
            "어제 요청한 일" to rest.filter { day(it)==today.minusDays(1) },"그전에 요청한 일" to rest.filter { day(it)<today.minusDays(1) })
            .filter { it.second.isNotEmpty() }
    }

    private fun render(ok:Boolean) {
        body.removeAllViews()
        CallUi.band(body,"체크할 일",if(ok) "확인할 일이 ${items.size}개 남았어요" else null)
        if(!ok) { body.addView(CallUi.text(this,"체크할 일을 읽지 못했어요. 잠시 후 다시 열어주세요."));return }
        if(items.isEmpty()) { body.addView(CallUi.card(this).apply { addView(CallUi.text(this@CallTasksActivity,"체크할 일이 없어요.",CallUi.SUMMARY,color=CallUi.OK)) });return }
        for((name,list) in groups(items)) {
            body.addView(CallUi.section(this,name,"${list.size}개"))
            list.forEach { item ->
                body.addView(CallUi.taskRow(this,item.title,item.createdMs,item.company,item.counterpart,item.status) { complete(item) }.apply { tag="task_${item.id}" })
            }
        }
    }

    private fun complete(item:OpenItem) {
        lifecycleScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching {
                CallRecordStore(CallPipelineStartup.storage(this@CallTasksActivity)).use { it.completeItem(item.id,item.revision) }
            }.isSuccess }
            Toast.makeText(this@CallTasksActivity,if(ok) "확인했어요. 목록에서 뺐어요." else "바꾸지 못했어요. 다시 시도해 주세요.",Toast.LENGTH_SHORT).show();load()
        }
    }
}
