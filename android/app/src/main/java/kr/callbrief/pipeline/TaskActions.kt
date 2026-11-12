package kr.callbrief.pipeline

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screens do with a to-do, shared by home, the check list and the company page. */
internal object TaskActions {
    /** Words of the undo bar after [다 됐어요] (G3). */
    const val DONE_WORDS="끝낸 일로 옮겼어요"
    /** Items whose [다 됐어요] is being saved; a second press of the same item (another row, a quick double tap) is ignored. */
    private val inFlight=HashSet<String>()
    fun inFlight(id:String)=synchronized(inFlight) { id in inFlight }
    /** The store writes, replaceable in tests (to hold the write back or make it fail). They run on an IO thread. */
    internal var completeIo:(Context,OpenItem)->Unit={ c,item -> kr.callbrief.IeumApplication.store(c).completeItem(item.id,item.revision) }
    internal var undoIo:(Context,OpenItem)->Unit={ c,item -> kr.callbrief.IeumApplication.store(c).undoDone(item.id,item.status,item.revision+1) }
    internal fun resetIo() {
        completeIo={ c,item -> kr.callbrief.IeumApplication.store(c).completeItem(item.id,item.revision) }
        undoIo={ c,item -> kr.callbrief.IeumApplication.store(c).undoDone(item.id,item.status,item.revision+1) }
        synchronized(inFlight) { inFlight.clear() }
    }

    /** [다 됐어요] (review #4): the moment it is pressed the row and its button go off and pale (0.35) and the "되돌리기" bar comes up;
     * then the store is written. If writing fails the row comes back as it was, the bar goes and a short message says so.
     * Undo brings the to-do back (after the write, when it is still running); otherwise [reload] runs when the bar goes and the
     * row leaves the list. Done is always the user's own tap (the AI only ever marks "확인 필요"). */
    fun complete(a:ComponentActivity,item:OpenItem,row:View,reload:()->Unit) {
        if(!synchronized(inFlight) { inFlight.add(item.id) }) return
        val app=a.applicationContext
        CallUi.enableAll(row,false);row.animate().cancel();row.alpha=0.35f
        val saved=CompletableDeferred<Boolean>()
        var undone=false;var gone=false
        CallUi.snack(a,DONE_WORDS,onAction={ undone=true;undo(a,item,row,saved,reload) },onGone={ gone=true;if(saved.isCompleted && !a.isFinishing && !a.isDestroyed) reload() })
        a.lifecycleScope.launch {
            val ok=withContext(Dispatchers.IO) { runCatching { completeIo(app,item) }.isSuccess }
            synchronized(inFlight) { inFlight.remove(item.id) }
            saved.complete(ok)
            if(ok) UseStats.add(UseStats.TASK_DONE) // v3.7 r17-3: in memory, written when the app goes to the background
            if(a.isFinishing||a.isDestroyed)return@launch
            if(!ok) {
                CallUi.hideSnack(a)
                CallUi.enableAll(row,true);row.alpha=1f
                Toast.makeText(a,"바꾸지 못했어요. 다시 눌러 주세요.",Toast.LENGTH_SHORT).show()
            } else if(gone && !undone) reload() // the bar timed out while the store was still writing

        }
    }
    private fun undo(a:ComponentActivity,item:OpenItem,row:View,saved:CompletableDeferred<Boolean>,reload:()->Unit) {
        val app=a.applicationContext
        // Back at once on the screen; the store follows when the first write is done.
        CallUi.enableAll(row,true);row.alpha=1f
        a.lifecycleScope.launch {
            if(!saved.await()) return@launch // nothing was written, so nothing to take back
            val ok=withContext(Dispatchers.IO) { runCatching { undoIo(app,item) }.isSuccess }
            if(a.isFinishing||a.isDestroyed)return@launch
            if(!ok) Toast.makeText(a,"되돌리지 못했어요.",Toast.LENGTH_SHORT).show()
            reload()
        }
    }


    /** Priority for the short list on home: ones to check first, then the newest. */
    fun ordered(all:List<OpenItem>)=all.sortedWith(compareByDescending<OpenItem> { it.status=="needs_confirmation" }.thenByDescending { it.createdMs })
}
