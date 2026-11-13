package kr.callbrief.pipeline

import android.content.Context
import org.json.JSONObject

/** v4.0 r20-2 (DESIGN_V7 6, SCORECARD items 4 and 10): how often [틀렸어요] was pressed and taken back, as running counts since
 * v4.0 — numbers only (which items are kept in the store as [CallRecordStore.WRONG] and go up to the morning tidy as ids in
 * `wrong[]`, never with words). The heartbeat's `feedback{wrong, undone}`. A press is rare, so it is written at once (one sealed
 * write on the IO thread of the press). */
internal object TaskFeedback {
    /** Sealed settings key: {"wrong": n, "undone": n}. */
    const val KEY="taskFeedback"
    const val WRONG="wrong";const val UNDONE="undone"
    /** Wrong items the morning tidy is told about: marked in this many last days. */
    const val STATE_DAYS=14L

    /** Adds to the counts (any background thread; never throws). */
    fun add(context:Context,wrong:Long=0,undone:Long=0) {
        if(wrong<=0 && undone<=0) return
        runCatching { kr.callbrief.SecureSettings(CallPipelineStartup.storage(context)).update { s ->
            val o=stored(s)
            s.put(KEY,JSONObject().put(WRONG,o.optLong(WRONG,0)+wrong.coerceAtLeast(0)).put(UNDONE,o.optLong(UNDONE,0)+undone.coerceAtLeast(0)).toString())
        } }.onFailure { android.util.Log.w("ieum.feedback","not counted: ${it.javaClass.simpleName}") }
    }
    fun stored(s:JSONObject?):JSONObject = when(val v=s?.opt(KEY)) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrDefault(JSONObject())
        else -> JSONObject() }
    /** `feedback{wrong, undone}`: always both, zeros before the first press. */
    fun heartbeat(s:JSONObject?):JSONObject { val o=stored(s)
        return JSONObject().put(WRONG,o.optLong(WRONG,0).coerceAtLeast(0)).put(UNDONE,o.optLong(UNDONE,0).coerceAtLeast(0)) }
}
