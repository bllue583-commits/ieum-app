package kr.callbrief

import android.net.Uri
import java.time.Instant

data class RecordingRef(val uri: Uri, val name: String, val size: Long, val mime: String?, val startedAt: Instant?, val modifiedAt: Long)

/** "Before the first pass starts" for a file in the folder (design A3, 3.2 #1): the time in the recorder's filename
 * decides when there is one (a moved or restored file gets a new modified time), otherwise the modified time. */
internal object DiscoveryCutoff {
    fun startedAtMs(name:String):Long? = kr.callbrief.core.RecordingNames.startedAt(name)?.toEpochMilli()
    /** [cutoffMs]<=0 means no cutoff. An unknown modified time (<=0) never counts as old. */
    fun before(name:String,modified:Long,cutoffMs:Long):Boolean {
        if(cutoffMs<=0)return false
        val named=startedAtMs(name)
        return if(named!=null) named<cutoffMs else modified in 1 until cutoffMs
    }
}
