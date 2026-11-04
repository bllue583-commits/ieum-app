package kr.callbrief.pipeline

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

/** Context ledger on generated public data only: companies, people, memos, what the model is sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ContextLedgerTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private val started=Instant.parse("2026-10-29T01:00:00Z").toEpochMilli()
    private var now=started+3_600_000
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun store()=CallRecordStore(context) { now }
    private val ids=(1..12).map { "rec$it" }

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            ids.forEach { id -> p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",id,GeneralLocalStore.OWNER,id,"$id.wav") }
            null
        } }
    }
    private val text="네 김민수 과장님, 철근 단가는 내일까지 다시 견적서 보내드릴게요. 박대리님도 같이 봐주세요."
    private fun call(i:Int,counterpart:String?="010-1111-0001",at:Long=started+i*60_000L,body:String=text):CallRecord {
        val t=CallTranscript("rec$i",1,"a".repeat(64),at,60_000,counterpart,body,listOf(CallTranscript.Segment(0,1,body)),"m","b".repeat(64),true)
        return store().use { it.insert(t);it.record(CallRecordStore.recordId(t))!! }
    }
    private fun cp(person:String?,company:String?,role:String?=null,staff:Boolean=false)=ClaudeCallAnalysis.Counterpart(person,company,role,staff)
    private fun analysis(cp:ClaudeCallAnalysis.Counterpart?,summary:String="요약",tasks:List<ClaudeCallAnalysis.Task> =emptyList(),site:String?=null,memo:String?=null,trade:String?=null)=
        ClaudeCallAnalysis(summary,emptyList(),cp?.personName,cp?.companyName,emptyList(),tasks,cp,site,memo,trade)
    private fun apply(r:CallRecord,a:ClaudeCallAnalysis)=store().use { it.applyAnalysis(r.recordId,a) }
    private fun create(title:String,company:String?=null)=ClaudeCallAnalysis.Task("create",null,title,"2026-10-30","철근 단가는 내일까지",company)
    private fun count(table:String)=port().use { (it.query("SELECT COUNT(*) AS n FROM $table").single()["n"] as Number).toInt() }

    @Test fun upsertsPeopleAndCompaniesAndLinksTheCall() {
        apply(call(1),analysis(cp("김민수"," △△ 철강 ","과장"),site="3층 슬래브 타설 준비 중",memo="단가 재견적 진행",trade="철근"))
        store().use { s ->
            val c=s.companies().single();assertEquals("△△ 철강",c.name);assertEquals("철근",c.trade)
            assertEquals("3층 슬래브 타설 준비 중",s.siteMemo());assertEquals("단가 재견적 진행",s.companyDetail(c.id)!!.memo)
        }
        assertEquals("01011110001",port().use { it.query("SELECT phone_key FROM people").single()["phone_key"] })
        // Same number, company spelled without the space: still one person and one company.
        apply(call(2),analysis(cp("김민수","△△철강","팀장")))
        assertEquals(1,count("people"));assertEquals(1,count("companies"));assertEquals(2,count("call_links"))
        assertEquals("팀장",port().use { it.query("SELECT role FROM people").single()["role"] })
        // Another number and name at the same company: a second person.
        apply(call(3,"010-2222-0002"),analysis(cp("박대리","△△철강")))
        assertEquals(2,count("people"))
        // Name-only counterpart (contact name) is matched by name.
        apply(call(4,"박대리"),analysis(cp("박대리","△△철강")))
        assertEquals(2,count("people"))
        // Staff are not attached to a company.
        apply(call(5,"이과장"),analysis(cp("이과장","△△철강",staff=true)))
        assertNull(port().use { it.query("SELECT company_id FROM people WHERE name='이과장'").single()["company_id"] });assertEquals(1,count("companies"))
        assertEquals(1,(port().use { it.query("SELECT is_staff FROM people WHERE name='이과장'").single()["is_staff"] } as Number).toInt())
        // Memos are replaced; null leaves them alone.
        apply(call(6),analysis(cp("김민수","△△철강"),site="새 메모"))
        store().use { assertEquals("새 메모",it.siteMemo());assertEquals("단가 재견적 진행",it.companyDetail(it.companies().single().id)!!.memo) }
    }

    @Test fun tasksGetCompaniesAndRequestCarriesTheLedger() {
        apply(call(1),analysis(cp("김민수","△△철강","과장"),"철근 단가 협의",listOf(create("단가 재견적 받기")),"현장 메모",trade="철근"))
        apply(call(2,"010-3333-0003"),analysis(cp("최사장","□□철근","대표"),"□□철근 납품 일정",listOf(create("납품 일정 확인")),trade="철근"))
        apply(call(3,"010-4444-0004"),analysis(cp("한소장","◇◇전기"),"전기 점검",listOf(create("분전반 점검","◇◇전기"))))
        store().use { s ->
            assertEquals(setOf("단가 재견적 받기","납품 일정 확인","분전반 점검"),s.openItems().map { it.title }.toSet())
            assertEquals("△△철강",s.openItems().first { it.title=="단가 재견적 받기" }.company)
            assertEquals("△△철강",s.dueItems(java.time.LocalDate.of(2026,10,30)).first { it.title=="단가 재견적 받기" }.company)
        }
        // A second person of the same company (new number) is named by the AI; the company then has two people.
        apply(call(4,"010-1111-0009"),analysis(cp("박대리","△△철강","대리"),"박대리와 도면 확인"))
        val next=call(5)
        val req=store().use { it.request(next) }
        assertEquals("현장 메모",req.siteMemo);assertEquals("김민수",req.person!!.name);assertEquals("과장",req.person!!.role)
        assertEquals("△△철강",req.company!!.name);assertEquals("철근",req.company!!.trade)
        assertEquals(listOf("박대리","김민수"),req.companyCalls.map { it.who }) // across people, newest first
        assertEquals(listOf("단가 재견적 받기"),req.openTasks.map { it.title })
        assertEquals(listOf("납품 일정 확인"),req.tradeTasks.map { it.title });assertEquals("□□철근",req.tradeTasks.single().company)
        assertEquals(listOf("분전반 점검"),req.otherTasks.map { it.title });assertEquals("◇◇전기",req.otherTasks.single().company)
        assertTrue(req.companyNames.containsAll(listOf("△△철강","□□철근","◇◇전기")));assertTrue(req.personNames.containsAll(listOf("김민수","박대리","한소장")))
        val message=ClaudeCallAnalyzer.userMessage(req)
        listOf("[현장 상황 메모]","[상대]","[업체]","[이 업체와의 최근 통화","[이 업체의 열린 체크할 일(JSON)]","[같은 공종(철근)","[그 밖의 열린 체크할 일(JSON)]","[이름 목록]").forEach { assertTrue(it,message.contains(it)) }
        val all=req.openTasks+req.tradeTasks+req.otherTasks
        all.forEach { assertTrue(message.contains("\"taskId\":\"${it.id}\"")) }
        assertEquals(all.map { it.id }.toSet(),req.knownTaskIds)
    }

    @Test fun unknownCounterpartStillGetsSiteTodayAllTasksAndDirectory() {
        apply(call(1),analysis(cp("김민수","△△철강"),"오전 통화 요약",listOf(create("단가 재견적 받기")),"현장 메모"))
        val unknown=call(2,null,at=started+120_000L)
        val req=store().use { it.request(unknown) }
        assertNull(req.person);assertNull(req.company);assertEquals("현장 메모",req.siteMemo)
        assertEquals(listOf("오전 통화 요약"),req.todayCalls.map { it.summary })
        assertEquals(listOf("단가 재견적 받기"),req.otherTasks.map { it.title });assertTrue(req.openTasks.isEmpty())
        assertEquals(listOf("△△철강"),req.companyNames)
        val m=ClaudeCallAnalyzer.userMessage(req)
        assertTrue(m.contains("[오늘 다른 통화 요약]"));assertTrue(m.contains("아직 모르는 상대"));assertTrue(m.contains("[이름 목록]"))
    }

    @Test fun requestSizesAreLimited() {
        val big=(1..60).map { ClaudeCallRequest.OpenTask("wf_$it","일 $it","","open","업체$it") }
        val req=ClaudeCallRequest("t","시각",null,0,big,emptyList(),siteMemo="가".repeat(2000),
            company=ClaudeCallRequest.CompanyCard("A","철근","나".repeat(2000)),companyCalls=(1..9).map { ClaudeCallRequest.PastCall("2026-10-0$it","누구","다".repeat(900)) },
            tradeTasks=big,otherTasks=big,companyNames=(1..200).map { "업체이름$it" },personNames=(1..200).map { "사람이름$it" })
        val m=ClaudeCallAnalyzer.userMessage(req)
        assertEquals(30+10+40,Regex("\"taskId\"").findAll(m).count())
        assertEquals(5,m.lines().count { it.startsWith("- 2026-10-0") })
        assertFalse(m.contains("가".repeat(601)));assertFalse(m.contains("나".repeat(401)));assertFalse(m.contains("다".repeat(241)))
        val names=m.lines().first { it.startsWith("[이름 목록]") }
        assertEquals(60+200,Regex("업체이름\\d+|사람이름\\d+").findAll(names).count())
    }

    @Test fun filterKeepsSafetyRulesAndDropsInventedNames() {
        val req=ClaudeCallRequest(text,"시각","010-1111-0001",0,listOf(ClaudeCallRequest.OpenTask("wf_1","a","","open")),emptyList(),
            companyNames=listOf("△△철강"),personNames=listOf("김민수"))
        val raw=analysis(cp("김민수","□□콘크리트","과장"),tasks=listOf(
            ClaudeCallAnalysis.Task("create",null,"x","","철근 단가는 내일까지","없는업체"),
            ClaudeCallAnalysis.Task("create",null,"지어낸","","전혀 없는 말","△△철강"),
            ClaudeCallAnalysis.Task("update","wf_nope","y","","철근 단가는 내일까지","△△철강"),
            ClaudeCallAnalysis.Task("update","wf_1","z","","철근 단가는 내일까지","△△ 철강")),site="가".repeat(900),memo="나".repeat(900))
        val f=ClaudeCallAnalyzer().filter(raw,req)
        assertEquals("김민수",f.counterpart!!.personName);assertNull(f.counterpart!!.companyName) // invented company dropped
        assertEquals(listOf("x","z"),f.tasks.map { it.title });assertNull(f.tasks[0].company);assertEquals("△△ 철강",f.tasks[1].company)
        assertEquals(600,f.siteMemo!!.length);assertEquals(400,f.companyMemo!!.length)
        val staff=ClaudeCallAnalyzer().filter(analysis(cp("김민수","△△철강",staff=true)),req);assertNull(staff.counterpart!!.companyName)
    }

    @Test fun parseAndToJsonRoundTripNewFields() {
        val a=analysis(cp("김민수","△△철강","과장"),site="s",memo="m",trade="철근",tasks=listOf(create("t","△△철강")))
        assertEquals(a,ClaudeCallAnalysis.parse(a.toJson()))
        val legacy=ClaudeCallAnalysis.parse(JSONObject("{\"summary\":\"s\",\"counterpartName\":\"김과장\",\"tasks\":[]}"))
        assertEquals("김과장",legacy.counterpart!!.personName);assertNull(legacy.siteMemo)
    }

    @Test fun oneNumberCanBelongToSeveralPeopleAndTheNameDecidesWho() {
        val office="010-5555-0001"
        apply(call(1,office),analysis(cp("김민수","△△철강","과장")))
        // The same number answered by someone with a clearly different name: a second person, not a rewrite of the first.
        apply(call(2,office),analysis(cp("박지훈","△△철강","대리")))
        assertEquals(2,count("people"));assertEquals(2,count("people_phones"))
        assertEquals("과장",port().use { it.query("SELECT role FROM people WHERE name='김민수'").single()["role"] })
        assertNull(port().use { it.query("SELECT phone_key FROM people WHERE name='박지훈'").single()["phone_key"] }) // phone_key stays unique, people_phones holds both
        // A misheard jamo or a title is still the first person; so is a repeat.
        apply(call(3,office),analysis(cp("김민서","△△철강")))
        apply(call(4,office),analysis(cp("박지훈 대리","△△철강")))
        assertEquals(2,count("people"))
        // A call by number only goes to the latest answerer, and the request names the others on that number.
        val next=call(5,office)
        val req=store().use { it.request(next) }
        assertEquals("박지훈",req.person!!.name);assertEquals(listOf("김민수(△△철강 과장)"),req.sameNumberPeople)
        assertTrue(ClaudeCallAnalyzer.userMessage(req).contains("[같은 번호를 쓰는 다른 사람] 김민수(△△철강 과장)"))
        // Someone known only by number takes the first name that is heard.
        val ledgerId=store().use { it.ledgerIdFor(call(6,"010-7777-0007")) }
        assertNotNull(ledgerId);assertEquals(3,count("people"))
        apply(call(7,"010-7777-0007"),analysis(cp("최영호","□□전기")))
        assertEquals(3,count("people"));assertEquals("최영호",port().use { it.query("SELECT name FROM people WHERE phone_key='01077770007'").single()["name"] })
    }

    @Test fun companySpellingsAreOneCompanyAndANearSpellingOnlyOnTheSameNumber() {
        apply(call(1),analysis(cp("김민수","(주)△△철강")))
        apply(call(2,"010-2222-0002"),analysis(cp("박지훈"," △△ 철강 ")))
        apply(call(3,"010-2222-0003"),analysis(cp("최영호","△△철강 주식회사")))
        assertEquals(1,count("companies"))
        // One jamo off, on the number of someone who belongs to the company: the same company, remembered as another spelling.
        apply(call(4),analysis(cp("김민수","△△철광")))
        assertEquals(1,count("companies"));assertEquals("△△철광",port().use { it.query("SELECT name_key FROM company_aliases").single()["name_key"] })
        apply(call(5,"010-4444-0004"),analysis(cp("한소장","△△철광")))   // known spelling now
        assertEquals(1,count("companies"))
        // One jamo off on another number: a different company.
        apply(call(6,"010-4444-0005"),analysis(cp("오세훈","△△철감")))
        assertEquals(2,count("companies"))
        // The ledger's merge keeps the old spelling as an alias.
        store().use { it.applyLedger(JSONObject("{\"rev\":2,\"companies\":[{\"name\":\"△△철감\",\"mergedInto\":\"△△철강\"}]}")) }
        assertEquals(1,count("companies"));assertEquals(2,count("company_aliases"))
        assertEquals(5,count("people")) // 김민수, 박지훈, 최영호, 한소장, 오세훈
    }

    @Test fun theNameListCarriesCompanyTitleAndCorrectedSpellings() {
        apply(call(1),analysis(cp("김철주","△△철강","과장")))
        val id=store().use { it.peopleOf(port().use { p -> p.query("SELECT id FROM companies").single()["id"] as String }).single().id }
        store().use { it.renamePerson(id,"김철수") }
        apply(call(2,"010-8888-0001"),analysis(cp("박영희",null,"공무",staff=true)))
        val req=store().use { it.request(call(3,"010-9999-0001")) }
        assertEquals("△△철강 과장, 오인식 김철주",req.personDetails["김철수"]);assertEquals("우리 회사 공무",req.personDetails["박영희"])
        val m=ClaudeCallAnalyzer.userMessage(req)
        assertTrue(m,m.contains("김철수(△△철강 과장, 오인식 김철주)"))
    }

    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun settle(check:()->Boolean) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }

    @Test fun companiesScreenListsDetailsAndUsesBigPlainText() {
        apply(call(1),analysis(cp("김민수","△△철강"),"철근 단가 협의",listOf(create("단가 재견적 받기")),"현장 메모",memo="단가 재견적 진행",trade="철근"))
        val a=Robolectric.buildActivity(CompaniesActivity::class.java).setup().get()
        settle { texts(a).any { it.text.toString()=="△△철강" } }
        val english=Regex("[A-Z]{3,}_[A-Z_]+|\\b(WAITING|FAILED|TRANSCRIBED|ANALYZED|null)\\b")
        fun check() {
            val tv=texts(a)
            assertTrue(tv.filter { it !is android.widget.Button }.all { it.textSize/it.resources.displayMetrics.scaledDensity>=18f })
            assertFalse(english.containsMatchIn(tv.joinToString("\n") { it.text }))
        }
        check()
        val list=texts(a).joinToString("\n") { it.text }
        assertTrue(list,list.contains("철근"));assertTrue(list.contains("체크할 일 1개"))
        views(a.window.decorView).first { (it.tag as? String)?.startsWith("company_")==true }.performClick()
        settle { texts(a).any { it.text.toString().startsWith("체크할 일 (1)") } }
        check()
        val detail=texts(a).joinToString("\n") { it.text }
        assertTrue(detail,detail.contains("단가 재견적 진행"));assertTrue(detail.contains("단가 재견적 받기"));assertTrue(detail.contains("철근 단가 협의"))
    }

    @Test fun renamedPersonShowsEverywhereAndOldSpellingStillMatches() {
        val first=call(1,counterpart=null)
        apply(first,analysis(cp("김철주","△△철강","과장")))
        val id=store().use { it.peopleOf(port().use { p -> p.query("SELECT id FROM companies").single()["id"] as String }).single().id }
        assertFalse(store().use { it.renamePerson(id,"   ") })
        assertTrue(store().use { it.renamePerson(id," 김철수 ") })
        assertTrue(store().use { it.record(first.recordId)!!.displayName }.contains("김철수"))
        // The model hears the old spelling again: same person, corrected name.
        val second=call(2,counterpart=null)
        apply(second,analysis(cp("김철주","△△철강")))
        assertEquals(1,count("people"))
        assertTrue(store().use { it.record(second.recordId)!!.displayName }.contains("김철수"))
        val names=store().use { it.request(call(3,counterpart=null)).personNames }
        assertTrue(names.toString(),"김철수" in names && "김철주" !in names)
    }

    @Test fun companiesScreenRenamesAPerson() {
        apply(call(1),analysis(cp("김철주","△△철강","과장")))
        apply(call(2,counterpart="010-2222-0002"),analysis(cp("박영희",null,"공무",staff=true)))
        val a=Robolectric.buildActivity(CompaniesActivity::class.java).setup().get()
        settle { texts(a).any { it.text.toString()=="업체 없는 사람 (우리 직원 등)" } }
        assertTrue(texts(a).any { it.text.toString()=="박영희" })
        views(a.window.decorView).first { (it.tag as? String)?.startsWith("company_")==true }.performClick()
        settle { texts(a).any { it.text.toString()=="사람 (1)" } }
        views(a.window.decorView).first { (it.tag as? String)?.startsWith("person_rename_")==true }.performClick()
        val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val input=views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single()
        assertEquals("김철주",input.text.toString())
        input.setText("김철수")
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        settle { texts(a).any { it.text.toString()=="김철수" } }
        assertFalse(texts(a).any { it.text.toString()=="김철주" })
    }

    @Test fun personalTextIsDeletedAndPersonalCallHidden() {
        val day=java.time.LocalDate.of(2026,10,29)
        store().use { it.insertMessage("sms:01012345678:9",started,"01012345678","[오전 10:00 받은 문자] 오늘 저녁 뭐 먹어?") }
        val text=store().use { s -> s.day(day).single { it.kind=="문자" } }
        store().use { it.applyAnalysis(text.recordId,ClaudeCallAnalysis("개인 내용",emptyList(),null,null,emptyList(),emptyList(),business=false)) }
        assertNull(store().use { it.record(text.recordId) })
        assertEquals(0,port().use { (it.query("SELECT COUNT(*) AS n FROM recordings WHERE recording_id='sms:01012345678:9'").single()["n"] as Number).toInt() })
        val c=call(1)
        apply(c,ClaudeCallAnalysis("개인 내용",emptyList(),null,null,emptyList(),emptyList(),business=false))
        assertEquals("ANALYZED",store().use { it.record(c.recordId)!!.state })
        assertEquals("",store().use { it.record(c.recordId)!!.transcript })
        assertTrue(store().use { s -> s.day(day).none { it.recordId==c.recordId } })
        assertEquals(0,count("companies"))
    }

    @Test fun workTextBecomesARecordWithCheckItems() {
        store().use { it.insertMessage("sms:01011110001:3",started,"01011110001","[오전 10:00 받은 문자] 철근 반입 금요일 오전으로 확정했습니다") }
        val r=store().use { s -> s.day(java.time.LocalDate.of(2026,10,29)).single { it.kind=="문자" } }
        store().use { it.applyAnalysis(r.recordId,analysis(cp("김민수","△△철강"),"철근 반입 확정",listOf(ClaudeCallAnalysis.Task("create",null,"금요일 철근 반입 확인","2026-10-30","철근 반입 금요일 오전으로 확정",null)))) }
        assertTrue(store().use { it.openItems() }.any { it.title=="금요일 철근 반입 확인" && it.company=="△△철강" })
    }

    @Test fun morningLineHomeDoorAndSettingsMemo() {
        apply(call(1),analysis(cp("김민수","△△철강"),"요약",listOf(create("단가 재견적 받기")),"현장 메모 본문"))
        val m=store().use { CallDigestText.morning(it,java.time.LocalDate.of(2026,10,30)) }
        assertTrue(m.lines.toString(),m.lines.any { it.startsWith("[△△철강] 단가 재견적 받기, ") && it.endsWith(" 요청") })
        val home=Robolectric.buildActivity(HomeActivity::class.java).setup().get()
        settle { home.window.decorView.findViewWithTag<View>("home_calls_button")!=null }
        home.render(HomeActivity.Snapshot(true,true,true,"READY",0,0,0,0,0,0,companies=3))
        val door=home.window.decorView.findViewWithTag<View>("home_companies_button")
        assertNotNull(door);assertTrue(views(door).filterIsInstance<TextView>().joinToString { it.text }.contains("업체"))
        val settings=Robolectric.buildActivity(CallSettingsActivity::class.java).setup().get()
        settle { texts(settings).any { it.text.toString()=="현장 상황 메모" } }
        settle { settings.window.decorView.findViewWithTag<TextView>("site_memo")?.text?.toString()=="현장 메모 본문" }
    }
}
