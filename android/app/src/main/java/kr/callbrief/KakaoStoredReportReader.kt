package kr.callbrief
import android.content.Context
import android.database.Cursor
import kr.co.callsummary.core.android.ProtectedDatabase
import kr.callbrief.kakao.core.DeliveryCoordinator
import java.io.File
import java.time.*

/** Read-only adapter pinned to existing daily-report v2 AAD/schema. The producer
 * owns this format; fail closed on drift. Never instantiate the writable ledger,
 * create a key/store, reconcile, migrate, or invoke the report coordinator. */
internal object KakaoStoredReportReader {
    fun read(context:Context,profile:String,date:LocalDate,
        cipher:ReportPayloadCipher=AndroidReportPayloadCipher("ieum.daily.report.v2."+DeliveryCoordinator.sha256(profile))):List<Pair<String,String>> {
        val file=ProtectedDatabase.file(context,DailyReportLedger.DATABASE)
        if(!file.isFile && !File(file.path+".bak").isFile)return emptyList()
        return ProtectedDatabase.open(context,DailyReportLedger.DATABASE,null).use { store ->
            val db=store.database()
            ProtectedDatabase.read(db) {
                db.rawQuery("SELECT id,day,zone,from_ms,until_ms,payload_hash,payload,readiness FROM reports WHERE day=? AND basis<>'' AND payload IS NOT NULL LIMIT 2",arrayOf(date.toString())).use { c ->
                    buildList {
                        while(c.moveToNext()) {
                            check(isEmpty()) { "DUPLICATE_REPORT_DATE" }
                            check(c.getType(3)==Cursor.FIELD_TYPE_INTEGER && c.getType(4)==Cursor.FIELD_TYPE_INTEGER)
                            val id=c.getString(0);check(id==ReportWindow.slot(profile,date) && c.getString(1)==date.toString())
                            val zone=ZoneId.of(c.getString(2));val from=Instant.ofEpochMilli(c.getLong(3));val until=Instant.ofEpochMilli(c.getLong(4))
                            ReportWindow(date,zone,from,until)
                            val text=cipher.open(c.getBlob(6),"$profile\n$id\n$date\n${zone.id}\n$from\n$until")
                            check(DeliveryCoordinator.sha256(text)==c.getString(5)) { "REPORT_PAYLOAD_HASH_MISMATCH" }
                            val status=if(c.getString(7)=="STALE_REVIEW")"이전 보관본 · 최신 변경 검토 필요" else "앱 보관 보고"
                            add("${zone.id} · $status" to text)
                        }
                    }
                }
            }
        }
    }
}
