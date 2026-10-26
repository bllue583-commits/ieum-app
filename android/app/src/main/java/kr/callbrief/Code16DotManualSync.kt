package kr.callbrief

import android.os.Looper
import kr.callagent.drive.ManualDotSync
import kr.callagent.drive.StrictJson
import kr.co.callsummary.core.android.AndroidSqlPort

/** Additive integration adapter. Caller owns UI/thread, existing initialized protected DB and exact approved synthetic jobs. */
internal class Code16DotManualSync(
    private val session:GoogleConnectionSession,
    private val pipeline:GoogleNativePipeline,
    private val protectedPort:AndroidSqlPort,
    private val operatorHandoff:((String,String)->Unit)?=null
) {
    private val observed=mutableMapOf<String,GoogleRelayStore.ResultObservation>()
    fun resultObservation(job:String)=observed[job]
    private fun scope():ManualDotSync.Scope {
        val s=session.nativeSessionBinding();val owner=s.getJSONObject("ownerBinding")
        return ManualDotSync.Scope(owner.getString("ownerId"),owner.getString("driveAccount"),session.driveOwnerPermissionId(),s.getString("sessionId"),s.getLong("epoch"),true)
    }
    private val port=object:ManualDotSync.Port {
        override fun currentScope()=scope()
        override fun currentJobs(approved:Set<String>):List<ManualDotSync.Job> = pipeline.jobs().filter { it.id in approved }.map { job ->
            if(!pipeline.accountSupported())return@map ManualDotSync.Job.unavailable(job.id,job.phase,job.remoteState,"DOT_PROFILE_ACCOUNT_UNSUPPORTED")
            try {
                val registration=pipeline.registration(job.id)
                check(registration.getJSONObject("state").optBoolean("syntheticAudioApproved")) { "SYNTHETIC_SOURCE_APPROVAL_REQUIRED" }
                val bytes=StrictJson.bytes(StrictJson.`object`(StrictJson.parse(registration.toString().toByteArray(Charsets.UTF_8))))
                ManualDotSync.Job(job.id,job.phase,job.remoteState,registration.getString("requestDigest"),String(bytes,Charsets.UTF_8),kr.callagent.drive.DriveRelay.sha256(bytes))
            } catch(_:IllegalStateException) { ManualDotSync.Job.unavailable(job.id,job.phase,job.remoteState) }
        }
        override fun pollReadOnly(jobId:String)=when(val value=pipeline.pollObservation(jobId).also { observed[jobId]=it }.disposition) {
            "READ_ONLY_CANDIDATE","PROVENANCE_PENDING","LEGACY_CANDIDATE","DUPLICATE_PROPOSAL" -> "CANDIDATE_STORED"
            "CANCELLED" -> "REMOTE_CANCELLED"
            else -> value
        }
        override fun retryFailed(jobId:String)=pipeline.retry(jobId).jobId
    }
    private val store=object:ManualDotSync.Store {
        override fun <T> transact(s:ManualDotSync.Scope,action:ManualDotSync.Store.Action<T>):T = session.commit(s.epoch,s.ownerId) {
            check(s.same(scope())) { "OWNER_SESSION_CHANGED" };protectedPort.requireProtectedPersistence()
            protectedPort.transaction {
                protectedPort.execute("CREATE TABLE IF NOT EXISTS dot_manual_sync_rows(owner TEXT NOT NULL,scope TEXT NOT NULL,job TEXT NOT NULL,digest TEXT NOT NULL,status TEXT NOT NULL,handoff TEXT NOT NULL,registration_sha TEXT NOT NULL,PRIMARY KEY(owner,scope,job))")
                protectedPort.execute("CREATE TABLE IF NOT EXISTS dot_manual_sync_cursor(owner TEXT NOT NULL,scope TEXT NOT NULL,cursor TEXT NOT NULL,PRIMARY KEY(owner,scope))")
                val tx=object:ManualDotSync.Store.Tx {
                    override fun get(job:String):ManualDotSync.Record? = protectedPort.query("SELECT digest,status,handoff,registration_sha FROM dot_manual_sync_rows WHERE owner=? AND scope=? AND job=?",s.ownerId,s.key(),job).singleOrNull()?.let { row ->
                        ManualDotSync.Record().apply { digest=row["digest"].toString();status=row["status"].toString();handoff=row["handoff"].toString();registrationSha=row["registration_sha"].toString() }
                    }
                    override fun put(job:String,r:ManualDotSync.Record) {
                        val old=get(job);check(old==null||old.digest==r.digest) { "IMMUTABLE_REQUEST_CHANGED" }
                        if(old==null)protectedPort.execute("INSERT INTO dot_manual_sync_rows VALUES(?,?,?,?,?,?,?)",s.ownerId,s.key(),job,r.digest,r.status,r.handoff,r.registrationSha)
                        else protectedPort.execute("UPDATE dot_manual_sync_rows SET status=?,handoff=?,registration_sha=? WHERE owner=? AND scope=? AND job=?",r.status,r.handoff,r.registrationSha,s.ownerId,s.key(),job)
                    }
                    override fun cursor()=protectedPort.query("SELECT cursor FROM dot_manual_sync_cursor WHERE owner=? AND scope=?",s.ownerId,s.key()).singleOrNull()?.get("cursor")?.toString() ?: ""
                    override fun cursor(job:String) {
                        if(protectedPort.query("SELECT cursor FROM dot_manual_sync_cursor WHERE owner=? AND scope=?",s.ownerId,s.key()).isEmpty())protectedPort.execute("INSERT INTO dot_manual_sync_cursor VALUES(?,?,?)",s.ownerId,s.key(),job)
                        else protectedPort.execute("UPDATE dot_manual_sync_cursor SET cursor=? WHERE owner=? AND scope=?",job,s.ownerId,s.key())
                    }
                }
                action.run(tx).also { check(s.same(scope()));protectedPort.requireProtectedPersistence() }
            }
        }
    }
    private val handoff=object:ManualDotSync.Handoff {
        override fun supportedEntry()=if(operatorHandoff==null)ManualDotSync.Entry.NONE else ManualDotSync.Entry.FOREGROUND_OPERATOR_HANDOFF
        override fun offer(s:ManualDotSync.Scope,j:ManualDotSync.Job) {
            check(s.same(scope()));checkNotNull(operatorHandoff).invoke(j.id,j.registrationJson)
        }
    }
    private val coordinator=ManualDotSync(port,store,handoff)
    fun syncExplicitSynthetic(approvedJobIds:Set<String>,budget:Int=4):List<ManualDotSync.Result> {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "DRIVE_IO_THREAD_REQUIRED" }
        return coordinator.sync(approvedJobIds,budget)
    }
    fun retryExplicit(jobId:String,approvedJobIds:Set<String>):String {
        check(Looper.myLooper()!=Looper.getMainLooper()) { "DRIVE_IO_THREAD_REQUIRED" }
        return coordinator.retry(jobId,approvedJobIds)
    }
}
