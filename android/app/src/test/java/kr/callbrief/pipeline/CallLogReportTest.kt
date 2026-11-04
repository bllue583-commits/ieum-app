package kr.callbrief.pipeline

import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.SecureSettings
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
import java.time.LocalDate

/** status/calllog.txt (decided 11/4): the phone's call log next to what the app stored. Fake names and numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallLogReportTest:ProtectedTextHostTestBase() {
    private val now=LocalDate.of(2026,11,4).atTime(21,0).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private fun at(day:Int,h:Int,m:Int,s:Int=0)=LocalDate.of(2026,if(day>=20) 10 else 11,day).atTime(h,m,s).atZone(CallRecordStore.KST).toInstant().toEpochMilli()
    private fun call(id:Long,ms:Long,type:Int=1,sec:Long=192,name:String?="홍길동",number:String="010-1234-5678",excluded:Boolean=false)=
        CallLogReport.DeviceCall(id,number,name,ms,sec,type,excluded)
    private fun stored(id:Long,ms:Long,record:String?=null,state:String?=null,recording:String?=null,business:Boolean?=null,type:Int=1)=
        CallRecordStore.StoredLog(id,"01012345678","홍길동",ms,192_000,type,record,state,business,recording)

    @Test fun linesHaveTheAgreedFormat() {
        val r=CallLogReport.render(now,"2.4",listOf(call(1,at(4,14,5)),call(2,at(4,15,0),type=3,sec=0,name=null,number="02-555-0000")),
            listOf(stored(1,at(4,14,5),"rec1|1|x",CallRecordStore.ANALYZED)),emptySet(),full=false,lastFullMs=0)
        val lines=r.text.lines()
        assertTrue(lines[0].startsWith("이음 통화 목록 대조 (만든 시각 2026-11-04 21:00 KST, 앱 2.4)"))
        assertEquals("폰 기록 2건, 앱 저장 1건, 빠진 1건, 앱에만 0건, 중복 0건 (10/1 이후)",lines[1])
        assertTrue(lines.contains("11-04 14:05 | 받은 | 통화 3분 12초 | 홍길동 | 010-1234-5678 | O | O | 완료 | "+CallInbox.fileName("rec1|1|x").take(8)))
        assertTrue(lines.contains("11-04 15:00 | 부재중 | 통화 0분 0초 | - | 02-555-0000 | X | X | 없음 | -"))
        for(t in listOf("[빠진 통화 (폰에 있고 앱에 없음)]","[앱에만 있는 기록]","[중복]","[최근 48시간]","[10/1 이후 전체]")) assertTrue(t,lines.contains(t))
        assertTrue(lines.contains("(하루 한 번 갱신, 마지막 갱신 아직 없음)"))
    }

    @Test fun missingAppOnlyAndDoubledAreFound() {
        val device=listOf(call(1,at(4,10,0)),call(2,at(4,10,0)), // same number, second and length: doubled
            call(3,at(3,9,0)),call(4,at(2,9,0)),call(5,at(1,9,0)))
        val st=listOf(stored(1,at(4,10,0)),stored(2,at(4,10,0)),stored(3,at(3,9,0),"rA","TRANSCRIBED","recX"),stored(4,at(2,9,0),"rB","ANALYZED","recX"), // one recording, two calls
            stored(9,at(30,9,0)))
        val t=CallLogReport.render(now,"2.4",device,st,emptySet(),full=true,lastFullMs=0).text
        assertTrue(t.lines()[1],t.lines()[1].startsWith("폰 기록 5건, 앱 저장 5건, 빠진 1건, 앱에만 1건, 중복 4건"))
        val missing=t.substringAfter("[빠진 통화 (폰에 있고 앱에 없음)]\n").substringBefore("\n\n")
        assertTrue(missing,missing.startsWith("11-01 09:00"))
        assertTrue(t.substringAfter("[앱에만 있는 기록]\n").startsWith("10-30 09:00"))
        val doubled=t.substringAfter("[중복]\n").substringBefore("\n\n").lines()
        assertEquals(4,doubled.size)
        // Full section filled: all five calls, newest first; the recent one has only the last 48 hours.
        assertEquals(5,t.substringAfter("[10/1 이후 전체]\n").trim().lines().size)
        assertEquals(2+1,t.substringAfter("[최근 48시간]\n").substringBefore("\n\n").lines().size) // 11/4 x2, 11/3 09:00 is within 48h of 11/4 21:00
    }

    @Test fun personalAndExcludedNumbers() {
        val r=CallLogReport.render(now,"2.4",listOf(call(1,at(4,10,0),excluded=true),call(2,at(4,11,0),number="010-2222-3333",name="김철수")),
            listOf(stored(2,at(4,11,0),"r2","ANALYZED")),setOf("01022223333"),full=false,lastFullMs=0).text
        assertTrue(r.contains("11-04 10:00 | 받은 | 통화 3분 12초 | (제외 번호) | (제외 번호) | X | X | 개인 | -"))
        assertFalse(r.contains("010-1234-5678"))
        assertTrue(r.contains("| 김철수 | 010-2222-3333 | O | O | 개인 |"))
    }

    @Test fun noPermissionSaysSo() {
        val t=CallLogReport.render(now,"2.4",null,listOf(stored(1,at(4,10,0))),emptySet(),full=false,lastFullMs=0).text
        assertTrue(t.contains("폰 통화 기록을 읽을 권한이 없어요. 앱 저장 1건만 보여요."))
        assertFalse(t.contains("[최근 48시간]"))
    }

    @Test fun sameContentIsNotSentAgainAndFullSectionOnceADay() {
        val context=RuntimeEnvironment.getApplication()
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { }
        CallRecordStore(context).use { it.reconcileCallLog(listOf(FirstPass.LogEntry(1,"01012345678","홍길동",at(4,14,5),192_000,1))) }
        val puts=mutableListOf<String>()
        val inbox=object:CallInboxStore {
            override fun put(path:String,json:String){ throw AssertionError("put") }
            override fun replace(path:String,json:String){ assertEquals(CallLogReport.PATH,path);puts+=json }
            override fun list(dir:String)=emptyList<Pair<String,String>>()
            override fun get(path:String)=""
            override fun delete(path:String,sha:String){}
        }
        val device={ _:android.content.Context,_:org.json.JSONObject -> listOf(call(1,at(4,14,5)),call(2,at(4,15,0))) }
        CallLogReport.publish(context,inbox,"2.4",now,device)
        assertEquals(1,puts.size);assertTrue(puts[0].substringAfter("[10/1 이후 전체]\n").startsWith("11-04 15:00"))
        // An hour later: the full section is only the marker (changed file: sent), then nothing changes (not sent).
        CallLogReport.publish(context,inbox,"2.4",now+3_600_000,device)
        assertEquals(2,puts.size);assertTrue(puts[1].contains("(하루 한 번 갱신, 마지막 갱신 2026-11-04 21:00)"))
        CallLogReport.publish(context,inbox,"2.4",now+2*3_600_000,device)
        assertEquals(2,puts.size)
        // The next KST day: the full section again.
        CallLogReport.publish(context,inbox,"2.4",now+4*3_600_000,device)
        assertEquals(3,puts.size);assertTrue(puts[2].substringAfter("[10/1 이후 전체]\n").startsWith("11-04 15:00"))
        // A failed send remembers nothing, so the next tick sends.
        val failing=object:CallInboxStore by inbox { override fun replace(path:String,json:String){ throw java.io.IOException("offline") } }
        val more={ _:android.content.Context,_:org.json.JSONObject -> listOf(call(1,at(4,14,5)),call(2,at(4,15,0)),call(3,at(5,2,0))) }
        CallLogReport.publish(context,failing,"2.4",now+5*3_600_000,more)
        CallLogReport.publish(context,inbox,"2.4",now+5*3_600_000,more)
        assertEquals(4,puts.size)
        assertEquals(SecureSettings(context).snapshot().optString(CallLogReport.FULL_DAY),"2026-11-05")
    }
}
