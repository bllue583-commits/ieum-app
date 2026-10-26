package kr.callbrief

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import kr.co.callsummary.core.android.ProtectedDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kr.callbrief.kakao.core.DeliveryCoordinator
import kr.callbrief.kakao.core.DeliveryResult
import kr.callbrief.kakao.core.DailyReport

/** Abort the legacy probe. Never delete, repair, enumerate attachments or close from this callback.
 * Throwing also prevents SQLiteDatabase.open() from retrying openInner() after onCorruption(). */
internal object PreserveLegacyReportErrorHandler:DatabaseErrorHandler {
    override fun onCorruption(dbObj:SQLiteDatabase) {
        throw SQLiteDatabaseCorruptException("LEGACY_V1_REPORT_UNREADABLE_PRESERVED")
    }
}

internal interface ReportPayloadCipher {
    fun seal(text: String,aad: String,hasExistingPayload: Boolean): ByteArray
    fun open(bytes: ByteArray,aad: String): String
}
internal class AndroidReportPayloadCipher(private val alias: String) : ReportPayloadCipher {
    private fun key(create: Boolean): SecretKey {
        val old=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias,null) as? SecretKey
        if(old!=null)return old
        check(create) { "REPORT_KEY_MISSING_REVIEW_REQUIRED" }
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun seal(text:String,aad:String,hasExistingPayload:Boolean):ByteArray {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(!hasExistingPayload));c.updateAAD(aad.toByteArray())
        return c.iv+c.doFinal(text.toByteArray(Charsets.UTF_8))
    }
    override fun open(bytes:ByteArray,aad:String):String {
        require(bytes.size>=28);val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(0,12)));c.updateAAD(aad.toByteArray())
        return String(c.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
    }
}

/** Dedicated app report store; source tables and external sender ledger are never altered. */
internal class DailyReportLedger(context:Context,val profile:String,
    private val cipher:ReportPayloadCipher=AndroidReportPayloadCipher("ieum.daily.report.v2."+DeliveryCoordinator.sha256(profile))) : AutoCloseable {
    companion object {
        const val DATABASE="ieum-daily-reports-v2.db"
        private val SCHEMA=listOf(
            "CREATE TABLE config(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL,generation INTEGER NOT NULL,cursor TEXT,zone TEXT NOT NULL)",
            "INSERT INTO config VALUES(1,0,0,NULL,'')",
            "CREATE TABLE reports(id TEXT PRIMARY KEY,day TEXT NOT NULL UNIQUE,zone TEXT NOT NULL,from_ms INTEGER NOT NULL,until_ms INTEGER NOT NULL,"+
                "ordinal INTEGER NOT NULL UNIQUE,observed INTEGER,"+
                "readiness TEXT NOT NULL,delivery TEXT NOT NULL,basis TEXT NOT NULL DEFAULT '',payload_hash TEXT NOT NULL DEFAULT '',payload BLOB,"+
                "attempts INTEGER NOT NULL DEFAULT 0,retry_at INTEGER NOT NULL DEFAULT 0,note TEXT NOT NULL DEFAULT '',"+
                "claim TEXT NOT NULL DEFAULT '',claim_boot INTEGER NOT NULL DEFAULT 0,claim_until INTEGER NOT NULL DEFAULT 0,generation INTEGER NOT NULL,CHECK(until_ms>from_ms))",
            "CREATE TRIGGER immutable_report BEFORE UPDATE OF payload,payload_hash,basis ON reports WHEN OLD.payload IS NOT NULL " +
                "AND (NEW.payload_hash!=OLD.payload_hash OR NEW.basis!=OLD.basis OR NEW.payload IS NOT OLD.payload) BEGIN SELECT RAISE(ABORT,'FROZEN_REPORT_IMMUTABLE'); END",
            "CREATE TRIGGER immutable_coverage BEFORE UPDATE OF day,zone,from_ms,until_ms,ordinal ON reports " +
                "BEGIN SELECT RAISE(ABORT,'REPORT_UTC_COVERAGE_IMMUTABLE'); END"
        ).joinToString("\n-- statement-break\n")
    }
    // v1 windows may already overlap. Preserve that ledger and block opt-in instead of silently resending/migrating it.
    private val legacyHold=run {
        val legacyFiles=ProtectedDatabase.legacyFiles(context,"ieum-daily-reports-v1.db").distinctBy { it.path }
        // AccountStorageContext also supplies retained pre-protection account paths.
        val sidecar:(File)->Boolean={ f ->listOf("-wal","-shm","-journal").any {f.path.endsWith(it)} }
        legacyFiles.any {sidecar(it) && it.exists()} || legacyFiles.filterNot(sidecar).any { legacy ->
            legacy.exists() && runCatching { SQLiteDatabase.openDatabase(legacy.path,null,SQLiteDatabase.OPEN_READONLY,
                PreserveLegacyReportErrorHandler).use { old ->
                old.rawQuery("SELECT 1 FROM reports LIMIT 1",null).use { it.moveToFirst() }
            } }.getOrDefault(true)
        }
    }
    private val protectedStore=ProtectedDatabase.open(context,DATABASE,SCHEMA)
    private val db=protectedStore.database()
    init {
        ProtectedDatabase.requireSealedPersistence(db)
        tx {
            db.execSQL("CREATE TABLE IF NOT EXISTS partial_reports(id TEXT PRIMARY KEY,report_id TEXT NOT NULL REFERENCES reports(id),basis TEXT NOT NULL,payload_hash TEXT NOT NULL,payload BLOB NOT NULL,created_ms INTEGER NOT NULL,UNIQUE(report_id,basis))")
            for(action in listOf("UPDATE","DELETE"))db.execSQL("CREATE TRIGGER IF NOT EXISTS partial_reports_immutable_${action.lowercase()} BEFORE $action ON partial_reports BEGIN SELECT RAISE(ABORT,'PARTIAL_HISTORY_IMMUTABLE'); END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS partial_reports_no_replace BEFORE INSERT ON partial_reports WHEN EXISTS(SELECT 1 FROM partial_reports WHERE id=NEW.id OR (report_id=NEW.report_id AND basis=NEW.basis)) BEGIN SELECT RAISE(ABORT,'PARTIAL_HISTORY_IMMUTABLE'); END")
        }
    }
    private fun <T> tx(block:()->T):T=ProtectedDatabase.transaction(db) {
        ProtectedDatabase.requireSealedPersistence(db);block()
    }
    private fun <T> read(block:()->T):T=ProtectedDatabase.read(db) {
        ProtectedDatabase.requireSealedPersistence(db);block()
    }
    fun config():DailyReportConfig=read { db.rawQuery("SELECT * FROM config WHERE id=1",null).use { c ->
        check(c.moveToFirst());DailyReportConfig(c.getInt(1)==1 && !legacyHold,c.getLong(2),c.getString(3)?.let(LocalDate::parse),c.getString(4),legacyHold)
    } }
    fun enabled(expected:Long)=config().let { it.enabled && it.generation==expected }
    fun setEnabled(value:Boolean,now:Instant,zone:ZoneId):DailyReportConfig=tx {
        check(!value || !legacyHold) { "LEGACY_V1_REPORT_REVIEW_REQUIRED" }
        val current=config();val next=current.generation+1
        db.execSQL("UPDATE config SET enabled=?,generation=?,cursor=?,zone=? WHERE id=1",
            arrayOf(if(value)1 else 0,next,now.atZone(zone).toLocalDate().minusDays(1).toString(),zone.id))
        db.execSQL("UPDATE reports SET delivery='UNKNOWN',note='작업 중단 · 원격 접수 여부 불명' WHERE delivery='SENDING'")
        db.execSQL("UPDATE reports SET readiness='CANCELLED',delivery='CANCELLED',note='사용자가 일일보고를 중단함' " +
            "WHERE delivery NOT IN ('SENT','UNKNOWN','CANCELLED')")
        config()
    }
    fun setCursor(cursor:LocalDate,zone:ZoneId) = tx { db.execSQL("UPDATE config SET cursor=?,zone=? WHERE id=1",arrayOf(cursor.toString(),zone.id)) }
    fun ensure(window:ReportWindow,generation:Long):SavedDailyReport=checkNotNull(allocate(window,generation)) { "REPORT_WINDOW_DOES_NOT_ADVANCE_UTC" }
    /** A date skipped after a backwards zone/clock change has no coverage; the next advancing date continues the frontier. */
    fun allocate(window:ReportWindow,generation:Long):SavedDailyReport?=tx {
        val id=ReportWindow.slot(profile,window.date)
        get(id)?.let { return@tx it }
        val last=db.rawQuery("SELECT until_ms,ordinal FROM reports ORDER BY ordinal DESC LIMIT 1",null).use { c ->
            if(c.moveToFirst())c.getLong(0) to c.getLong(1) else null
        }
        val from=last?.first ?: window.from.toEpochMilli()
        if(window.until.toEpochMilli()<=from)return@tx null
        val coverage=window.copy(from=Instant.ofEpochMilli(from))
        val v=ContentValues().apply { put("id",id);put("day",window.date.toString());put("zone",window.zone.id)
            put("from_ms",from);put("until_ms",window.until.toEpochMilli());put("ordinal",(last?.second ?: 0)+1)
            put("readiness",if(coverage.backlogReview)ReportReadiness.BACKLOG_REVIEW.name else ReportReadiness.PLANNED.name)
            put("delivery",ReportDelivery.NOT_STORED.name);put("generation",generation) }
        check(db.insertOrThrow("reports",null,v)!=-1L)
        requireNotNull(get(id))
    }
    fun get(id:String):SavedDailyReport?=read { db.rawQuery("SELECT * FROM reports WHERE id=?",arrayOf(id)).use { c ->
        if(!c.moveToFirst())return@use null
        fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
        fun n(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
        SavedDailyReport(s("id"),ReportWindow(LocalDate.parse(s("day")),ZoneId.of(s("zone")),Instant.ofEpochMilli(n("from_ms")),Instant.ofEpochMilli(n("until_ms"))),
            if(c.isNull(c.getColumnIndexOrThrow("observed")))null else Instant.ofEpochMilli(n("observed")),
            ReportReadiness.valueOf(s("readiness")),ReportDelivery.valueOf(s("delivery")),s("basis"),s("payload_hash"),
            n("attempts").toInt(),Instant.ofEpochMilli(n("retry_at")),s("note"),s("claim"),n("claim_boot"),n("claim_until"))
    } }
    // Keep unresolved extended ranges visible beyond the ordinary 60-report display window.
    fun list():List<SavedDailyReport> = read { db.rawQuery("SELECT id FROM reports WHERE (until_ms-from_ms>86400000 AND delivery NOT IN ('SENT','CANCELLED')) " +
        "OR id IN (SELECT id FROM reports ORDER BY ordinal DESC LIMIT 60) ORDER BY ordinal DESC",null).use { c ->
        buildList { while(c.moveToNext())get(c.getString(0))?.let(::add) }
    } }
    fun observe(id:String,input:DailyReportInput,env:ReportEnvironment,generation:Long) = tx {
        val r=requireNotNull(get(id));if(!enabled(generation) || r.delivery==ReportDelivery.CANCELLED)return@tx
        val changed=input.readiness!=ReportReadiness.SOURCE_FAILURE && r.basisHash.isNotBlank() && r.basisHash!=input.basisHash
        val sticky=r.readiness==ReportReadiness.STALE_REVIEW && r.basisHash.isNotBlank()
        val readiness=when { changed || sticky -> ReportReadiness.STALE_REVIEW
            r.window.backlogReview && input.readiness!=ReportReadiness.SOURCE_FAILURE -> ReportReadiness.BACKLOG_REVIEW
            else -> input.readiness }
        val note=when {
            changed || sticky -> "검토 뒤 원본·요약·수동 수정·제외 설정이 변경됨 · 이전 보고 보관, 재검토 필요"
            input.readiness==ReportReadiness.SOURCE_FAILURE -> "기록 상태 조회 실패 · 보고 보관, 전송 보류"
            r.window.backlogReview -> "밀린 기록 검토 · 자동 발송 제외 · 대상 ${input.expectedCount}개, 분석·검토 대기 ${input.pendingCount}개\n${r.window.rangeKo()}"
            env.doze -> "기기 절전 중 · 정각 실행은 보장되지 않음"
            !env.online -> "네트워크 단절 · 앱에 보관하고 전송 대기"
            !env.batteryOkay -> "배터리 절전 조건으로 전송 대기"
            r.observed==null && env.now.isAfter(r.window.due.plusSeconds(60)) -> "목표 시각보다 지연됨 · OS 제약 또는 앱 미실행 가능 (${env.trigger})"
            r.delivery in listOf(ReportDelivery.RETRY,ReportDelivery.FAILED,ReportDelivery.UNKNOWN,ReportDelivery.SENT) -> r.note
            r.window.zone!=env.zone -> "기기 시간대 변경 · 이 보고의 대상일·작성 당시 시간대는 고정됨"
            else -> "${input.expectedCount}개 중 분석·검토 대기 ${input.pendingCount}개 (${env.trigger})"
        }
        db.execSQL("UPDATE reports SET observed=COALESCE(observed,?),readiness=?,note=? WHERE id=?",
            arrayOf(if(env.now.isBefore(r.window.due))null else env.now.toEpochMilli(),readiness.name,note,id))
        if(!input.fullyReviewed && (input.pendingCount>0 || input.unresolved.isNotEmpty() || input.readiness==ReportReadiness.SOURCE_FAILURE))
            storePartial(id,input,env.now,generation)
    }
    private fun aad(r:SavedDailyReport)="$profile\n${r.id}\n${r.window.date}\n${r.window.zone.id}\n${r.window.from}\n${r.window.until}"
    private fun hasPayload()=db.rawQuery("SELECT 1 FROM reports WHERE payload IS NOT NULL UNION ALL SELECT 1 FROM partial_reports LIMIT 1",null).use { it.moveToFirst() }
    fun storePartial(id:String,input:DailyReportInput,now:Instant,generation:Long):String=tx {
        check(enabled(generation) && !input.fullyReviewed) { "PARTIAL_CURRENT_INPUT_REQUIRED" }
        val r=requireNotNull(get(id));check(r.delivery!=ReportDelivery.CANCELLED) { "REPORT_CANCELLED" }
        val key=DeliveryCoordinator.sha256("partial-v1\n$id\n${input.basisHash}")
        if(db.rawQuery("SELECT id FROM partial_reports WHERE id=?",arrayOf(key)).use { it.moveToFirst() })return@tx key
        val text=r.window.bodyWithRange(input.partialPreview());require(text.length<=8_000_000) { "PARTIAL_REPORT_TOO_LARGE" }
        val hash=DeliveryCoordinator.sha256(text)
        val encrypted=cipher.seal(text,aad(r)+"\nPARTIAL_V1\n$key\n${input.basisHash}",hasPayload())
        db.execSQL("INSERT INTO partial_reports VALUES(?,?,?,?,?,?)",arrayOf(key,id,input.basisHash,hash,encrypted,now.toEpochMilli()));key
    }
    fun partialHistory(id:String):List<Pair<String,Instant>> = read {
        db.rawQuery("SELECT id,created_ms FROM partial_reports WHERE report_id=? ORDER BY created_ms DESC,rowid DESC",arrayOf(id)).use { c ->buildList { while(c.moveToNext())add(c.getString(0) to Instant.ofEpochMilli(c.getLong(1))) } }
    }
    fun partialText(id:String,key:String):String=read {
        val r=requireNotNull(get(id))
        db.rawQuery("SELECT basis,payload_hash,payload FROM partial_reports WHERE report_id=? AND id=?",arrayOf(id,key)).use { c ->
            check(c.moveToFirst()) { "PARTIAL_NOT_FOUND" }
            cipher.open(c.getBlob(2),aad(r)+"\nPARTIAL_V1\n$key\n${c.getString(0)}").also { check(DeliveryCoordinator.sha256(it)==c.getString(1)) { "REPORT_PAYLOAD_HASH_MISMATCH" } }
        }
    }
    fun freeze(id:String,input:DailyReportInput,expectedBasis:String,text:String,privacyConfirmed:Boolean,generation:Long) = tx {
        check(enabled(generation) && input.fullyReviewed && input.basisHash==expectedBasis && privacyConfirmed) { "REVIEWED_CURRENT_INPUT_REQUIRED" }
        require(text.isNotBlank() && text.length<=16_000)
        val r=requireNotNull(get(id));check(r.delivery==ReportDelivery.NOT_STORED && r.readiness!=ReportReadiness.CANCELLED) { "REPORT_ALREADY_FROZEN_OR_CANCELLED" }
        check(!r.window.backlogReview) { "BACKLOG_RANGE_REVIEW_REQUIRED_NO_DAILY_SEND" }
        val storedText=r.window.bodyWithRange(text);require(storedText.length<=16_000)
        DailyReport(profile,r.window.date,r.window.zone,storedText,true,false) // Validate Unicode/size before any dispatch claim.
        val encrypted=cipher.seal(storedText,aad(r),hasPayload())
        db.execSQL("UPDATE reports SET readiness='REVIEWED',delivery='SEND_WAITING',basis=?,payload_hash=?,payload=?,note='내용·개인정보 제외를 확인해 앱에 보관 · 실제 카카오 전송 미확인' WHERE id=?",
            arrayOf(input.basisHash,DeliveryCoordinator.sha256(storedText),encrypted,id))
        // No successful delivery is implied by creating an app copy.
    }
    fun text(id:String):String = read {
        val r=requireNotNull(get(id));val bytes=db.rawQuery("SELECT payload FROM reports WHERE id=?",arrayOf(id)).use { c ->check(c.moveToFirst());check(!c.isNull(0));c.getBlob(0) }
        cipher.open(bytes,aad(r)).also { check(DeliveryCoordinator.sha256(it)==r.payloadHash) { "REPORT_PAYLOAD_HASH_MISMATCH" } }
    }
    fun waiting(id:String,note:String) = tx { db.execSQL("UPDATE reports SET note=? WHERE id=? AND delivery IN ('SEND_WAITING','RETRY','LOCAL_STORED')",arrayOf(note,id)) }
    // A 120s claim lease is examined only on a subsequent tick/refresh; this is not a callback timeout/timer.
    fun recover(env:ReportEnvironment) = tx { db.execSQL("UPDATE reports SET delivery='UNKNOWN',note='다음 상태 확인에서 전송 임대 만료·재부팅 확인 · 자동 재전송하지 않음' " +
        "WHERE delivery='SENDING' AND (claim_boot!=? OR claim_until<=?)",arrayOf(env.boot,env.elapsed)) }
    fun claim(id:String,input:DailyReportInput,env:ReportEnvironment,generation:Long):String?=tx {
        val r=requireNotNull(get(id))
        if(r.window.backlogReview || !enabled(generation) || !input.fullyReviewed || r.basisHash!=input.basisHash ||
            r.readiness!=ReportReadiness.REVIEWED || r.delivery !in listOf(ReportDelivery.SEND_WAITING,ReportDelivery.RETRY) || env.now.isBefore(r.retryAt) || r.attempts>=3)return@tx null
        val claim=UUID.randomUUID().toString()
        db.execSQL("UPDATE reports SET delivery='SENDING',attempts=attempts+1,claim=?,claim_boot=?,claim_until=? WHERE id=?",
            arrayOf(claim,env.boot,env.elapsed+120_000,id));claim
    }
    fun valid(id:String,claim:String,generation:Long,env:ReportEnvironment)=enabled(generation) && get(id)?.let {
        it.delivery==ReportDelivery.SENDING && it.claim==claim && it.claimBoot==env.boot && it.claimUntil>env.elapsed
    }==true
    fun complete(id:String,claim:String,result:DeliveryResult,env:ReportEnvironment,generation:Long)=tx {
        if(!valid(id,claim,generation,env))return@tx
        val r=requireNotNull(get(id))
        val retry=result.status in setOf(DeliveryResult.Status.RATE_LIMITED,DeliveryResult.Status.CONNECTION_CHECK_FAILED)
        val delivery=when(result.status) {
            DeliveryResult.Status.SENT,DeliveryResult.Status.ALREADY_SENT -> ReportDelivery.SENT
            DeliveryResult.Status.UNKNOWN,DeliveryResult.Status.IN_FLIGHT,DeliveryResult.Status.STORAGE_FAILURE -> ReportDelivery.UNKNOWN
            DeliveryResult.Status.TEST_CONTRACT_PASSED -> ReportDelivery.LOCAL_STORED
            DeliveryResult.Status.DISABLED,DeliveryResult.Status.LOCAL_CONSENT_REQUIRED,DeliveryResult.Status.AUTH_REQUIRED,
            DeliveryResult.Status.SCOPE_REQUIRED,DeliveryResult.Status.CONFIG_REQUIRED,DeliveryResult.Status.ACCOUNT_MISMATCH -> ReportDelivery.SEND_WAITING
            else -> if(retry && r.attempts<3)ReportDelivery.RETRY else ReportDelivery.FAILED
        }
        val floor=env.now.plusSeconds(30L shl (r.attempts-1).coerceIn(0,5))
        val retryAt=if(retry)maxOf(floor,result.retryNotBefore) else Instant.EPOCH
        db.execSQL("UPDATE reports SET delivery=?,retry_at=?,note=?,claim_until=0 WHERE id=?",
            arrayOf(delivery.name,retryAt.toEpochMilli(),result.messageKo(),id))
    }
    fun rejectClaim(id:String,claim:String,reason:String)=tx {
        db.execSQL("UPDATE reports SET readiness='STALE_REVIEW',delivery='SEND_WAITING',note=?,claim_until=0 WHERE id=? AND claim=? AND delivery='SENDING'",arrayOf(reason,id,claim))
    }
    override fun close() { protectedStore.close() }
}
