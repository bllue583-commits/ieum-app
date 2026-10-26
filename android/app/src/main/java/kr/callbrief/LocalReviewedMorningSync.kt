package kr.callbrief

import android.content.Context
import kr.callagent.drive.DriveRelay
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase

/** A real durable checkpoint of the current local reviewed store, not external collection.
 * The source receipt transaction must finish before COMPLETE/durable can escape this method. */
internal class LocalReviewedMorningSync(private val context:Context,private val owner:String,
    private val current:()->Boolean,private val source:ReviewedDailySource=CanonicalReviewedDailySource(context),
    private val fault:()->Unit={}):MorningIncrementalSyncPort {
    override suspend fun sync(request:MorningSyncRequest):MorningSyncResult {
        if(!current() || !ProtectedDatabase.file(context,GeneralLocalStore.DATABASE).isFile)return MorningSyncResult(MorningSyncState.UNAVAILABLE)
        val cursor=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use{db->
            db.requireProtectedPersistence();db.transaction {
                check(current()){"LOCAL_SYNC_SCOPE_CHANGED"}
                db.execute("CREATE TABLE IF NOT EXISTS integrated_sync_checkpoints(id TEXT PRIMARY KEY,owner TEXT NOT NULL,generation INTEGER NOT NULL,from_ms INTEGER NOT NULL,until_ms INTEGER NOT NULL,prior_cursor TEXT NOT NULL,basis TEXT NOT NULL,cursor TEXT NOT NULL)")
                for(a in listOf("UPDATE","DELETE"))db.execute("CREATE TRIGGER IF NOT EXISTS integrated_sync_${a.lowercase()} BEFORE $a ON integrated_sync_checkpoints BEGIN SELECT RAISE(ABORT,'IMMUTABLE_HISTORY');END")
                val old=db.query("SELECT * FROM integrated_sync_checkpoints WHERE id=?",request.id).singleOrNull()
                if(old!=null) {
                    check(old["owner"]==owner && old["generation"]==request.generation && old["from_ms"]==request.from.toEpochMilli() && old["until_ms"]==request.until.toEpochMilli() && old["prior_cursor"]==request.durableCursor){"LOCAL_SYNC_REQUEST_CONFLICT"}
                    return@transaction old["cursor"].toString()
                }
                val input=source.snapshot(ReportWindow(request.until.atZone(MorningReportWindow.KST).toLocalDate(),MorningReportWindow.KST,request.from,request.until))
                // A successful read of this local store can prove zero local rows. It is not a
                // reviewed summary or complete external inventory; coverage stays PARTIAL.
                val knownLocalEmpty=input.readiness==ReportReadiness.NO_RECORDINGS && input.expectedCount==0 && input.sources.isEmpty()
                if(input.pendingCount>0 || input.unresolved.isNotEmpty() || !(input.fullyReviewed || knownLocalEmpty))return@transaction null
                input.sources.forEach{it.validate()}
                val basis=input.basisHash
                val committed="local-reviewed-v1:"+DriveRelay.sha256(("$owner\n${request.id}\n${request.generation}\n$basis").toByteArray())
                db.execute("INSERT INTO integrated_sync_checkpoints VALUES(?,?,?,?,?,?,?,?)",request.id,owner,request.generation,request.from.toEpochMilli(),request.until.toEpochMilli(),request.durableCursor,basis,committed)
                fault();check(current()){"LOCAL_SYNC_SCOPE_CHANGED"};committed
            }
        }
        return if(cursor==null)MorningSyncResult(MorningSyncState.ACCEPTED) else MorningSyncResult(MorningSyncState.COMPLETE,cursor,true)
    }
}
