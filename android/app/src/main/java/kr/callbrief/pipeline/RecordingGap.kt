package kr.callbrief.pipeline

import android.content.Context
import org.json.JSONObject

/** DESIGN_V4 2.1 (v3.1, r12-1): are calls being recorded? Key-commit contract; r12-1 writes the judgement and the words.
 * The verdict codes are [PipelineSettingsKeys.GAP_OK] and friends; the last result is kept under [PipelineSettingsKeys.RECORDING_GAP]. */
internal object RecordingGap {
    /** One judgement: n target calls, how many had a file in their window, how many of those paired with a record. */
    data class Verdict(val code:String,val calls:Int=0,val withFile:Int=0,val matched:Int=0,val newestFileMs:Long?=null,val sinceMs:Long=0,val atMs:Long=0)
    /** The one line under the home numbers (null: nothing to say); tapping it opens settings at the recording row. */
    data class Banner(val text:String)
    /** r12-1: the last stored verdict, or null before the first judgement. */
    fun last(s:JSONObject):Verdict? = null
    /** r12-1: the home banner — only for noRecording, partial and folder, and only from the second judgement 6 hours apart. */
    fun banner(c:Context):Banner? = null
    /** r12-1: the orange text of the "녹음 가져오기" light, or null when the light keeps its own words. */
    fun lightText(s:JSONObject):String? = null
}
