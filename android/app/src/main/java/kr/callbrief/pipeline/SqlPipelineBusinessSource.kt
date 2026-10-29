package kr.callbrief.pipeline

import android.content.Context

/** Lane C implementation of the C1 `business` seam over the app's own protected tables.
 * Call off the main thread. The returned block passes PipelineContract C1 validation. */
internal class SqlPipelineBusinessSource(private val context:Context):PipelineBusinessSource {
    override fun business(recordingId:String):Map<String,Any?> = PipelineApplyStore(context).use { it.business(recordingId) }
}
