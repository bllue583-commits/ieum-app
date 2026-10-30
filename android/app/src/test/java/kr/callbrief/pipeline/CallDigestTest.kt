package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallDigestTest:ProtectedTextHostTestBase() {
    private fun kst(text:String)=Instant.parse(text+"+09:00").toEpochMilli()
    private fun transcript(id:String,startedMs:Long?,counterpart:String?)=
        CallTranscript(id,1,"c".repeat(64),startedMs,60_000,counterpart,"통화 내용",emptyList(),"m","d".repeat(64),false)

    @Test fun kstDayBoundariesAndEveningCounts() {
        val context:Context=RuntimeEnvironment.getApplication()
        val created=kst("2026-10-29T20:00:00")
        CallRecordStore(context) { created }.use { s ->
            s.insert(transcript("late",kst("2026-10-28T23:59:00"),"어제 상대"))
            s.insert(transcript("early",kst("2026-10-29T00:01:00"),"홍길동"))
            s.insert(transcript("noon",kst("2026-10-29T12:00:00"),"010-1111-2222"))
            s.insert(transcript("unknown",null,null)) // no start time: counted on the day it was stored
            assertEquals(listOf("late"),s.day(LocalDate.of(2026,10,28)).map { it.recordingId })
            assertEquals(listOf("unknown","noon","early"),s.day(LocalDate.of(2026,10,29)).map { it.recordingId })
            val m=CallDigestText.evening(s,LocalDate.of(2026,10,29))
            assertEquals("오늘 통화 3건, 새 할 일 0개",m.title)
            assertTrue(m.text.contains("010-1111-2222"));assertTrue(m.text.contains("홍길동"));assertFalse(m.text.contains("통화 내용"))
        }
        assertEquals(LocalDate.of(2026,10,28),CallRecordStore.kstDay(kst("2026-10-28T23:59:00")))
        assertEquals(LocalDate.of(2026,10,29),CallRecordStore.kstDay(kst("2026-10-29T00:01:00")))
    }

    @Test fun eveningTextNamesAtMostTwo() {
        fun rec(name:String?)=CallRecord(name ?: "x",name ?: "x",0,0,name,null,"","TRANSCRIBED",null,null,emptyList(),null,0,null)
        val m=CallDigestText.evening(listOf(rec("가"),rec("나"),rec("다"),rec(null)),2)
        assertEquals("오늘 통화 4건, 새 할 일 2개",m.title);assertTrue(m.text.contains("가, 나 등"));assertFalse(m.text.contains("다"))
    }

    @Test fun morningCountsDueTodayAndOverdue() {
        val context:Context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> CallRecordStore(context).close();p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES('r','${GeneralLocalStore.OWNER}','g1','d','n','audio/wav',1,0,0,1,1,1)")
            listOf("i1" to "2026-10-29" to "open","i2" to "2026-10-27" to "needs_confirmation","i3" to "2026-10-30" to "open","i4" to "2026-10-20" to "done","i5" to "내일" to "open").forEach { (a,status) ->
                p.execute("INSERT INTO workflow_items VALUES(?,'r',NULL,'TODO',?,'',?,?,1,NULL,'',0)",a.first,"할 일 ${a.first}",a.second,status)
            };null
        } }
        CallRecordStore(context).use { s ->
            assertEquals(listOf("i2","i1"),s.dueItems(LocalDate.of(2026,10,29)).map { it.id })
            assertEquals("오늘 할 일 2개 (지난 기한 1개)",CallDigestText.morning(s,LocalDate.of(2026,10,29)).title)
            assertEquals("오늘 할 일 0개",CallDigestText.morning(s,LocalDate.of(2026,10,26)).title)
        }
    }

    @Test fun nextRunIsTheNextKstHour() {
        assertEquals(Instant.parse("2026-10-29T10:00:00Z"),CallDigestTime.next(Instant.parse("2026-10-29T09:59:00Z"),19))
        assertEquals(Instant.parse("2026-10-30T10:00:00Z"),CallDigestTime.next(Instant.parse("2026-10-29T10:00:00Z"),19))
        assertEquals(Instant.parse("2026-10-29T21:00:00Z"),CallDigestTime.next(Instant.parse("2026-10-29T10:00:00Z"),6))
    }
}
