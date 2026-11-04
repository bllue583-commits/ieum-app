package kr.callbrief.pipeline

import android.content.Context
import android.os.StatFs
import android.util.Log
import org.json.JSONObject

/** The phone's heartbeat for the mailbox (design B1): one small file, status/phone.json, that the watchdog reads.
 * Numbers, times and short codes only. Never a name, a number, a file name or any call content. */
internal object PhoneStatus {
    const val PATH="status/phone.json"
    private const val TAG="ieum.status"
    /** Settings keys written by other parts of the app; read by name so this file does not depend on them. */
    private const val LAST_UPDATE_ERROR="lastUpdateError"

    /** Everything the file says; [oldestQueuedMs] is the age start of the oldest recording still waiting for its text. */
    data class Input(val nowMs:Long,val appVersion:String,val versionCode:Long,val queuedCount:Int,val oldestQueuedMs:Long?,
        val lastTranscribedMs:Long,val lastResultMs:Long,
        val modelState:String,val lastInboxError:String,val lastUpdateError:String,val storageUsedPercent:Int)

    fun build(i:Input):JSONObject {
        val o=JSONObject().put("atMs",i.nowMs).put("appVersion",i.appVersion).put("versionCode",i.versionCode)
            .put("queuedCount",i.queuedCount).put("oldestQueuedMs",i.oldestQueuedMs ?: JSONObject.NULL)
            .put("lastTranscribedMs",i.lastTranscribedMs).put("lastResultMs",i.lastResultMs)
            .put("modelState",code(i.modelState)).put("storageUsedPercent",i.storageUsedPercent)
        // Absent when there is nothing to report, so the watchdog's "has an error" test is just a key test.
        code(i.lastInboxError).takeIf { it.isNotEmpty() }?.let { o.put("lastInboxError",it) }
        code(i.lastUpdateError).takeIf { it.isNotEmpty() }?.let { o.put("lastUpdateError",it) }
        return o
    }
    /** Error values are codes (INBOX_HTTP_401, STATUS_FAILURE_...). Anything longer or odder is cut so free text cannot leak. */
    internal fun code(raw:String):String = raw.trim().take(60).filter { it.code<128 && it.isLetterOrDigit() || it=='_' || it=='-' || it==':' }

    /** Where [report] sends; a test swaps in a fake mailbox. */
    @Volatile internal var reportStore:()->CallInboxStore? ={ CallInboxConfig.store() }
    /** "문제 알리기" (design B5): the heartbeat now, on the caller's background thread. False when it could not be sent. */
    fun report(context:Context,now:Long=System.currentTimeMillis()):Boolean = try {
        val store=reportStore() ?: return false
        store.replace(PATH,build(collect(context.applicationContext,now)).toString());true
    } catch(e:Exception) { Log.w(TAG,"report not sent: ${e.javaClass.simpleName}");false }

    /** Reads the current state and writes the file. Never throws: the hourly tick must not fail because of this. */
    fun publish(context:Context,store:CallInboxStore? =CallInboxConfig.store(),now:Long=System.currentTimeMillis()) {
        try {
            store ?: return
            store.replace(PATH,build(collect(context.applicationContext,now)).toString())
        } catch(e:Exception) { Log.w(TAG,"heartbeat not sent: ${e.javaClass.simpleName}") }
    }

    private fun collect(app:Context,now:Long):Input {
        val s=kr.callbrief.SecureSettings(app).snapshot()
        var queued=0;var oldest:Long?=null
        runCatching {
            CallTranscriptionStore(app).use { t ->
                val waiting=t.rows().filter { it.second=="QUEUED" }.map { it.first }.toSet()
                queued=waiting.size
                if(waiting.isNotEmpty()) oldest=t.candidates().filter { it.key in waiting }
                    .mapNotNull { (it.startedAtMs ?: it.modifiedMs).takeIf { ms -> ms>0 } }.minOrNull()
            }
        }.onFailure { Log.w(TAG,"queue not read: ${it.javaClass.simpleName}") }
        val info=runCatching { app.packageManager.getPackageInfo(app.packageName,0) }.getOrNull()
        @Suppress("DEPRECATION") val code=info?.let { if(android.os.Build.VERSION.SDK_INT>=28) it.longVersionCode else it.versionCode.toLong() } ?: 0L
        return Input(now,info?.versionName ?: "",code,queued,oldest,
            lastTranscribedMs = s.optLong(PipelineSettingsKeys.LAST_TRANSCRIBED_MS,0L),
            lastResultMs = s.optLong(PipelineSettingsKeys.LAST_ANALYZED_MS,0L),
            modelState = s.optString(PipelineSettingsKeys.MODEL_STATE,""),
            lastInboxError = s.optString(PipelineSettingsKeys.LAST_INBOX_ERROR,""),
            lastUpdateError = s.optString(LAST_UPDATE_ERROR,""),
            storageUsedPercent = storagePercent(app))
    }
    private fun storagePercent(app:Context):Int = runCatching {
        val st=StatFs(app.filesDir.path);val total=st.totalBytes
        if(total<=0) 0 else (100-st.availableBytes*100/total).toInt().coerceIn(0,100)
    }.getOrDefault(0)
}
