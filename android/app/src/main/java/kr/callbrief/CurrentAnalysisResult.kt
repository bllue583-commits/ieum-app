package kr.callbrief

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kr.co.callsummary.core.android.ProtectedDatabase
import org.json.JSONObject

/** Current immutable result evidence only. These reads never advance workflow state
 * or treat worker content as a user approval or proof of model execution. */
internal object CurrentAnalysisResult {
    fun latestRequest(db:SQLiteDatabase,id:String,version:Long,audio:String):JSONObject? {
        if(!db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='workflow_analysis_requests'",null).use { it.moveToFirst() })return null
        return db.rawQuery("SELECT id,state,binding_json,job_id FROM workflow_analysis_requests WHERE recording_id=? AND state!='CANCELLED' ORDER BY created_ms DESC,rowid DESC",arrayOf(id)).use { requests ->
            var found:JSONObject?=null
            while(found==null && requests.moveToNext()) {
                val binding=JSONObject(requests.getString(2))
                if(binding.optLong("sourceVersion")==version && binding.optString("audioSha")==audio)
                    found=JSONObject().put("id",requests.getString(0)).put("state",requests.getString(1)).put("job",requests.getString(3))
            };found
        }
    }
    // Catalog calls this inside its existing protected transaction. ProtectedDatabase
    // returns the same scoped handle, keeping the file/version and receipt read atomic.
    fun read(context:Context,databaseName:String,id:String,version:Long):JSONObject? =
        ProtectedDatabase.open(context,databaseName,null).use { store ->
            val db=store.database()
            ProtectedDatabase.read(db) {
                val audio=db.rawQuery("SELECT v.audio_sha256 FROM recordings r JOIN source_versions v ON v.recording_id=r.recording_id AND v.source_version=r.source_version WHERE r.recording_id=? AND r.source_version=? AND r.owner_key=?",arrayOf(id,version.toString(),GeneralLocalStore.OWNER)).use { c ->
                    if(c.moveToFirst())c.getString(0) else null
                } ?: return@read JSONObject().put("state","BINDING_REVIEW").put("digest","")
                read(context,db,id,version,audio,latestRequest(db,id,version,audio))
            }
        }
    /** The workflow observer can lag intake. Revalidate the immutable receipt against its
     * existing frozen job, scoped to this owner/job/source/result file, without advancing any cursor. */
    fun read(context:Context,db:android.database.sqlite.SQLiteDatabase,id:String,version:Long,audio:String,request:JSONObject?):JSONObject? {
        val jobId=request?.optString("job")?.takeIf { it.isNotBlank() && it!="null" } ?: return null
        fun held()=JSONObject().put("state","BINDING_REVIEW").put("digest","")
        return try {
            val owner=(context as? AccountStorageContext)?.ownerId ?: return held()
            if(!db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='drive_profiles_receipts'",null).use { it.moveToFirst() })return held()
            val active=db.rawQuery("SELECT job_id FROM drive_v0_active WHERE source_id=?",arrayOf(id)).use { if(it.moveToFirst())it.getString(0) else null }
            if(active!=jobId)return held()
            val jobs=kr.callagent.drive.SqliteJobStore(db) { _,_->null }
            val job=jobs.transaction { it.job(jobId) } ?: return held()
            if(job.source.ownerBinding!=owner || job.source.sourceKey!=id || job.source.sourceVersion!=version || job.source.audioSha!=audio)return held()
            db.rawQuery("SELECT payload,digest,result_revision,kind,schema_version,conflict,hidden FROM drive_profiles_receipts WHERE owner_id=? AND job_id=? AND result_id=? ORDER BY result_revision DESC LIMIT 1",arrayOf(owner,jobId,job.resultFileId)).use { c->
                if(!c.moveToFirst())return held()
                val decoded=kr.callagent.drive.ProfileResultDispatcher().validate(c.getBlob(0),job)
                if(decoded.digest!=c.getString(1) || decoded.revision!=c.getLong(2) || decoded.kind!=c.getString(3) || decoded.schemaVersion!=c.getLong(4))return held()
                val collision=db.rawQuery("SELECT 1 FROM drive_profiles_collisions WHERE owner_id=? AND job_id=? AND result_revision>=? LIMIT 1",arrayOf(owner,jobId,decoded.revision.toString())).use { it.moveToFirst() }
                JSONObject().put("state",if(collision || decoded.proposal() && (c.getInt(5)!=0 || c.getInt(6)!=0))"BINDING_REVIEW" else decoded.state).put("digest",decoded.digest)
            }
        } catch(_:Exception) { held() }
    }
}
