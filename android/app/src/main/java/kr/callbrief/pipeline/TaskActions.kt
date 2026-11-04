package kr.callbrief.pipeline

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screens do with a to-do, shared by home, the check list and the company page. */
internal object TaskActions {
    /** [다 됐어요]: the row fades, a "되돌리기" bar stays about five seconds. Undo brings the to-do back; otherwise [reload] runs when the bar goes
     * and the row leaves the list. Done is always the user's own tap (the AI only ever marks "확인 필요"). */
    fun complete(a:ComponentActivity,item:OpenItem,row:View,reload:()->Unit) {
        a.lifecycleScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching {
                CallRecordStore(CallPipelineStartup.storage(a)).use { it.completeItem(item.id,item.revision) } }.isSuccess }
            if(a.isFinishing||a.isDestroyed)return@launch
            if(!ok) { Toast.makeText(a,"바꾸지 못했어요. 다시 시도해 주세요.",Toast.LENGTH_SHORT).show();reload();return@launch }
            row.isEnabled=false;row.animate().alpha(0.35f).setDuration(120).start()
            CallUi.snack(a,"다 됐어요로 옮겼어요",onAction={ undo(a,item,row,reload) },onGone={ if(!a.isFinishing&&!a.isDestroyed) reload() })
        }
    }
    private fun undo(a:ComponentActivity,item:OpenItem,row:View,reload:()->Unit) {
        a.lifecycleScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching {
                CallRecordStore(CallPipelineStartup.storage(a)).use { it.undoDone(item.id,item.status,item.revision+1) } }.isSuccess }
            if(a.isFinishing||a.isDestroyed)return@launch
            if(!ok) Toast.makeText(a,"되돌리지 못했어요.",Toast.LENGTH_SHORT).show() else row.animate().alpha(1f).setDuration(120).start()
            reload()
        }
    }

    /** Opens the call a to-do came from (found through the to-do ids each call recorded). Nothing happens for a to-do no call made. */
    fun openSource(a:ComponentActivity,item:OpenItem) {
        a.lifecycleScope.launch {
            val found=withContext(Dispatchers.IO) { runCatching {
                CallRecordStore(CallPipelineStartup.storage(a)).use { s ->
                    val day=CallRecordStore.kstDay(item.createdMs)
                    listOf(day,day.minusDays(1)).firstNotNullOfOrNull { d -> s.day(d).firstOrNull { c -> c.applied.any { it.itemId==item.id } } } } }.getOrNull() }
            if(a.isFinishing||a.isDestroyed)return@launch
            if(found==null) { Toast.makeText(a,"이 체크할 일을 만든 통화를 찾지 못했어요.",Toast.LENGTH_SHORT).show();return@launch }
            a.startActivity(Intent(a,TodayCallsActivity::class.java).putExtra(TodayCallsActivity.EXTRA_CALL,found.recordId).putExtra(TodayCallsActivity.EXTRA_DAY_MS,found.dayMs))
        }
    }

    /** Priority for the short list on home: ones to check first, then the newest. */
    fun ordered(all:List<OpenItem>)=all.sortedWith(compareByDescending<OpenItem> { it.status=="needs_confirmation" }.thenByDescending { it.createdMs })
}
