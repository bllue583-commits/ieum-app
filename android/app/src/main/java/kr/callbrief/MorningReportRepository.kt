package kr.callbrief

import android.database.sqlite.SQLiteDatabase
import kr.callbrief.kakao.core.DeliveryCoordinator
import kr.co.callsummary.core.android.ProtectedDatabase
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Report-owned additive tables in the existing sealed DailyReportLedger, no source schema changes. */
internal class MorningReportRepository(private val db:SQLiteDatabase,private val profile:String,
    private val cipher:ReportPayloadCipher,private val enabled:(Long)->Boolean) {
    companion object {
        fun initialize(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS morning_runtime(id INTEGER PRIMARY KEY CHECK(id=1),generation INTEGER NOT NULL,cursor TEXT NOT NULL,ingest_cursor TEXT NOT NULL DEFAULT '',last_success_ms INTEGER NOT NULL DEFAULT 0,lease TEXT NOT NULL DEFAULT '',lease_boot INTEGER NOT NULL DEFAULT 0,lease_until INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS morning_jobs(report_key TEXT PRIMARY KEY,report_day TEXT NOT NULL,kind TEXT NOT NULL,generation INTEGER NOT NULL,state TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,fingerprint TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0,retry_ms INTEGER NOT NULL DEFAULT 0,retry_boot INTEGER NOT NULL DEFAULT 0,retry_elapsed INTEGER NOT NULL DEFAULT 0,observed_ms INTEGER,note TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE TABLE IF NOT EXISTS morning_revisions(report_key TEXT NOT NULL,revision INTEGER NOT NULL,fingerprint TEXT NOT NULL,body_hash TEXT NOT NULL,payload BLOB NOT NULL,created_ms INTEGER NOT NULL,PRIMARY KEY(report_key,revision))")
            db.execSQL("CREATE TABLE IF NOT EXISTS morning_outbox(id TEXT PRIMARY KEY,report_key TEXT NOT NULL,revision INTEGER NOT NULL,generation INTEGER NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,retry_ms INTEGER NOT NULL DEFAULT 0,retry_boot INTEGER NOT NULL DEFAULT 0,retry_elapsed INTEGER NOT NULL DEFAULT 0,destination TEXT NOT NULL,body_hash TEXT NOT NULL,claim TEXT NOT NULL DEFAULT '',claim_boot INTEGER NOT NULL DEFAULT 0,claim_until INTEGER NOT NULL DEFAULT 0,receipt TEXT NOT NULL DEFAULT '',UNIQUE(report_key,revision,destination))")
            db.execSQL("CREATE TABLE IF NOT EXISTS morning_sync(id TEXT PRIMARY KEY,generation INTEGER NOT NULL,from_ms INTEGER NOT NULL,until_ms INTEGER NOT NULL,prior_cursor TEXT NOT NULL,reason TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,retry_ms INTEGER NOT NULL DEFAULT 0,retry_boot INTEGER NOT NULL DEFAULT 0,retry_elapsed INTEGER NOT NULL DEFAULT 0)")
            for(action in listOf("UPDATE","DELETE"))db.execSQL("CREATE TRIGGER IF NOT EXISTS morning_revision_${action.lowercase()} BEFORE $action ON morning_revisions BEGIN SELECT RAISE(ABORT,'MORNING_HISTORY_IMMUTABLE'); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS morning_revision_no_replace BEFORE INSERT ON morning_revisions WHEN EXISTS(SELECT 1 FROM morning_revisions WHERE report_key=NEW.report_key AND revision=NEW.revision) BEGIN SELECT RAISE(ABORT,'MORNING_HISTORY_IMMUTABLE'); END")
        }
    }
    private fun <T> tx(block:()->T):T=ProtectedDatabase.transaction(db) { ProtectedDatabase.requireSealedPersistence(db);block() }
    private fun <T> read(block:()->T):T=ProtectedDatabase.read(db) { ProtectedDatabase.requireSealedPersistence(db);block() }
    private fun text(sql:String,args:Array<String> = emptyArray()):String?=db.rawQuery(sql,args).use { if(it.moveToFirst())it.getString(0) else null }
    fun reset(generation:Long,now:Instant)=tx {
        db.execSQL("UPDATE morning_jobs SET state='CANCELLED',note='SCHEDULE_GENERATION_CANCELLED' WHERE state!='CANCELLED'")
        db.execSQL("UPDATE morning_outbox SET state=CASE WHEN state='IN_FLIGHT' THEN 'UNKNOWN' ELSE 'CANCELLED' END,claim='' WHERE state NOT IN ('ACKNOWLEDGED','UNKNOWN','DRY_RUN_RECORDED','CANCELLED')")
        db.execSQL("UPDATE morning_sync SET state='CANCELLED' WHERE state IN ('PENDING','ACCEPTED','FAILED','DEFERRED')")
        db.execSQL("INSERT OR REPLACE INTO morning_runtime(id,generation,cursor) VALUES(1,?,?)",arrayOf<Any?>(generation,MorningReportWindow.latestDue(now).toString()))
    }
    fun lease(generation:Long,env:ReportEnvironment):String?=tx {
        if(!enabled(generation))return@tx null
        if(text("SELECT cursor FROM morning_runtime WHERE id=1")==null)
            db.execSQL("INSERT INTO morning_runtime(id,generation,cursor) VALUES(1,?,?)",arrayOf<Any?>(generation,MorningReportWindow.latestDue(env.now).toString()))
        val token=UUID.randomUUID().toString()
        db.execSQL("UPDATE morning_runtime SET lease=?,lease_boot=?,lease_until=? WHERE id=1 AND generation=? AND (lease='' OR lease_boot!=? OR lease_until<=?)",
            arrayOf<Any?>(token,env.boot,env.elapsed+120_000,generation,env.boot,env.elapsed))
        if(text("SELECT lease FROM morning_runtime WHERE id=1")!=token)return@tx null
        db.execSQL("UPDATE morning_outbox SET state='UNKNOWN',claim='' WHERE state='IN_FLIGHT' AND (claim_boot!=? OR claim_until<=?)",arrayOf<Any?>(env.boot,env.elapsed))
        token
    }
    fun valid(generation:Long,token:String,env:ReportEnvironment):Boolean=read {
        enabled(generation) && text("SELECT lease FROM morning_runtime WHERE id=1 AND generation=? AND lease_boot=? AND lease_until>?",
            arrayOf(generation.toString(),env.boot.toString(),env.elapsed.toString()))==token
    }
    fun release(token:String)=tx { db.execSQL("UPDATE morning_runtime SET lease='',lease_until=0 WHERE id=1 AND lease=?",arrayOf(token)) }
    fun cursor():LocalDate=read { LocalDate.parse(requireNotNull(text("SELECT cursor FROM morning_runtime WHERE id=1"))) }
    fun advance(day:LocalDate,generation:Long,token:String,env:ReportEnvironment)=tx {
        check(valid(generation,token,env));db.execSQL("UPDATE morning_runtime SET cursor=? WHERE id=1",arrayOf<Any?>(day.toString()))
    }
    fun allocate(window:MorningReportWindow,generation:Long,token:String,env:ReportEnvironment):MorningReportRow=tx {
        check(valid(generation,token,env));val key=window.key(profile)
        db.execSQL("INSERT OR IGNORE INTO morning_jobs(report_key,report_day,kind,generation,state) VALUES(?,?,?,?,'PENDING')",arrayOf<Any?>(key,window.reportDay.toString(),window.kind.name,generation))
        requireNotNull(get(key))
    }
    fun get(key:String):MorningReportRow?=read { db.rawQuery("SELECT * FROM morning_jobs WHERE report_key=?",arrayOf(key)).use { c ->
        if(!c.moveToFirst())return@use null
        fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
        fun n(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
        MorningReportRow(key,MorningReportWindow(LocalDate.parse(s("report_day")),MorningReportKind.valueOf(s("kind"))),n("generation"),s("state"),n("revision").toInt(),s("fingerprint"),n("attempts").toInt(),Instant.ofEpochMilli(n("retry_ms")),
            if(c.isNull(c.getColumnIndexOrThrow("observed_ms")))null else Instant.ofEpochMilli(n("observed_ms")),s("note"))
    } }
    fun reports():List<MorningReportRow> = read { db.rawQuery("SELECT report_key FROM morning_jobs ORDER BY report_day DESC,kind",null).use { c->buildList { while(c.moveToNext())get(c.getString(0))?.let(::add) } } }
    private fun retryDue(table:String,keyColumn:String,key:String,env:ReportEnvironment)=db.rawQuery("SELECT retry_ms,retry_boot,retry_elapsed FROM $table WHERE $keyColumn=?",arrayOf(key)).use { c->
        c.moveToFirst();if(c.getLong(1)==env.boot)c.getLong(2)<=env.elapsed else c.getLong(0)<=env.now.toEpochMilli()
    }
    fun buildDue(row:MorningReportRow,env:ReportEnvironment)=read { row.state!="CANCELLED" && row.attempts<3 && retryDue("morning_jobs","report_key",row.key,env) }
    fun fail(row:MorningReportRow,generation:Long,token:String,env:ReportEnvironment,permanent:Boolean)=tx {
        if(!valid(generation,token,env))return@tx
        val attempt=if(permanent)3 else row.attempts+1;val delay=30_000L*(1L shl (attempt-1).coerceAtLeast(0))
        db.execSQL("UPDATE morning_jobs SET state='FAILED',attempts=?,retry_ms=?,retry_boot=?,retry_elapsed=?,note=? WHERE report_key=? AND generation=? AND state!='CANCELLED'",
            arrayOf<Any?>(attempt,env.now.toEpochMilli()+delay,env.boot,env.elapsed+delay,if(permanent)"INVALID_SNAPSHOT_MANUAL_REVIEW" else "SNAPSHOT_READ_FAILED",row.key,generation))
    }
    fun commit(row:MorningReportRow,snapshot:MorningSnapshot,generation:Long,token:String,env:ReportEnvironment):Boolean=tx {
        check(valid(generation,token,env));val current=requireNotNull(get(row.key))
        check(current.generation==generation && current.state!="CANCELLED" && !env.now.isBefore(row.window.due))
        snapshot.validated()
        if(current.fingerprint==snapshot.fingerprint) {
            db.execSQL("UPDATE morning_jobs SET state=?,attempts=0,retry_ms=0,retry_elapsed=0,note=? WHERE report_key=?",
                arrayOf<Any?>(if(snapshot.coverage==MorningCoverage.COMPLETE)"GENERATED_COMPLETE" else "GENERATED_PARTIAL",if(current.note=="MANUAL_RETRY")"MANUAL_REFRESH_NO_VISIBLE_CHANGE" else current.note,row.key));return@tx false
        }
        val revision=current.revision+1;val body=snapshot.text(row.window);require(body.toByteArray(Charsets.UTF_8).size<=500_000)
        val hash=DeliveryCoordinator.sha256(body);val aad="$profile\nMORNING_V1\n${row.key}\n$revision\n${snapshot.fingerprint}"
        val anyPayload=text("SELECT report_key FROM morning_revisions LIMIT 1")!=null || text("SELECT id FROM reports WHERE payload IS NOT NULL UNION ALL SELECT id FROM partial_reports LIMIT 1")!=null
        val payload=cipher.seal(body,aad,anyPayload)
        db.execSQL("INSERT INTO morning_revisions VALUES(?,?,?,?,?,?)",arrayOf<Any?>(row.key,revision,snapshot.fingerprint,hash,payload,env.now.toEpochMilli()))
        db.execSQL("UPDATE morning_outbox SET state='CANCELLED' WHERE report_key=? AND state IN ('OUTBOX_READY','RETRY_WAIT','FAILED')",arrayOf(row.key))
        val destination="local-preview";val id=DeliveryCoordinator.sha256("morning-outbox-v1\n${row.key}\n$revision\n$destination\n$hash")
        db.execSQL("INSERT INTO morning_outbox(id,report_key,revision,generation,state,destination,body_hash) VALUES(?,?,?,?,'OUTBOX_READY',?,?)",arrayOf<Any?>(id,row.key,revision,generation,destination,hash))
        db.execSQL("UPDATE morning_jobs SET state=?,revision=?,fingerprint=?,attempts=0,retry_ms=0,retry_elapsed=0,observed_ms=?,note=? WHERE report_key=?",
            arrayOf<Any?>(if(snapshot.coverage==MorningCoverage.COMPLETE)"GENERATED_COMPLETE" else "GENERATED_PARTIAL",revision,snapshot.fingerprint,env.now.toEpochMilli(),
                if(env.now.isAfter(row.window.due.plusSeconds(60)))"DELAYED_${env.trigger}" else "LOCAL_COMMIT_NOT_DELIVERY",row.key));true
    }
    fun body(key:String,revision:Int):String=read {
        db.rawQuery("SELECT fingerprint,body_hash,payload FROM morning_revisions WHERE report_key=? AND revision=?",arrayOf(key,revision.toString())).use { c->
            check(c.moveToFirst());val body=cipher.open(c.getBlob(2),"$profile\nMORNING_V1\n$key\n$revision\n${c.getString(0)}")
            check(DeliveryCoordinator.sha256(body)==c.getString(1));body
        }
    }
    fun outbox():List<MorningOutboxRow> = read { db.rawQuery("SELECT * FROM morning_outbox ORDER BY rowid",null).use { c->buildList {
        while(c.moveToNext()) {
            fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
            fun n(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
            add(MorningOutboxRow(s("id"),s("report_key"),n("revision").toInt(),n("generation"),MorningDeliveryState.valueOf(s("state")),n("attempts").toInt(),Instant.ofEpochMilli(n("retry_ms")),s("destination"),s("body_hash"),s("claim"),n("claim_boot"),n("claim_until"),s("receipt")))
        }
    } } }
    fun claim(row:MorningOutboxRow,generation:Long,token:String,env:ReportEnvironment):MorningOutboxRow?=tx {
        if(!valid(generation,token,env) || row.generation!=generation)return@tx null
        val current=outbox().first { it.id==row.id }
        val report=get(row.reportKey) ?: return@tx null
        if(env.now.isBefore(report.window.due) || !report.state.startsWith("GENERATED_") || report.revision!=row.revision)return@tx null
        if(current.state !in listOf(MorningDeliveryState.OUTBOX_READY,MorningDeliveryState.RETRY_WAIT) || current.attempts>=3 || !retryDue("morning_outbox","id",row.id,env))return@tx null
        val claim=UUID.randomUUID().toString()
        db.execSQL("UPDATE morning_outbox SET state='IN_FLIGHT',claim=?,claim_boot=?,claim_until=?,attempts=attempts+1 WHERE id=?",arrayOf<Any?>(claim,env.boot,env.elapsed+120_000,row.id))
        outbox().first { it.id==row.id }
    }
    fun complete(row:MorningOutboxRow,result:MorningDeliveryResult,generation:Long,token:String,env:ReportEnvironment)=tx {
        if(!valid(generation,token,env))return@tx
        val current=outbox().first { it.id==row.id }
        if(current.state!=MorningDeliveryState.IN_FLIGHT || current.claim!=row.claim || current.claimBoot!=env.boot || current.claimUntil<=env.elapsed)return@tx
        val matched=result.key==row.id && result.destination==row.destination && result.bodyHash==row.bodyHash && result.receipt.isNotBlank()
        val state=when(result.state) {
            MorningDeliveryState.ACKNOWLEDGED->if(matched)result.state else MorningDeliveryState.UNKNOWN
            MorningDeliveryState.DRY_RUN_RECORDED,MorningDeliveryState.UNKNOWN,MorningDeliveryState.FAILED,MorningDeliveryState.CANCELLED->result.state
            MorningDeliveryState.RETRY_WAIT->if(current.attempts<3)result.state else MorningDeliveryState.FAILED
            else->MorningDeliveryState.UNKNOWN
        }
        val delay=30_000L*(1L shl (current.attempts-1))
        db.execSQL("UPDATE morning_outbox SET state=?,claim='',retry_ms=?,retry_boot=?,retry_elapsed=?,receipt=? WHERE id=?",
            arrayOf<Any?>(state.name,env.now.toEpochMilli()+delay,env.boot,env.elapsed+delay,if(state==MorningDeliveryState.ACKNOWLEDGED)result.receipt else "",row.id))
    }
    fun manualRetry(key:String,generation:Long,unknownRiskConfirmed:Boolean=false)=tx {
        check(enabled(generation));val row=requireNotNull(get(key));check(row.generation==generation && row.state!="CANCELLED")
        db.execSQL("UPDATE morning_jobs SET state='PENDING',attempts=0,retry_ms=0,retry_elapsed=0,note='MANUAL_RETRY' WHERE report_key=?",arrayOf(key))
        db.execSQL("UPDATE morning_outbox SET state='OUTBOX_READY',attempts=0,retry_ms=0,retry_elapsed=0,claim='' WHERE report_key=? AND generation=? AND (state IN ('FAILED','RETRY_WAIT') OR (state='UNKNOWN' AND ?=1))",arrayOf<Any?>(key,generation,if(unknownRiskConfirmed)1 else 0))
    }
    fun ingestCursor():String=read { text("SELECT ingest_cursor FROM morning_runtime WHERE id=1").orEmpty() }
    fun syncRequest(slot:String,reason:String,from:Instant,until:Instant,generation:Long,token:String,env:ReportEnvironment):MorningSyncRequest?=tx {
        check(valid(generation,token,env))
        // Finish accepted/deferred work with its original request ID/cursor before starting another slot.
        val pending=text("SELECT id FROM morning_sync WHERE generation=? AND state IN ('PENDING','ACCEPTED','FAILED','DEFERRED') AND attempts<3 ORDER BY rowid LIMIT 1",arrayOf(generation.toString()))
        val id=pending ?: DeliveryCoordinator.sha256("morning-sync-v1\n$profile\n$generation\n$reason\n$slot")
        if(pending==null)db.execSQL("INSERT OR IGNORE INTO morning_sync(id,generation,from_ms,until_ms,prior_cursor,reason,state) VALUES(?,?,?,?,?,?,'PENDING')",arrayOf<Any?>(id,generation,from.toEpochMilli(),until.toEpochMilli(),ingestCursor(),reason))
        db.rawQuery("SELECT * FROM morning_sync WHERE id=?",arrayOf(id)).use { c->
            check(c.moveToFirst())
            fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
            fun n(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
            if(s("state") !in setOf("PENDING","ACCEPTED","FAILED","DEFERRED") || n("attempts")>=3 || !retryDue("morning_sync","id",id,env))return@use null
            MorningSyncRequest(id,Instant.ofEpochMilli(n("from_ms")),Instant.ofEpochMilli(n("until_ms")),s("prior_cursor"),s("reason"),generation)
        }
    }
    fun syncComplete(request:MorningSyncRequest,result:MorningSyncResult,generation:Long,token:String,env:ReportEnvironment)=tx {
        if(!valid(generation,token,env))return@tx
        val attempts=(text("SELECT attempts FROM morning_sync WHERE id=?",arrayOf(request.id))?.toInt() ?: return@tx)+if(result.state==MorningSyncState.FAILED)1 else 0
        val delay=if(result.state==MorningSyncState.FAILED)30_000L*(1L shl (attempts-1)) else 900_000L
        db.execSQL("UPDATE morning_sync SET state=?,attempts=?,retry_ms=?,retry_boot=?,retry_elapsed=? WHERE id=? AND generation=?",arrayOf<Any?>(result.state.name,attempts,env.now.toEpochMilli()+delay,env.boot,env.elapsed+delay,request.id,generation))
        if(result.state==MorningSyncState.COMPLETE)db.execSQL("UPDATE morning_runtime SET ingest_cursor=?,last_success_ms=? WHERE id=1 AND generation=?",arrayOf<Any?>(result.committedCursor,env.now.toEpochMilli(),generation))
    }
    fun lastSuccessfulSync():Instant?=read { text("SELECT last_success_ms FROM morning_runtime WHERE id=1")?.toLong()?.takeIf { it>0 }?.let(Instant::ofEpochMilli) }
    fun syncStatus():String=read { text("SELECT state FROM morning_sync ORDER BY rowid DESC LIMIT 1") ?: "NOT_REQUESTED" }
}
