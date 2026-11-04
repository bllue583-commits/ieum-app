package kr.callbrief.pipeline

import android.content.Context
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import kr.co.callsummary.core.android.ProtectedDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

/** r9-2: the contact cards, worked out from generated calls in the production protected store. No real numbers or calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ContactCardsTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val started=Instant.parse("2026-10-29T01:00:00Z").toEpochMilli()
    private var now=started+3_600_000
    private val clock={ now }
    private val quote="견적서는 내일까지 메일로 보내드릴게요"

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        ContactCards.shutdown();ContactCards.clearPending()
        assertFalse("host tests do not flush in the background",ContactCards.background)
        AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql")).use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            listOf("rec1","rec2","rec3").forEach { id ->
                p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",
                    id,GeneralLocalStore.OWNER,id,"$id.wav")
            }
            null } }
    }
    @After fun tearDown() { ContactCards.shutdown();ContactCards.clearPending() }

    private fun store()=CallRecordStore(context,clock)
    private fun transcript(recording:String,counterpart:String,at:Long)=
        CallTranscript(recording,1,"a".repeat(64),at,125_000,counterpart,"통화 내용",listOf(CallTranscript.Segment(0,5000,"통화 내용")),"ggml-large-v3-turbo-q5_0","b".repeat(64),true)
    /** Stores one call and applies its result; returns the record id. */
    private fun call(s:CallRecordStore,recording:String,counterpart:String,at:Long,summary:String,person:String,company:String?,role:String?,vararg tasks:String):String {
        val t=transcript(recording,counterpart,at);s.insert(t)
        val id=CallRecordStore.recordId(t)
        s.applyAnalysis(id,ClaudeCallAnalysis(summary,emptyList(),person,company,emptyList(),tasks.map { ClaudeCallAnalysis.Task("create",null,it,null,quote) },
            counterpart=ClaudeCallAnalysis.Counterpart(person,company,role,false)))
        return id
    }

    @Test fun resultAppliedThenCardShowsNewSummary() = store().use { s ->
        call(s,"rec1","010-1234-5678",started,"견적서를 내일까지 보내기로 했어요. 단가는 지난번과 같아요.","김민수","탄탄이엔씨","대표","견적서 보내기")
        // The hook only marks the number; the card is worked out when the touched numbers are flushed.
        assertEquals(setOf("01012345678"),ContactCards.pendingKeys())
        assertNull(ContactCards.get(context,"010-1234-5678"))
        ContactCards.flushPending(s)
        val card=ContactCards.get("+82 10-1234-5678")!!
        assertEquals("김민수",card.people.single().name);assertEquals("대표",card.people.single().role)
        assertEquals("탄탄이엔씨",card.company?.name)
        assertEquals("견적서를 내일까지 보내기로 했어요.",card.recent.single().summaryFirst);assertEquals("통화",card.recent.single().kind)
        assertEquals("견적서 보내기",card.openTasks.single().title);assertEquals(started,card.openTasks.single().requestedMs)
        assertEquals(started,card.lastCallMs)
        val json=card.toJson().toString()
        assertFalse("no phone number on a card",json.contains("01012345678") || json.contains("1234-5678"))
        assertFalse("no transcript on a card",json.contains("통화 내용"))

        // A later call: the card follows.
        call(s,"rec2","01012345678",started+86_400_000,"계약서 초안을 다음 주에 받기로 했어요","김민수","탄탄이엔씨","대표")
        ContactCards.flushPending(s)
        val again=ContactCards.get("010-1234-5678")!!
        assertEquals(listOf("계약서 초안을 다음 주에 받기로 했어요","견적서를 내일까지 보내기로 했어요."),again.recent.map { it.summaryFirst })
        assertEquals(started+86_400_000,again.lastCallMs)

        // "끝났어요" takes the task off the card; read back from the file after memory is gone.
        val item=s.openItems().single();s.completeItem(item.id,item.revision);ContactCards.flushPending(s)
        ContactCards.shutdown()
        val fromFile=ContactCards.get(context,"01012345678")!!
        assertTrue(fromFile.openTasks.isEmpty());assertEquals(again.recent,fromFile.recent)
        assertTrue(ProtectedDatabase.file(context,ContactCards.DATABASE).name=="contact-cards-v1.sealed")
    }

    @Test fun personalNumberHasNoCard() = store().use { s ->
        call(s,"rec1","010-5555-0001",started,"자재 반입 일정을 정했어요","박영희","탄탄이엔씨","대리")
        ContactCards.flushPending(s)
        assertNotNull(ContactCards.get(context,"01055550001"))
        s.rememberPersonal("01055550001")
        ContactCards.refresh(s,listOf("01055550001"))
        assertNull(ContactCards.get("01055550001"))
        ContactCards.shutdown();assertNull(ContactCards.get(context,"01055550001"))
        assertEquals(0,ContactCards.rebuildAll(s));assertNull(ContactCards.get("01055550001"))
        // Taken back from personal: the card comes back.
        s.forgetPersonal("01055550001");ContactCards.flushPending(s)
        assertNotNull(ContactCards.get("01055550001"))
    }

    @Test fun sharedNumberListsPeopleLatestFirst() = store().use { s ->
        call(s,"rec1","02-123-4567",started,"도면 수정을 부탁했어요","박영희","탄탄이엔씨","대리")
        call(s,"rec2","021234567",started+3_600_000,"자재 반입을 확인했어요","이철수","탄탄이엔씨","과장")
        ContactCards.flushPending(s)
        val card=ContactCards.get(context,"+82 2-123-4567")!!
        assertEquals(listOf("이철수","박영희"),card.people.map { it.name })
        assertEquals("탄탄이엔씨",card.company?.name)
    }

    @Test fun renameAndRebuildAll() = store().use { s ->
        call(s,"rec1","010-1234-5678",started,"견적을 받았어요","김민수","탄탄이엔씨","대표")
        call(s,"rec2","010-2222-3333",started+60_000,"현장 출입을 물었어요","최영수","탄탄이엔씨","소장")
        assertEquals(2,ContactCards.rebuildAll(s));ContactCards.clearPending()
        val id=ContactCards.get(context,"01012345678")!!.people.single().personId
        assertTrue(s.renamePerson(id,"김민서"))
        assertEquals(setOf("01012345678"),ContactCards.pendingKeys())
        ContactCards.flushPending(s)
        assertEquals("김민서",ContactCards.get("01012345678")!!.people.single().name)
        assertEquals("최영수",ContactCards.get("01022223333")!!.people.single().name)
    }

    @Test fun threeHundredCardsReadTime() {
        val cards=(0 until 300).associate { i ->
            val key="0101000%04d".format(i)
            key to ContactCard(key,listOf(ContactCard.Person("pe_$i","사람$i","대리",false)),ContactCard.Company("co_${i%40}","업체${i%40}","철근"),started+i,"in",
                List(3) { ContactCard.Recent(started+it,"통화","견적서를 내일까지 보내기로 했어요 단가는 지난번과 같아요") },
                List(5) { ContactCard.Task("wf_$i-$it","체크할 일 제목 $it 번째 철근 반입 일정 확인",started,"open",null) },
                List(3) { ContactCard.IssueLine("진행 중인 일 $it","현황 한 줄 이에요") },
                List(2) { ContactCard.Text(started+it,"in","자료 보내 드렸어요") },started)
        }
        ContactCards.write(context,cards,replaceAll=true)
        ContactCards.shutdown()
        val t0=System.nanoTime()
        assertEquals(300,ContactCards.preload(context))
        val readMs=(System.nanoTime()-t0)/1_000_000
        val h0=System.nanoTime()
        repeat(300) { i -> assertNotNull(ContactCards.get("0101000%04d".format(i))) }
        val hitUs=(System.nanoTime()-h0)/1_000/300
        val bytes=ProtectedDatabase.file(context,ContactCards.DATABASE).length()
        println("ContactCards 300 cards: file bytes=$bytes, read ms=$readMs (preload ms=${ContactCards.lastPreloadMs}), cache hit us/get=$hitUs")
        assertTrue("300 cards read in $readMs ms",readMs<2_000)
        assertTrue("cache hit $hitUs us",hitUs<1_000)
    }
}
