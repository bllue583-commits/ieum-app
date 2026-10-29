package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase

/** Read-only count for the main screen badge. Opens nothing that does not already exist. */
internal object PipelineReviewBadge {
    fun count(context:Context):Int {
        if(!ProtectedDatabase.file(context,GeneralLocalStore.DATABASE).exists())return 0
        return AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { port ->
            if(port.query("SELECT name FROM sqlite_master WHERE type='table' AND name='pipeline_review_items'").isEmpty())0
            else ((port.query("SELECT count(*) AS n FROM pipeline_review_items WHERE state='OPEN'").single()["n"] as? Number)?.toInt() ?: 0)+
                ((port.query("SELECT count(*) AS n FROM pipeline_analyses WHERE state='AWAITING_CASE'").single()["n"] as? Number)?.toInt() ?: 0)
        }
    }
}
