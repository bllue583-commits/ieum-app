package kr.callbrief.kakao.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kr.callbrief.kakao.core.DeliveryLedger
import kr.callbrief.kakao.core.DeliveryResult
import kr.callbrief.kakao.core.DeliveryResult.Status
import java.io.File
import java.time.Instant

/** Contains only hashes, timestamps and states. The DB/WAL contain no report text or credentials. */
class SqliteDeliveryLedger(context: Context) : DeliveryLedger, AutoCloseable {
    companion object { private val processLock=Any() }
    private val helper = object : SQLiteOpenHelper(context.applicationContext,
        File(context.applicationContext.noBackupFilesDir,"kakao_delivery_v1.db").absolutePath,null,1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS delivery (key TEXT PRIMARY KEY, payload_hash TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, retry_ms INTEGER NOT NULL)") }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("ledger_migration_required") }
    }
    override fun claim(key: String, payloadHash: String, now: Instant): DeliveryLedger.Claim = synchronized(processLock) {
        require(key.matches(Regex("[a-f0-9]{64}")) && payloadHash.matches(Regex("[a-f0-9]{64}")))
        val db=helper.writableDatabase
        db.beginTransaction()
        try {
            db.query("delivery",null,"key=?",arrayOf(key),null,null,null).use { c ->
                if (c.moveToFirst()) {
                    val state=Status.valueOf(c.getString(c.getColumnIndexOrThrow("state")))
                    val attempts=c.getInt(c.getColumnIndexOrThrow("attempts"))
                    val due=Instant.ofEpochMilli(c.getLong(c.getColumnIndexOrThrow("retry_ms")))
                    val stored=c.getString(c.getColumnIndexOrThrow("payload_hash"))
                    val blocked=when {
                        state==Status.SENT -> DeliveryResult(Status.ALREADY_SENT)
                        state==Status.TEST_CONTRACT_PASSED -> DeliveryResult(Status.TEST_CONTRACT_PASSED)
                        state==Status.IN_FLIGHT || state==Status.UNKNOWN -> DeliveryResult(Status.UNKNOWN)
                        stored!=payloadHash -> DeliveryResult(Status.FAILED)
                        attempts>=3 -> DeliveryResult(Status.ATTEMPTS_EXHAUSTED)
                        state !in setOf(Status.AUTH_REQUIRED,Status.SCOPE_REQUIRED,Status.CONFIG_REQUIRED,Status.RATE_LIMITED,Status.LOCAL_CONSENT_REQUIRED,Status.ACCOUNT_MISMATCH) -> DeliveryResult(state)
                        now.isBefore(due) -> DeliveryResult(state,due)
                        else -> null
                    }
                    if (blocked!=null) { db.setTransactionSuccessful(); return DeliveryLedger.Claim(false,blocked) }
                    val values=ContentValues().apply { put("state",Status.IN_FLIGHT.name); put("attempts",attempts+1) }
                    check(db.update("delivery",values,"key=?",arrayOf(key))==1)
                } else {
                    db.insertOrThrow("delivery",null,ContentValues().apply {
                        put("key",key); put("payload_hash",payloadHash); put("state",Status.IN_FLIGHT.name); put("attempts",1); put("retry_ms",0)
                    })
                }
            }
            db.setTransactionSuccessful()
            return DeliveryLedger.Claim(true,DeliveryResult(Status.IN_FLIGHT))
        } finally { db.endTransaction() }
    }
    override fun complete(key: String, result: DeliveryResult) = synchronized(processLock) {
        val due = if(result.status in setOf(Status.AUTH_REQUIRED,Status.SCOPE_REQUIRED,Status.CONFIG_REQUIRED,Status.LOCAL_CONSENT_REQUIRED,Status.ACCOUNT_MISMATCH))
            maxOf(result.retryNotBefore.toEpochMilli(),Instant.now().plusSeconds(30).toEpochMilli()) else result.retryNotBefore.toEpochMilli()
        val values=ContentValues().apply { put("state",result.status.name); put("retry_ms",due) }
        check(helper.writableDatabase.update("delivery",values,"key=? AND state=?",arrayOf(key,Status.IN_FLIGHT.name))==1) { "ledger_completion_failed" }
    }
    override fun close() = synchronized(processLock) { helper.close() }
}
