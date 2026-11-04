package kr.callbrief.pipeline

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import kr.callbrief.GeneralLocalStore
import kr.callbrief.ProtectedTextHostTestBase
import kr.callbrief.ShadowTextAtomicFile
import kr.callbrief.ShadowTextKeys
import kr.co.callsummary.core.android.AndroidSqlPort
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** v2.8 r9-3: the person page (head, parts in order, call and text buttons, shared numbers, personal) and the ways into it.
 * Generated public data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[ShadowTextKeys::class,ShadowTextKeys.ExistingApprovedSettings::class,ShadowTextAtomicFile::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonScreenTest:ProtectedTextHostTestBase() {
    private lateinit var context:Context
    private var now=System.currentTimeMillis()
    private val started=now-2*3_600_000L
    private fun port()=AndroidSqlPort(context,GeneralLocalStore.DATABASE,context.assets.open("call_core_schema.sql"))
    private fun store()=CallRecordStore(context) { now }
    private val ids=(1..6).map { "rec$it" }
    private val first="010-1111-0001";private val firstKey="01011110001"

    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        kr.callbrief.IeumApplication.shutdown()
        port().use { p -> p.transaction {
            p.execute("INSERT OR IGNORE INTO board_state(owner_key,revision) VALUES(?,0)",GeneralLocalStore.OWNER)
            p.execute("INSERT INTO folder_grants VALUES('g1',?,'content://generated/tree',1,1,1)",GeneralLocalStore.OWNER)
            ids.forEach { id -> p.execute("INSERT INTO recordings(recording_id,owner_key,grant_id,document_id,display_name,mime_type,byte_count,first_seen_ms,last_seen_ms,source_seq,source_version,head_revision) VALUES(?,?,'g1',?,?,'audio/wav',10,0,0,1,1,1)",id,GeneralLocalStore.OWNER,id,"$id.wav") }
            null
        } }
    }
    private fun call(i:Int,counterpart:String?=first,at:Long=started+i*60_000L,body:String="네 단가 다시 보내드릴게요."):CallRecord {
        val t=CallTranscript("rec$i",1,"a".repeat(64),at,95_000,counterpart,body,listOf(CallTranscript.Segment(0,1,body)),"m","b".repeat(64),true)
        return store().use { it.insert(t);it.record(CallRecordStore.recordId(t))!! }
    }
    private fun analysis(person:String,company:String?,role:String?=null,summary:String="철근 단가를 다시 보내기로 했어요.",tasks:List<ClaudeCallAnalysis.Task> =emptyList())=
        ClaudeCallAnalysis(summary,emptyList(),person,company,emptyList(),tasks,ClaudeCallAnalysis.Counterpart(person,company,role,false))
    private fun create(title:String)=ClaudeCallAnalysis.Task("create",null,title,"","단가 다시 보내드릴게요",null)
    private fun apply(r:CallRecord,a:ClaudeCallAnalysis)=store().use { it.applyAnalysis(r.recordId,a) }
    private fun personNamed(name:String)=port().use { it.query("SELECT id FROM people WHERE name=?",name).single()["id"] as String }

    private fun views(v:View):List<View> =listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun texts(a:android.app.Activity)=views(a.window.decorView).filterIsInstance<TextView>()
    private fun words(a:android.app.Activity)=texts(a).joinToString("\n") { it.text }
    private fun <T:View> tagged(a:android.app.Activity,tag:String):T? =a.window.decorView.findViewWithTag(tag)
    private fun settle(check:()->Boolean) { repeat(300) { shadowOf(Looper.getMainLooper()).idle();if(check())return;Thread.sleep(10) };fail("UI did not settle") }
    private fun onPerson(a:MainActivity,name:String) = settle { a.screen==Screen.PERSON && tagged<TextView>(a,"page_title")?.text?.toString()==name && tagged<View>(a,"person_section_recent")!=null }

    /** A person with a company, a call log line, an open check item and a thread. */
    private fun seedOne():Pair<CallRecord,String> {
        val r=call(1)
        apply(r,analysis("김민수","탄탄이엔씨","대표",tasks=listOf(create("철근 단가 견적서 보내기"))))
        val id=personNamed("김민수")
        port().use { p -> p.transaction {
            p.execute("INSERT INTO call_log(log_id,number_key,name,start_ms,duration_ms,type) VALUES(1,?,NULL,?,192000,1)",firstKey,started+30*60_000L)
            p.execute("INSERT INTO issues(id,ord,title,status,companies,updated_ms,record_keys) VALUES('i1',0,'3층 철근 납품','견적 다시 받는 중','[\"탄탄이엔씨\"]',?,?)",now,"[\"${CallInbox.recordKey(r.recordId)}\"]")
            null } }
        return r to id
    }

    @Test fun headPartsInOrderAndTheButtonsCallAndText() {
        val (r,id)=seedOne()
        val a=launchMain(Screen.HOME)
        a.openPerson(id)
        onPerson(a,"김민수")
        assertEquals("탄탄이엔씨 대표",tagged<TextView>(a,"page_subtitle")!!.text.toString())
        // The parts in the order of the design: last call, check items, threads, latest calls and texts, then the two links.
        val order=views(a.column).mapNotNull { it.tag as? String }
        val parts=listOf("person_section_last","person_last","person_section_tasks","person_tasks","person_section_issues","person_issues","person_section_recent","person_recent","person_rename","person_personal")
        assertTrue(order.toString(),parts.all { it in order })
        assertEquals(parts,parts.sortedBy { order.indexOf(it) })
        val all=words(a)
        assertTrue(all,all.contains("받은 전화") && all.contains("3분 12초"))
        assertTrue(all,all.contains("철근 단가 견적서 보내기") && all.contains("3층 철근 납품") && all.contains("견적 다시 받는 중"))
        assertNotNull(tagged<View>(a,"person_recent_${r.recordId}"))
        assertTrue(views(a.column).any { it.tag=="task_done" })
        // The number is never written on the page (the dialer shows it).
        assertFalse(all,Regex("010|1111|0001").containsMatchIn(all))
        // Seniors' rules: large type, plain Korean, no middle dot.
        val english=Regex("[A-Z]{3,}_[A-Z_]+|\\b(WAITING|FAILED|TRANSCRIBED|ANALYZED|null)\\b")
        assertFalse(all,english.containsMatchIn(all) || all.contains("·") || all.contains("…"))
        val small=texts(a).filter { it !is Button && it.textSize/it.resources.displayMetrics.scaledDensity<((it.getTag(kr.callbrief.R.id.ui_min_sp) as? Float) ?: 18f) }
        assertTrue(small.joinToString { it.text },small.isEmpty())
        // The two round 44dp buttons: the dialer and the messages app, the number filled in.
        val call=tagged<View>(a,"person_call")!!;val text=tagged<View>(a,"person_text")!!
        assertEquals(CallUi.dp(a,44),call.layoutParams.width);assertEquals(CallUi.dp(a,44),text.layoutParams.height)
        call.performClick()
        val dial=shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL,dial.action);assertEquals(firstKey,dial.data!!.schemeSpecificPart)
        text.performClick()
        val sms=shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO,sms.action);assertEquals("smsto",sms.data!!.scheme);assertEquals(firstKey,sms.data!!.schemeSpecificPart)
        assertEquals(Intent.ACTION_SENDTO,PersonScreen.smsIntent(firstKey).action)
        // No tab of its own: the tab it was opened from stays marked.
        assertEquals(CallUi.Tab.HOME,a.barTab())
    }

    @Test fun backGoesToTheScreenItCameFromAsItWas() {
        val (_,id)=seedOne()
        val a=launchMain(Screen.COMPANIES)
        settle { texts(a).any { it.text.toString()=="탄탄이엔씨" } }
        views(a.window.decorView).first { (it.tag as? String)?.startsWith("company_")==true }.performClick()
        settle { texts(a).any { it.text.toString()=="사람 (1)" } }
        // The person row has no menu or rename link any more; the whole row opens the person page.
        assertNull(views(a.window.decorView).firstOrNull { (it.tag as? String)?.startsWith("person_rename_")==true })
        tagged<View>(a,"person_$id")!!.performClick()
        onPerson(a,"김민수")
        assertEquals(Screen.COMPANIES,a.personFrom);assertEquals(CallUi.Tab.COMPANIES,a.barTab())
        a.back()
        settle { a.screen==Screen.COMPANIES && texts(a).any { it.text.toString()=="사람 (1)" } }
        assertNotNull("the company page is still open",a.companies.detail)
        a.back()
        settle { a.screen==Screen.COMPANIES && a.companies.detail==null }
    }

    @Test fun aSharedNumberShowsWhoUsesItAndChipsMoveBetweenThem() {
        val (_,id)=seedOne()
        apply(call(2,"010-2222-0002"),analysis("박영희","탄탄이엔씨","대리"))
        val other=personNamed("박영희")
        port().use { p -> p.execute("INSERT OR IGNORE INTO people_phones VALUES(?,?)",firstKey,other);p.execute("UPDATE people SET updated_ms=? WHERE id=?",now+1,other) }
        val a=launchMain(Screen.TASKS)
        a.openPerson(id)
        onPerson(a,"김민수")
        assertEquals("이 번호를 쓰는 사람: 박영희 대리 외 1명",tagged<TextView>(a,"person_shared")!!.text.toString())
        assertNotNull(tagged<View>(a,"person_chip_$id"));val chip=tagged<TextView>(a,"person_chip_$other")!!
        assertTrue(chip.minHeight>=CallUi.dp(a,44))
        chip.performClick()
        onPerson(a,"박영희")
        assertEquals(other,a.person.personId)
        a.back()
        settle { a.screen==Screen.TASKS }
    }

    @Test fun keepingTheNumberPersonalAsksFirstThenRemembersIt() {
        val (_,id)=seedOne()
        val a=launchMain(Screen.HOME)
        a.openPerson(id)
        onPerson(a,"김민수")
        tagged<View>(a,"person_personal")!!.performClick()
        val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertFalse("nothing is kept before the yes",store().use { it.isPersonal(firstKey) })
        dialog.window!!.decorView.findViewWithTag<View>("sheet_primary").performClick()
        settle { tagged<View>(a,"person_personal_done")!=null }
        assertTrue(store().use { it.isPersonal(firstKey) })
        assertNull(tagged<View>(a,"person_personal"))
    }

    @Test fun renameIsOnThePersonPage() {
        val (_,id)=seedOne()
        val a=launchMain(Screen.HOME)
        a.openPerson(id)
        onPerson(a,"김민수")
        tagged<View>(a,"person_rename")!!.performClick()
        val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val input=views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single()
        assertEquals("김민수",input.text.toString());input.setText("김민서")
        dialog.window!!.decorView.findViewWithTag<View>("sheet_primary").performClick()
        onPerson(a,"김민서")
    }

    @Test fun everyNameLeadsToThePersonPage() {
        val r=call(1,at=now-60_000L)
        apply(r,analysis("김민수","탄탄이엔씨","대표",tasks=listOf(create("철근 단가 견적서 보내기"))))
        val id=personNamed("김민수")
        // Home: the memo's badge.
        val home=launchMain(Screen.HOME)
        settle { tagged<View>(home,"home_memo_${r.recordId}")!=null }
        tagged<View>(home,"home_memo_${r.recordId}")!!.findViewWithTag<View>("memo_person").performClick()
        onPerson(home,"김민수");assertEquals(Screen.HOME,home.personFrom)
        // Home: the check row's "김민수 님" (the first-look list holds today's items).
        home.back();settle { home.screen==Screen.HOME && tagged<View>(home,"task_who_person")!=null }
        tagged<View>(home,"task_who_person")!!.performClick()
        onPerson(home,"김민수")
        // Calls: the row's name, then the call page's link.
        val calls=launchMain(Screen.CALLS,dayMs=r.dayMs)
        settle { tagged<View>(calls,"call_${r.recordId}")!=null }
        tagged<View>(calls,"call_${r.recordId}")!!.findViewWithTag<View>("memo_who").performClick()
        onPerson(calls,"김민수");assertEquals(Screen.CALLS,calls.personFrom)
        calls.back();settle { calls.screen==Screen.CALLS && tagged<View>(calls,"call_${r.recordId}")!=null }
        tagged<View>(calls,"call_${r.recordId}")!!.performClick()
        settle { tagged<View>(calls,"call_person_link")!=null }
        tagged<View>(calls,"call_person_link")!!.performClick()
        onPerson(calls,"김민수")
        calls.back();settle { calls.screen==Screen.CALLS && calls.calls.detail==r.recordId }
        // Check items: "김민수 님" opens the person, the rest of the row still opens the call.
        val tasks=launchMain(Screen.TASKS)
        settle { tagged<View>(tasks,"task_who_person")!=null }
        tagged<View>(tasks,"task_who_person")!!.performClick()
        onPerson(tasks,"김민수");assertEquals(Screen.TASKS,tasks.personFrom)
    }
}
