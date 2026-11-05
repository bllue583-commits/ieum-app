package kr.callbrief.pipeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kr.callbrief.IeumApplication
import java.util.concurrent.Executors

/** The [다 됐어요] button of the morning report (design 4.2 r8-6): the item named in the body is finished without opening the
 * app, and the report then names the next one (or says everything is done, with no button). Undo stays in the app. */
internal object NotificationActions {
    const val ACTION_DONE="kr.callbrief.action.TASK_DONE"
    const val EXTRA_ITEM="itemId";const val EXTRA_REVISION="revision"
    /** [메모 남기기] on a call's follow-up notice (r10-2, [CallFollowupNotice]): one typed line under [KEY_MEMO] becomes the user's
     * own check item. [EXTRA_RECORD]: the call record when known; [EXTRA_FOLLOWUP]: the notice id. */
    const val ACTION_MEMO="kr.callbrief.action.CALL_MEMO"
    const val KEY_MEMO="memo";const val EXTRA_RECORD="recordId";const val EXTRA_FOLLOWUP="followupId"
    /** Request code of the button's PendingIntent: one per item, so the next item's button never rewrites the extras of a
     * press already on its way (a press always carries the item it was shown for). */
    const val REQUEST_DONE=701
    fun requestCode(itemId:String)=REQUEST_DONE xor itemId.hashCode()
    private const val TAG="ieum.notify.action"

    /** What the report is made of now: the open check items and the issues. Replaceable in tests. */
    internal var read:(Context)->Pair<List<CallTodoItem>,List<Issue>> ={ c -> IeumApplication.store(c).let { it.openTodos() to it.issues() } }
    internal fun resetForTest() { read={ c -> IeumApplication.store(c).let { it.openTodos() to it.issues() } };synchronized(handled) { handled.clear() } }

    /** Presses already taken ("id:revision"): a second press of the same button does nothing more. */
    private val handled=HashSet<String>()

    fun doneIntent(context:Context,itemId:String,revision:Long)=Intent(context,NotificationActionReceiver::class.java)
        .setAction(ACTION_DONE).setPackage(context.packageName).putExtra(EXTRA_ITEM,itemId).putExtra(EXTRA_REVISION,revision)

    /** Runs off the main thread. Returns true when this press finished the item (false for a repeated or stale press or a failure).
     * The report is posted again in every case, so it always shows what is still open. Never throws. */
    fun handle(context:Context,intent:Intent):Boolean {
        if(intent.action==ACTION_MEMO) return CallFollowupNotice.handleMemo(context,intent)
        if(intent.action!=ACTION_DONE) return false
        val id=intent.getStringExtra(EXTRA_ITEM) ?: return false
        val revision=intent.getLongExtra(EXTRA_REVISION,-1L)
        val key="$id:$revision"
        if(!synchronized(handled) { handled.add(key) }) return false
        var done=false
        try {
            val (items,_)=read(context)
            // Only the item as the report showed it: done already (in the app, or by an earlier press) or changed since means nothing to do.
            val item=items.firstOrNull { it.id==id && it.revision==revision }
            if(item!=null) {
                TaskActions.completeIo(context,OpenItem(item.id,item.title,item.due,item.status,item.revision,"",null,item.company,item.createdMs,item.aiNote,item.tentative))
                done=true
            }
        } catch(e:Exception) {
            Log.w(TAG,"not done: ${e.javaClass.simpleName}") // class only, never a title
            synchronized(handled) { handled.remove(key) } // the button stays, a later press tries again
        }
        refresh(context)
        return done
    }

    /** Posts the morning report again from the store, quietly (no sound for an update). */
    fun refresh(context:Context) {
        try {
            val (items,issues)=read(context)
            val m=if(items.isEmpty()) CallDigestText.allDone(issues) else CallDigestText.morning(items,CallUi.today(),issues)
            CallDigestNotifier.post(context,CallDigestSchedule.MORNING,m,refresh=true)
        } catch(e:Exception) { Log.w(TAG,"report not refreshed: ${e.javaClass.simpleName}") }
    }
}

/** Registered in the manifest (not exported); only the report's own PendingIntent reaches it. */
class NotificationActionReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val app=context.applicationContext ?: context
        val pending=goAsync()
        // One at a time: two quick presses are taken in order, and the second finds the item done.
        worker.execute { try { NotificationActions.handle(app,intent) } finally { runCatching { pending?.finish() } } }
    }
    private companion object { val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"ieum-notify-action").apply { isDaemon=true } } }
}
